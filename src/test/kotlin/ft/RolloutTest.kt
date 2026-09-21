package ft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RolloutTest {
    private fun rolloutFlag(
        store: Store,
        projectId: String,
        key: String,
        salt: String = "salt1",
        weightBp: Int = 1000
    ) {
        store.publishSimple(
            projectId, key,
            rules = listOf(
                Rule(
                    id = "r1",
                    name = "split",
                conditions = listOf(Condition("eligible", Operator.EQ, JsonBoolean(true))),
                    rollout = listOf(RolloutClause(ServeValue("blue"), weightBp)),
                    fallbackServe = ServeValue.OFF,
                    salt = salt
                )
            )
        )
    }

    @Test
    fun `bucketing is stable for same flag key salt and stable id`() {
        val store = tempStore()
        val project = store.testProject()
        rolloutFlag(store, project.id, "f", salt = "stable-salt")
        val ctx = json("""{"user":{"id":"user-42"},"eligible":true}""")

        val first = store.evalOnce(project.id, "f", ctx)
        val second = store.evalOnce(project.id, "f", ctx)
        assertEquals(first.result.name, second.result.name)

        val rollout = first.trace["steps"].asArray!!.items.first().asObject!!
            .get("rollout").asObject!!
        assertEquals("f.stable-salt.user-42", rollout["hashInput"].asString)
        assertEquals("murmur3_x86_32_seed0_mod_10000", rollout["algorithm"].asString)
    }

    @Test
    fun `weight zero never receives traffic`() {
        val store = tempStore()
        val project = store.testProject()
        rolloutFlag(store, project.id, "f", weightBp = 0)
        val result = store.evalOnce(
            project.id, "f", json("""{"user":{"id":"anyone"},"eligible":true}""")
        )
        assertEquals("off", result.result.name)
    }

    @Test
    fun `full weight always serves the variant`() {
        val store = tempStore()
        val project = store.testProject()
        rolloutFlag(store, project.id, "f", weightBp = 10000)
        repeat(200) { i ->
            val result = store.evalOnce(
                project.id, "f",
                json("""{"user":{"id":"user-$i"},"eligible":true}""")
            )
            assertEquals("blue", result.result.name, "id user-$i should always be blue")
        }
    }

    @Test
    fun `weight distributes roughly as configured over a population`() {
        val store = tempStore()
        val project = store.testProject()
        rolloutFlag(store, project.id, "f", weightBp = 3000)
        val blues = (0 until 4000).count { i ->
            val r = store.evalOnce(
                project.id, "f",
                json("""{"user":{"id":"user-$i"},"eligible":true}""")
            )
            r.result.name == "blue"
        }
        val ratio = blues.toDouble() / 4000.0
        assertTrue(ratio in 0.26..0.34, "expected ~30pct, got $ratio")
    }

    @Test
    fun `rollout without a usable stable id falls back deterministically`() {
        val store = tempStore()
        val project = store.testProject()
        rolloutFlag(store, project.id, "f")
        val missingId = store.evalOnce(
            project.id, "f", json("""{"eligible":true}""")
        )
        assertEquals("off", missingId.result.name)
        val rollout = missingId.trace["steps"].asArray!!.items.first().asObject!!
            .get("rollout").asObject!!
        assertEquals("missing", rollout["stableIdState"].asString)

        val nullId = store.evalOnce(
            project.id, "f", json("""{"user":{"id":null},"eligible":true}""")
        )
        assertEquals("off", nullId.result.name)
    }

    @Test
    fun `trace records bucket input and clause ranges`() {
        val store = tempStore()
        val project = store.testProject()
        rolloutFlag(store, project.id, "f", salt = "s", weightBp = 2500)
        val result = store.evalOnce(
            project.id, "f", json("""{"user":{"id":"abc"},"eligible":true}""")
        )
        val rollout = result.trace["steps"].asArray!!.items.first().asObject!!
            .get("rollout").asObject!!
        val bucket = rollout["bucketBp"].asNumber!!.long.toInt()
        assertTrue(bucket in 0..9999)
        val clause = rollout["clauses"].asArray!!.items.single().asObject!!
        assertEquals(0, clause["rangeStartBp"].asNumber!!.long.toInt())
        assertEquals(2500, clause["rangeEndBp"].asNumber!!.long.toInt())
        assertNotNull(rollout["salt"])
    }

    @Test
    fun `bucket endpoints are handled as half-open ranges`() {
        // Range [0,10000) covers every possible bucket.
        val store = tempStore()
        val project = store.testProject()
        rolloutFlag(store, project.id, "f", weightBp = 10000)
        listOf("edge-0", "edge-9999", "edge-middle").forEach { id ->
            val r = store.evalOnce(
                project.id, "f",
                json("""{"user":{"id":"$id"},"eligible":true}""")
            )
            assertEquals("blue", r.result.name)
        }
    }
}
