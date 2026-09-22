package fst

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BucketingTest {
    private fun tmpStore(): Store = Store(java.nio.file.Files.createTempDirectory("fstb"))

    private fun flagWithRollout(store: Store, weights: List<Pair<String, Int>>, rulesOrder: List<String>? = null, salt: String = "salt-v1") {
        store.createFlag("roll", "R", "id", emptyList())
        val ruleA = Rule("ra", "A", listOf(Condition("ca", "a", "eq", true)), null,
            weights.map { Distribution(it.first, it.second) })
        val ruleB = Rule("rb", "B", listOf(Condition("cb", "b", "eq", true)), Serve("variant", "off"), null)
        store.publishVersion("roll", weights.map { it.first }.distinct(), emptyList(),
            if (rulesOrder == null) listOf(ruleA, ruleB) else rulesOrder.map { if (it == "ra") ruleA else ruleB },
            Serve("off"), salt, "v1")
    }

    @Test
    fun `hash input and bucket are deterministic public values`() {
        val b1 = Bucketing.bucket("flag", "s", "user-1")
        val b2 = Bucketing.bucket("flag", "s", "user-1")
        assertEquals(b1, b2)
        assertEquals(0..99, b1..b1)
        assertEquals("flag|s|user-1", Bucketing.hashInput("flag", "s", "user-1"))
        // different salt => different input documented
        assertNotEquals(Bucketing.hashInput("flag", "s2", "user-1"), Bucketing.hashInput("flag", "s", "user-1"))
    }

    @Test
    fun `zero and one hundred weight endpoints are exact`() {
        val dir = java.nio.file.Files.createTempDirectory("fstb")
        val store = Store(dir)
        flagWithRollout(store, listOf("x" to 0, "y" to 100))
        val snap = Engine.snapshot(store.project)
        // brute force: across 2000 ids everything must land on y, never x
        repeat(2000) { i ->
            val out = Engine.evaluate(snap, "roll", mapOf("id" to "id-$i", "a" to true))
            assertEquals("y", out.served.display(), "id-$i got ${out.served.display()} bucket=${Bucketing.bucket("roll", "salt-v1", "id-$i")}")
        }
        val store2 = Store(java.nio.file.Files.createTempDirectory("fstb2"))
        flagWithRollout(store2, listOf("x" to 100, "y" to 0))
        val snap2 = Engine.snapshot(store2.project)
        repeat(500) { i ->
            val out = Engine.evaluate(snap2, "roll", mapOf("id" to "id-$i", "a" to true))
            assertEquals("x", out.served.display())
        }
    }

    @Test
    fun `bucket band selection covers exact boundaries`() {
        val roll = listOf(Distribution("lo", 30), Distribution("mid", 40), Distribution("hi", 30))
        assertEquals("lo", Bucketing.choose(roll, 0))
        assertEquals("lo", Bucketing.choose(roll, 29))
        assertEquals("mid", Bucketing.choose(roll, 30))
        assertEquals("mid", Bucketing.choose(roll, 69))
        assertEquals("hi", Bucketing.choose(roll, 70))
        assertEquals("hi", Bucketing.choose(roll, 99))
    }

    @Test
    fun `reordering rules without changing rollout clause does not move bucket`() {
        val dir = java.nio.file.Files.createTempDirectory("fst")
        val store = Store(dir)
        store.createFlag("roll", "R", "id", emptyList())
        val rollRule = Rule("ra", "rollout-rule", listOf(Condition("ca", "a", "eq", true)),
            null, listOf(Distribution("v1", 30), Distribution("v2", 70)))
        val otherRule = Rule("rb", "other", listOf(Condition("cb", "b", "eq", true)), Serve("variant", "v1"), null)
        store.publishVersion("roll", listOf("v1", "v2"), emptyList(), listOf(rollRule, otherRule), Serve("off"), "SALT", "v1")

        // find ids whose bucket is within v1 band and whose first-rule conditions are satisfied
        val ids = (0..3000).map { "u$it" }.filter { Bucketing.bucket("roll", "SALT", it) in 0..29 }.take(5)
        check(ids.size == 5)

        val v1 = Engine.snapshot(store.project)
        val before = ids.associateWith { Engine.evaluate(v1, "roll", mapOf("id" to it, "a" to true, "b" to true)).served.display() }
        val bucketsBefore = ids.associateWith { Bucketing.bucket("roll", "SALT", it) }

        // reorder: other rule first (its condition b=true will now win!) — but the trace bucket
        // of the rollout clause, when reached, must be identical. Check clause-only scenario:
        // reorder where rollout rule still matches by making rb condition false.
        store.reorderRules("roll", listOf("rb", "ra"))
        val v2 = Engine.snapshot(store.project)
        val bucketsAfter = ids.associateWith { Bucketing.bucket("roll", "SALT", it) }
        assertEquals(bucketsBefore, bucketsAfter, "same flagKey+salt+id => same bucket regardless of rule index")

        // rollout clause still reached because rb condition false
        val after = ids.associateWith { Engine.evaluate(v2, "roll", mapOf("id" to it, "a" to true, "b" to false)).served.display() }
        assertEquals(before, after)

        // trace shows same hash input and bucket
        val trace = Engine.evaluate(v2, "roll", mapOf("id" to ids.first(), "a" to true, "b" to false)).root
        val bucketNode = flatten(trace).first { it.kind == "bucket" }
        assertEquals(bucketsBefore[ids.first()], bucketNode.detail["bucket"])
        assertEquals("roll|SALT|${ids.first()}", bucketNode.detail["hashInput"])
    }

    @Test
    fun `missing stable id skips rollout to off and explains why`() {
        val dir = java.nio.file.Files.createTempDirectory("fst")
        val store = Store(dir)
        flagWithRollout(store, listOf("x" to 50, "y" to 50))
        val snap = Engine.snapshot(store.project)
        val out = Engine.evaluate(snap, "roll", mapOf("a" to true))
        assertEquals("off", out.served.display())
        assertTrue(flatten(out.root).any { it.kind == "rollout-no-id" })
    }

    private fun flatten(n: TNode): List<TNode> = listOf(n) + n.children.flatMap { flatten(it) }
}
