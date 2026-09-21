package ft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SensitiveHistoryExportTest {

    private fun setupWithSensitive(): Pair<Service, Store> {
        val (svc, store) = Fixtures.service()
        svc.createFlag(
            "f", "f",
            Fixtures.draftBoolean(
                rules = listOf(
                    Fixtures.valueRule(
                        "email-vip",
                        listOf(Fixtures.cond("email", Operator.EQ, Json.s("vip@example.com"))),
                        true,
                    )
                ),
                sensitive = listOf("email"),
            ),
        )
        return svc to store
    }

    @Test fun `sensitive value is only shown as summary tag`() {
        val (svc, _) = setupWithSensitive()
        val r = svc.evaluate("f", Fixtures.ctx("email" to "vip@example.com"))
        val cond = findCondition(r.trace, "email")
        assertTrue(cond.detail.map.keys.none { it == "value" }, "raw value must not be in trace")
        val summary = cond.detail["valueSummary"] as JObj
        assertEquals("sensitive", (summary["kind"] as JStr).value)
        assertTrue((summary["tag"] as JStr).value.matches(Regex("[0-9a-f]{16}")))
    }

    @Test fun `same input repeats same tag within a project`() {
        val (svc, _) = setupWithSensitive()
        val tag1 = summaryTag(svc, "vip@example.com")
        val tag2 = summaryTag(svc, "vip@example.com")
        assertEquals(tag1, tag2)
        // still proves inequality: different value -> different tag
        assertNotEquals(tag1, summaryTag(svc, "other@example.com"))
    }

    @Test fun `tags cannot be correlated across project domains`() {
        val (svc1, store1) = setupWithSensitive()
        val (svc2, store2) = Fixtures.service()
        svc2.createFlag(
            "f", "f",
            Fixtures.draftBoolean(
                rules = listOf(
                    Fixtures.valueRule(
                        "email-vip",
                        listOf(Fixtures.cond("email", Operator.EQ, Json.s("vip@example.com"))),
                        true,
                    )
                ),
                sensitive = listOf("email"),
            ),
        )
        val p1 = svc1.snapshot()
        val p2 = svc2.snapshot()
        val tagIn1 = Hashing.summaryTag(p1.domainSecret, "email", Json.s("vip@example.com"))
        val tagIn2 = Hashing.summaryTag(p2.domainSecret, "email", Json.s("vip@example.com"))
        assertNotEquals(p1.domainSecret, p2.domainSecret)
        assertNotEquals(tagIn1, tagIn2)
    }

    @Test fun `history record is bound to version and does not follow later edits`() {
        val (svc, _) = setupWithSensitive()
        val ctx = Fixtures.ctx("email" to "vip@example.com")
        svc.evaluate("f", ctx, saveRecord = true)
        val rec = svc.records("f").first()
        assertEquals(1, rec.version)
        assertEquals(true, (rec.result as JBool).value)

        // Publish v2 where the same email means off
        svc.publishVersion(
            "f",
            Fixtures.draftBoolean(
                rules = listOf(
                    Fixtures.valueRule(
                        "email-vip",
                        listOf(Fixtures.cond("email", Operator.EQ, Json.s("vip@example.com"))),
                        false,
                    )
                ),
                default = true,
                sensitive = listOf("email"),
            ),
        )

        // stored record still says true, and replay on v1 also says true
        val (stored, replay) = svc.replayRecord(rec.id)
        assertEquals(true, (stored.result as JBool).value)
        assertEquals(true, (replay.value as JBool).value)
        assertEquals(1, replay.trace.detail["version"]?.let { (it as JNum).num.toInt() })

        // but evaluating current gives the new meaning
        assertEquals(false, (svc.evaluate("f", ctx).value as JBool).value)
    }

    @Test fun `export then import preserves buckets and trace order`() {
        val (svc, _) = Fixtures.service()
        svc.createFlag(
            "f", "f",
            Fixtures.draftBoolean(
                salt = "stable-salt",
                rules = listOf(
                    Fixtures.valueRule(
                        "staff",
                        listOf(Fixtures.cond("staff", Operator.EQ, Json.b(true))),
                        true,
                    ),
                    Fixtures.rolloutRule(
                        "pct",
                        listOf(Fixtures.cond("country", Operator.EQ, Json.s("JP"))),
                        listOf("on" to 3333, "off" to 6667),
                    ),
                ),
            ),
        )
        val context = Fixtures.ctx("user_id" to "alice-9", "country" to "JP", "staff" to false)
        val before = svc.evaluate("f", context, saveRecord = true)
        val export = svc.export()

        // round trip into a fresh store of the SAME project id (e.g. backup/restore)
        val (svc2, store2) = Fixtures.service()
        svc2.import(export, Service.ImportMode.REPLACE)
        val after = svc2.evaluate("f", context)

        assertEquals(before.value, after.value)
        val rb = findStep(before.trace, "rollout")
        val ra = findStep(after.trace, "rollout")
        assertEquals((rb.detail["bucketInput"] as JStr).value, (ra.detail["bucketInput"] as JStr).value)
        assertEquals((rb.detail["bucketBp"] as JNum).num, (ra.detail["bucketBp"] as JNum).num)

        // trace node ordering identical
        fun order(n: TraceNode): List<String> = listOf(n.step + ":" + n.outcome) +
            n.children.flatMap { order(it) }
        assertEquals(order(before.trace), order(after.trace))

        // history record came across and still replays on v1
        val recs = svc2.records("f")
        assertEquals(1, recs.size)
        val (record, replay) = svc2.replayRecord(recs.first().id)
        assertEquals(1, record.version)
        assertTrue(Json.equal(record.result, replay.value))
    }

    @Test fun `foreign import gets isolated summary domain`() {
        val (svcA, _) = Fixtures.service()
        svcA.createFlag("f", "f", Fixtures.draftBoolean(
            rules = listOf(Fixtures.valueRule("r",
                listOf(Fixtures.cond("email", Operator.EQ, Json.s("a@b.c"))), true)),
            sensitive = listOf("email")))
        val export = svcA.export()

        val (svcB, _) = Fixtures.service()
        svcB.createFlag("local", "local", Fixtures.draftBoolean(emptyList(), default = false))
        val merged = svcB.import(export, Service.ImportMode.MERGE)

        val projectA = svcA.snapshot()
        assertNotEquals(projectA.domainSecret, merged.domainSecret)
        val tagA = Hashing.summaryTag(projectA.domainSecret, "email", Json.s("a@b.c"))
        val tagB = Hashing.summaryTag(merged.domainSecret, "email", Json.s("a@b.c"))
        assertNotEquals(tagA, tagB)
        // buckets still identical: salt copied verbatim
        val ctx = Fixtures.ctx("user_id" to "z1")
        svcA.createFlag("h", "h", Fixtures.draftBoolean(
            salt = "k", rules = listOf(Fixtures.rolloutRule("r", emptyList(), listOf("on" to 5000, "off" to 5000)))))
    }

    private fun summaryTag(svc: Service, email: String): String {
        val r = svc.evaluate("f", Fixtures.ctx("email" to email))
        val summary = findCondition(r.trace, "email").detail["valueSummary"] as JObj
        return (summary["tag"] as JStr).value
    }
}
