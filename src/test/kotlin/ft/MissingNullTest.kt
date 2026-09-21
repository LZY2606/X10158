package ft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MissingNullTest {
    @Test
    fun `missing field and explicit null are distinguished in trace`() {
        val store = tempStore()
        val project = store.testProject()
        store.publishSimple(
            project.id, "f",
            rules = listOf(
                Rule(
                    "r1", "null check",
                    listOf(Condition("user.plan", Operator.EQ, JsonNull)),
                    serve = ServeValue.ON
                )
            )
        )

        val missing = store.evalOnce(project.id, "f", json("""{"user":{"id":"u1"}}"""))
        val explicitNull = store.evalOnce(project.id, "f", json("""{"user":{"id":"u1","plan":null}}"""))

        assertEquals("off", missing.result.name)
        assertEquals("on", explicitNull.result.name)

        val readsMissing = missing.trace["fieldReads"].asObject!!
        assertEquals("missing", readsMissing["user.plan"].asObject!!.get("state").asString)
        val readsNull = explicitNull.trace["fieldReads"].asObject!!
        assertEquals("null", readsNull["user.plan"].asObject!!.get("state").asString)
    }

    @Test
    fun `exists operator treats explicit null as present`() {
        val store = tempStore()
        val project = store.testProject()
        store.publishSimple(
            project.id, "f",
            rules = listOf(
                Rule(
                    "r1", "exists",
                    listOf(Condition("user.plan", Operator.EXISTS)),
                    serve = ServeValue.ON
                )
            )
        )

        val nullPresent = store.evalOnce(
            project.id, "f", json("""{"user":{"id":"u1","plan":null}}""")
        )
        assertEquals("on", nullPresent.result.name)
    }

    @Test
    fun `nested path into null reports missing`() {
        val store = tempStore()
        val project = store.testProject()
        store.publishSimple(
            project.id, "f",
            rules = listOf(
                Rule(
                    "r1", "deep",
                    listOf(Condition("user.plan.name", Operator.EQ, JsonString("pro"))),
                    serve = ServeValue.ON
                )
            )
        )
        val result = store.evalOnce(
            project.id, "f", json("""{"user":{"plan":null}}""")
        )
        assertEquals("off", result.result.name)
        val state = result.trace["fieldReads"].asObject!!
            .get("user.plan.name").asObject!!.get("state").asString
        assertEquals("missing", state)
        val reason = result.trace["steps"].asArray!!.items
            .first().asObject!!.get("conditions").asArray!!.items
            .first().asObject!!.get("reason").asString!!
        assertTrue(reason.contains("missing"), reason)
    }
}
