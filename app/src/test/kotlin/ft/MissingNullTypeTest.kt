package ft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Missing vs null, and no implicit string/number coercion. */
class MissingNullTypeTest {

    private fun setup(): Pair<Service, String> {
        val (svc, _) = Fixtures.service()
        svc.createFlag(
            "f", "f",
            Fixtures.draftBoolean(
                rules = listOf(
                    Fixtures.valueRule(
                        "tier-eq-3",
                        listOf(Fixtures.cond("tier", Operator.EQ, Json.n(3))),
                        true,
                    )
                )
            ),
        )
        return svc to "f"
    }

    @Test fun `missing field fails and trace says missing`() {
        val (svc, key) = setup()
        val r = svc.evaluate(key, Fixtures.ctx("user_id" to "u1"))
        assertEquals(false, (r.value as JBool).value)
        val condNode = findCondition(r.trace, "tier")
        assertEquals("failed", condNode.outcome)
        assertEquals("field missing", (condNode.detail["reason"] as JStr).value)
        assertEquals(false, (condNode.detail["present"] as JBool).value)
    }

    @Test fun `explicit null is distinct from missing`() {
        val (svc, key) = setup()
        val r = svc.evaluate(key, Fixtures.ctx("user_id" to "u1", "tier" to null))
        val condNode = findCondition(r.trace, "tier")
        assertEquals("failed", condNode.outcome)
        assertEquals(true, (condNode.detail["present"] as JBool).value)
        assertEquals("values differ", (condNode.detail["reason"] as JStr).value)
    }

    @Test fun `string 3 never equals number 3`() {
        val (svc, key) = setup()
        val r = svc.evaluate(key, Fixtures.ctx("user_id" to "u1", "tier" to "3"))
        val condNode = findCondition(r.trace, "tier")
        assertEquals("failed", condNode.outcome)
        assertTrue((condNode.detail["reason"] as JStr).value.contains("type mismatch"))
        // numeric 3 does match
        val r2 = svc.evaluate(key, Fixtures.ctx("user_id" to "u1", "tier" to 3))
        assertEquals(true, (r2.value as JBool).value)
    }

    @Test fun `ordering comparisons reject cross types`() {
        val (svc, _) = Fixtures.service()
        svc.createFlag(
            "age", "age",
            Fixtures.draftBoolean(
                rules = listOf(
                    Fixtures.valueRule(
                        "gt", listOf(Fixtures.cond("age", Operator.GT, Json.n(18))), true
                    )
                )
            ),
        )
        val stringAge = svc.evaluate("age", Fixtures.ctx("age" to "19"))
        val node = findCondition(stringAge.trace, "age")
        assertEquals("failed", node.outcome)
        assertTrue((node.detail["reason"] as JStr).value.contains("type mismatch"))

        val number = svc.evaluate("age", Fixtures.ctx("age" to 19))
        assertEquals(true, (number.value as JBool).value)
    }

    @Test fun `exists distinguishes present null`() {
        val (svc, _) = Fixtures.service()
        svc.createFlag(
            "ex", "ex",
            Fixtures.draftBoolean(
                rules = listOf(
                    Fixtures.valueRule(
                        "exists", listOf(Fixtures.cond("coupon", Operator.EXISTS, JNull)), true
                    )
                )
            ),
        )
        assertFalse((svc.evaluate("ex", Fixtures.ctx()).value as JBool).value)
        assertFalse((svc.evaluate("ex", Fixtures.ctx("coupon" to null)).value as JBool).value)
        assertTrue((svc.evaluate("ex", Fixtures.ctx("coupon" to "WELCOME")).value as JBool).value)
    }

    @Test fun `reads list includes every examined field`() {
        val (svc, key) = setup()
        val r = svc.evaluate(key, Fixtures.ctx())
        val reads = (r.trace.detail["reads"] as JArr).items.map { (it as JStr).value }
        assertTrue(reads.contains("tier"))
        // identity field is not read because this flag has no rollout rule
        assertFalse(reads.contains("user_id"))
    }
}

internal fun findCondition(node: TraceNode, field: String): TraceNode =
    findConditionOrNull(node, field) ?: error("condition for $field not found")

internal fun findConditionOrNull(node: TraceNode, field: String): TraceNode? {
    if (node.step == "condition" && (node.detail["field"] as? JStr)?.value == field) return node
    for (c in node.children) findConditionOrNull(c, field)?.let { return it }
    return null
}

internal fun findStep(node: TraceNode, step: String): TraceNode =
    findStepOrNull(node, step) ?: error("step $step not found")

internal fun findStepOrNull(node: TraceNode, step: String): TraceNode? {
    if (node.step == step) return node
    for (c in node.children) findStepOrNull(c, step)?.let { return it }
    return null
}

internal fun findAllSteps(node: TraceNode, step: String): List<TraceNode> {
    val out = mutableListOf<TraceNode>()
    fun walk(n: TraceNode) {
        if (n.step == step) out.add(n)
        n.children.forEach(::walk)
    }
    walk(node)
    return out
}
