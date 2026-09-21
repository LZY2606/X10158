package ft

import kotlin.test.Test
import kotlin.test.assertEquals

class RuleReorderTest {
    private fun rolloutRule() = Rule(
        id = "rollout_rule",
        name = "50% 分流",
        conditions = listOf(Condition("tier", Operator.EQ, JsonString("normal"))),
        rollout = listOf(
            RolloutClause(ServeValue("blue"), 5000),
            RolloutClause(ServeValue("green"), 5000)
        ),
        fallbackServe = ServeValue.OFF,
        salt = "shared-salt"
    )

    private fun employeeRule() = Rule(
        id = "employee_rule",
        name = "员工",
        conditions = listOf(Condition("tier", Operator.EQ, JsonString("employee"))),
        serve = ServeValue.ON
    )

    @Test
    fun `reordering rules does not silently move rollout buckets`() {
        val store = tempStore()
        val project = store.testProject()
        store.publishSimple(
            project.id, "f",
            rules = listOf(employeeRule(), rolloutRule()),
            note = "v1"
        )

        val contexts = (0 until 500).map { i ->
            json("""{"user":{"id":"u-$i"},"tier":"normal"}""")
        }
        val before = contexts.associateWith { store.evalOnce(project.id, "f", it).result.name }
        val beforeBuckets = contexts.associateWith {
            val step = store.evalOnce(project.id, "f", it).trace["steps"].asArray!!.items
                .first { s -> s.asObject!!.get("ruleId").asString == "rollout_rule" }
            step.asObject!!.get("rollout").asObject!!.get("bucketBp").asNumber!!.long
        }

        // Publish a new version where rules are reordered but the rollout clause
        // (id + salt + weights) is byte-for-byte unchanged.
        store.saveDraft(
            projectId = project.id,
            key = "f",
            name = "f",
            description = "",
            rules = listOf(rolloutRule(), employeeRule()),
            prerequisites = emptyList(),
            defaultValue = ServeValue.OFF,
            stableIdField = "user.id",
            publish = true,
            note = "v2 reordered"
        )

        contexts.forEach { ctx ->
            val after = store.evalOnce(project.id, "f", ctx).result.name
            assertEquals(before[ctx], after, "bucket changed after reorder for $ctx")
            val afterTrace = store.evalOnce(project.id, "f", ctx).trace["steps"].asArray!!.items
            val afterBucket = afterTrace
                .first { s -> s.asObject!!.get("ruleId").asString == "rollout_rule" }
                .asObject!!.get("rollout").asObject!!.get("bucketBp").asNumber!!.long
            assertEquals(beforeBuckets[ctx], afterBucket)
        }
    }

    @Test
    fun `trace step order follows rule order`() {
        val store = tempStore()
        val project = store.testProject()
        store.publishSimple(
            project.id, "f",
            rules = listOf(employeeRule(), rolloutRule()),
            note = "v1"
        )
        val ctx = json("""{"user":{"id":"u"},"tier":"normal"}""")
        val orderV1 = store.evalOnce(project.id, "f", ctx)
            .trace["steps"].asArray!!.items
            .mapNotNull { it.asObject!!.get("ruleId")?.asString }
        assertEquals(listOf("employee_rule", "rollout_rule"), orderV1)

        store.saveDraft(
            projectId = project.id,
            key = "f",
            name = "f",
            description = "",
            rules = listOf(rolloutRule(), employeeRule()),
            prerequisites = emptyList(),
            defaultValue = ServeValue.OFF,
            stableIdField = "user.id",
            publish = true,
            note = "v2"
        )
        val orderV2 = store.evalOnce(project.id, "f", ctx)
            .trace["steps"].asArray!!.items
            .mapNotNull { it.asObject!!.get("ruleId")?.asString }
        assertEquals(listOf("rollout_rule"), orderV2)
    }
}
