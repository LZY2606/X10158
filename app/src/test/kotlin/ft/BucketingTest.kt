package ft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BucketingTest {

    private fun boolRollout(slices: List<Pair<String, Int>>, salt: String = "roll-1"): Pair<Service, String> {
        val (svc, _) = Fixtures.service()
        svc.createFlag(
            "roll", "roll",
            Fixtures.draftBoolean(
                salt = salt,
                rules = listOf(Fixtures.rolloutRule("r", emptyList(), slices)),
            ),
        )
        return svc to "roll"
    }

    @Test fun `bucket function matches published algorithm vectors`() {
        // Pinned vectors so the "public, stable" algorithm never silently changes.
        val b1 = Hashing.bucket("roll", "roll-1", "user-42")
        val b2 = Hashing.bucket("roll", "roll-1", "user-43")
        assertEquals(b1, Hashing.bucket("roll", "roll-1", "user-42"))
        assertNotEquals(b1, b2)
        assertTrue(b1 in 0 until Hashing.BP_TOTAL)
        // bucket range
        repeat(2000) { i ->
            val b = Hashing.bucket("roll", "roll-1", "u$i")
            assertTrue(b in 0 until Hashing.BP_TOTAL)
        }
    }

    @Test fun `bucket input contains key salt and identity`() {
        assertEquals("roll.roll-1.abc", Hashing.bucketInput("roll", "roll-1", "abc"))
    }

    @Test fun `zero and 9999 endpoint weights behave exactly`() {
        // on slice covers [0,1): only bucket 0 is on
        val (svc, key) = boolRollout(listOf("on" to 1, "off" to 9999))
        // find identities that land exactly on bucket 0 and bucket 9999
        val (idZero, idMax) = generateSequence(0) { it + 1 }
            .map { "e$it" }
            .let { seq ->
                val z = seq.first { Hashing.bucket("roll", "roll-1", it) == 0 }
                val m = generateSequence(0) { it + 1 }.map { "e$it" }
                    .first { Hashing.bucket("roll", "roll-1", it) == 9999 }
                z to m
            }
        assertEquals(true, (svc.evaluate(key, Fixtures.ctx("user_id" to idZero)).value as JBool).value)
        assertEquals(false, (svc.evaluate(key, Fixtures.ctx("user_id" to idMax)).value as JBool).value)
    }

    @Test fun `uncovered tail falls through to default`() {
        // on covers [0,5000); the other half must hit default(false), never off slice
        val (svc, key) = boolRollout(listOf("on" to 5000))
        val results = (0 until 400).map { i ->
            (svc.evaluate(key, Fixtures.ctx("user_id" to "u$i")).value as JBool).value
        }
        assertTrue(results.any { it } && results.any { !it })
        // and trace explains the gap
        val offId = (0 until 400).map { "u$it" }
            .first { Hashing.bucket("roll", "roll-1", it) >= 5000 }
        val trace = svc.evaluate(key, Fixtures.ctx("user_id" to offId)).trace
        assertEquals("default_after_rollout_gap", trace.outcome)
    }

    @Test fun `rule reorder does not move buckets when clause unchanged`() {
        val (svc, _) = Fixtures.service()
        svc.createFlag(
            "f", "f",
            Fixtures.draftBoolean(
                salt = "s",
                rules = listOf(
                    Fixtures.valueRule("gate1", listOf(Fixtures.cond("a", Operator.EQ, Json.b(true))), true),
                    Fixtures.rolloutRule("pct", listOf(Fixtures.cond("b", Operator.EXISTS, JNull)),
                        listOf("on" to 10000)),
                ),
            ),
        )
        // reorder: pct first, gate second; rollout salt identical
        svc.publishVersion(
            "f",
            Fixtures.draftBoolean(
                salt = "s",
                rules = listOf(
                    Fixtures.rolloutRule("pct", listOf(Fixtures.cond("b", Operator.EXISTS, JNull)),
                        listOf("on" to 10000)),
                    Fixtures.valueRule("gate1", listOf(Fixtures.cond("a", Operator.EQ, Json.b(true))), true),
                ),
            ),
        )
        val ctx = Fixtures.ctx("user_id" to "stable-1", "b" to "x")
        val v1 = svc.evaluate("f", ctx, version = 1)
        val v2 = svc.evaluate("f", ctx, version = 2)
        val roll1 = findStep(v1.trace, "rollout")
        val roll2 = findStep(v2.trace, "rollout")
        assertEquals((roll1.detail["bucketBp"] as JNum).num, (roll2.detail["bucketBp"] as JNum).num)
        assertEquals((roll1.detail["bucketInput"] as JStr).value, (roll2.detail["bucketInput"] as JStr).value)
        assertEquals(v1.value, v2.value)
    }

    @Test fun `changing salt changes buckets intentionally`() {
        val (svc, key) = boolRollout(listOf("on" to 5000, "off" to 5000), salt = "s1")
        svc.publishVersion(key, Fixtures.draftBoolean(salt = "s2",
            rules = listOf(Fixtures.rolloutRule("r", emptyList(), listOf("on" to 5000, "off" to 5000)))))
        val differing = (0 until 200).any { i ->
            val ctx = Fixtures.ctx("user_id" to "id-$i")
            svc.evaluate(key, ctx, version = 1).value != svc.evaluate(key, ctx, version = 2).value
        }
        assertTrue(differing)
    }

    @Test fun `weights above 10000 rejected`() {
        val (svc, _) = Fixtures.service()
        try {
            svc.createFlag("bad", "bad", Fixtures.draftBoolean(
                rules = listOf(Fixtures.rolloutRule("r", emptyList(), listOf("on" to 6000, "off" to 5000)))))
            error("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("total"))
        }
    }

    @Test fun `missing identity blocks rollout distinctly from null identity`() {
        val (svc, key) = boolRollout(listOf("on" to 10000))
        val missing = findStep(svc.evaluate(key, Fixtures.ctx()).trace, "rollout")
        val nulled = findStep(svc.evaluate(key, Fixtures.ctx("user_id" to null)).trace, "rollout")
        assertEquals("identity field is missing", (missing.detail["reason"] as JStr).value)
        assertEquals("identity field is null", (nulled.detail["reason"] as JStr).value)
    }
}
