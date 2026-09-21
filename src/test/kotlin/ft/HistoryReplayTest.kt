package ft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryReplayTest {
    private fun tierRule(tier: String, serve: ServeValue) = Rule(
        "r$tier", tier,
        listOf(Condition("tier", Operator.EQ, JsonString(tier))),
        serve = serve
    )

    @Test
    fun `historical record replays against its pinned version after rules change`() {
        val store = tempStore()
        val project = store.testProject()
        store.publishSimple(
            project.id, "f",
            rules = listOf(tierRule("vip", ServeValue("blue"))),
            note = "v1"
        )
        val ctx = json("""{"tier":"vip"}""")
        val record = store.evaluateBatch(
            project.id,
            listOf(BatchRequest("f", "vip-ctx", ctx)),
            save = true
        ).single()
        assertEquals("blue", record.result.name)
        assertEquals(1, record.version)

        // Publish v2 that flips the outcome for the same context.
        store.saveDraft(
            projectId = project.id,
            key = "f",
            name = "f",
            description = "",
            rules = listOf(tierRule("vip", ServeValue("green"))),
            prerequisites = emptyList(),
            defaultValue = ServeValue.OFF,
            stableIdField = "user.id",
            publish = true,
            note = "v2 flip"
        )

        val (stored, fresh) = store.replayRecord(record.id)
        assertEquals("blue", stored.result.name)
        assertEquals("blue", fresh.result.name, "replay must be pinned to v1")
        assertEquals(1, fresh.version)

        // A fresh evaluation against latest produces v2/green.
        val latest = store.evalOnce(project.id, "f", ctx)
        assertEquals("green", latest.result.name)

        // Stored trace is immutable and still describes the old rule.
        val storedServed = stored.trace["steps"].asArray!!.items.first().asObject!!
            .get("served").asString
        assertEquals("blue", storedServed)
    }

    @Test
    fun `recorded prerequisite traces are version-bound too`() {
        val store = tempStore()
        val project = store.testProject()
        store.publishSimple(
            project.id, "base",
            rules = listOf(Rule("r", "r", emptyList(), serve = ServeValue.ON)),
            note = "v1"
        )
        store.publishSimple(
            project.id, "dependent",
            rules = listOf(Rule("r", "r", emptyList(), serve = ServeValue("feature"))),
            prerequisites = listOf(
                Prerequisite("base", listOf(ServeValue.ON), ServeValue.OFF)
            ),
            note = "v1"
        )
        val record = store.evaluateBatch(
            project.id,
            listOf(BatchRequest("dependent", "c", json("{}"))),
            save = true
        ).single()
        assertEquals("feature", record.result.name)

        // Flip base off in v2.
        store.saveDraft(
            projectId = project.id,
            key = "base",
            name = "base",
            description = "",
            rules = listOf(Rule("r", "r", emptyList(), serve = ServeValue.OFF)),
            prerequisites = emptyList(),
            defaultValue = ServeValue.OFF,
            stableIdField = "user.id",
            publish = true,
            note = "v2 off"
        )

        val (_, replay) = store.replayRecord(record.id)
        assertEquals("feature", replay.result.name)
        assertEquals(1, replay.version)

        val fresh = store.evalOnce(project.id, "dependent", json("{}"))
        assertEquals("off", fresh.result.name)
    }

    @Test
    fun `trace step order is stable across replay`() {
        val store = tempStore()
        val project = store.testProject()
        store.publishSimple(
            project.id, "f",
            rules = listOf(
                tierRule("vip", ServeValue("blue")),
                Rule("r2", "default-on", emptyList(), serve = ServeValue.ON)
            ),
            note = "v1"
        )
        val record = store.evaluateBatch(
            project.id,
            listOf(BatchRequest("f", "c", json("""{"tier":"other"}"""))),
            save = true
        ).single()
        val (stored, fresh) = store.replayRecord(record.id)
        val storedOrder = stored.trace["steps"].asArray!!.items
            .mapNotNull { it.asObject!!.get("ruleId")?.asString ?: it.asObject!!.get("type").asString }
        val freshOrder = fresh.trace["steps"].asArray!!.items
            .mapNotNull { it.asObject!!.get("ruleId")?.asString ?: it.asObject!!.get("type").asString }
        assertEquals(storedOrder, freshOrder)
        assertTrue(stored.trace.toJson() == fresh.trace.toJson())
    }
}
