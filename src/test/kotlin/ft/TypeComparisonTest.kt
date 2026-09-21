package ft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeComparisonTest {
    private fun storeWithRule(operator: Operator, expected: JsonValue): Pair<Store, Project> {
        val store = tempStore()
        val project = store.testProject()
        store.publishSimple(
            project.id, "f",
            rules = listOf(
                Rule("r1", "r", listOf(Condition("v", operator, expected)), serve = ServeValue.ON)
            )
        )
        return store to project
    }

    @Test
    fun `string 100 does not equal number 100`() {
        val (store, project) = storeWithRule(Operator.EQ, JsonNumber("100"))
        val result = store.evalOnce(project.id, "f", json("""{"v":"100"}"""))
        assertEquals("off", result.result.name)
        val reason = result.trace["steps"].asArray!!.items.first().asObject!!
            .get("conditions").asArray!!.items.first().asObject!!
            .get("reason").asString!!
        assertTrue(reason.contains("type mismatch"), reason)
    }

    @Test
    fun `numeric equality works for integral and decimal tokens`() {
        val (store, project) = storeWithRule(Operator.EQ, JsonNumber("100.0"))
        val result = store.evalOnce(project.id, "f", json("""{"v":100}"""))
        assertEquals("on", result.result.name)
    }

    @Test
    fun `gt never coerces strings`() {
        val (store, project) = storeWithRule(Operator.GT, JsonNumber("9"))
        val result = store.evalOnce(project.id, "f", json("""{"v":"100"}"""))
        assertEquals("off", result.result.name)
    }

    @Test
    fun `contains requires strings`() {
        val (store, project) = storeWithRule(Operator.CONTAINS, JsonString("ab"))
        val result = store.evalOnce(project.id, "f", json("""{"v":["ab"]}"""))
        assertEquals("off", result.result.name)
    }

    @Test
    fun `in uses strict membership`() {
        val (store, project) = storeWithRule(
            Operator.IN, JsonArray(listOf(JsonString("1"), JsonString("2")))
        )
        val numberOne = store.evalOnce(project.id, "f", json("""{"v":1}"""))
        val stringOne = store.evalOnce(project.id, "f", json("""{"v":"1"}"""))
        assertEquals("off", numberOne.result.name)
        assertEquals("on", stringOne.result.name)
    }

    @Test
    fun `booleans never equal strings or numbers`() {
        val (store, project) = storeWithRule(Operator.EQ, JsonBoolean(true))
        assertEquals("off", store.evalOnce(project.id, "f", json("""{"v":"true"}""")).result.name)
        assertEquals("off", store.evalOnce(project.id, "f", json("""{"v":1}""")).result.name)
        assertEquals("on", store.evalOnce(project.id, "f", json("""{"v":true}""")).result.name)
    }
}
