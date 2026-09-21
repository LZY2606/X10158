package ft

data class ValidationResult(val valid: Boolean, val errors: List<String>, val cyclePath: List<String>? = null)

object Validator {

    fun validateSingle(fv: FlagVersion): List<String> {
        val errors = ArrayList<String>()
        if (fv.key.isBlank()) errors += "Flag key is required"
        if (fv.salt.isBlank()) errors += "Flag salt is required"
        if (fv.stableIdField.isBlank()) errors += "Stable id field is required"
        val ruleIds = HashSet<String>()
        for ((idx, rule) in fv.rules.withIndex()) {
            if (rule.id.isBlank()) errors += "Rule #$idx has no id"
            if (!ruleIds.add(rule.id)) errors += "Duplicate rule id '${rule.id}'"
            if (rule.isRollout) {
                val slices = rule.rollout!!
                val total = slices.sumOf { it.weightBps }
                if (total > 10000) errors += "Rule '${rule.name}' rollout weights sum to ${total} bps (> 10000)"
                if (slices.any { it.weightBps < 0 }) errors += "Rule '${rule.name}' has a negative slice"
                val names = slices.map { it.outcome.label }
                if (names.toSet().size != names.size)
                    errors += "Rule '${rule.name}' rollout has duplicate outcomes"
            } else if (rule.outcome == null) {
                errors += "Rule '${rule.name}' has neither an outcome nor a rollout"
            }
            for (c in rule.conditions) {
                if (c.field.isBlank()) errors += "Rule '${rule.name}' has an empty condition field"
                if (c.op !in KNOWN_OPS) errors += "Rule '${rule.name}' uses unknown operator '${c.op}'"
            }
        }
        return errors
    }

    val KNOWN_OPS = setOf(
        "eq", "ne", "gt", "ge", "lt", "le",
        "starts_with", "ends_with", "contains", "in", "present", "absent"
    )

    /**
     * Detects cycles in the prerequisite graph over the given candidate set.
     * Returns the first cycle path found (closed, first node repeated).
     */
    fun findCycle(candidates: Map<String, FlagVersion>): List<String>? {
        val graph: Map<String, List<String>> =
            candidates.mapValues { it.value.prerequisites.map { p -> p.flagKey } }

        val visited = HashSet<String>()
        val onStack = LinkedHashSet<String>()

        fun dfs(node: String): List<String>? {
            if (node in onStack) {
                val start = onStack.toList().indexOf(node)
                return onStack.toList().subList(start, onStack.size) + node
            }
            if (node in visited) return null
            onStack += node
            for (next in graph[node] ?: emptyList()) {
                if (next !in candidates) {
                    // Unknown prerequisite is reported separately, not a cycle.
                    continue
                }
                dfs(next)?.let { return it }
            }
            onStack -= node
            visited += node
            return null
        }

        for (key in candidates.keys.sorted()) {
            dfs(key)?.let { return it }
        }
        return null
    }
}
