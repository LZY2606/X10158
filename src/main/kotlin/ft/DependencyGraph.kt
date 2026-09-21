package ft

/**
 * Validates the prerequisite graph of one project. Cycles are rejected with
 * the exact path that closes the loop, e.g. a -> b -> c -> a.
 */
object DependencyGraph {
    data class CycleError(val path: List<String>) :
        RuntimeException("prerequisite cycle detected: ${path.joinToString(" -> ")}")

    /** Edge set: flag key -> prerequisite flag keys (resolved within the project). */
    fun assertAcyclic(edges: Map<String, List<String>>) {
        findCycle(edges)?.let { throw CycleError(it) }
    }

    fun findCycle(edges: Map<String, List<String>>): List<String>? {
        // 0 = unvisited, 1 = in progress, 2 = finished
        val state = HashMap<String, Int>()
        val stack = ArrayDeque<String>()

        fun dfs(node: String): List<String>? {
            state[node] = 1
            stack.addLast(node)
            for (next in edges[node].orEmpty()) {
                when (state[next] ?: 0) {
                    1 -> {
                        val start = stack.indexOf(next)
                        return stack.toList().subList(start, stack.size) + next
                    }
                    0 -> dfs(next)?.let { return it }
                }
            }
            stack.removeLast()
            state[node] = 2
            return null
        }

        for (node in edges.keys.sorted()) {
            if ((state[node] ?: 0) == 0) {
                dfs(node)?.let { return it }
            }
        }
        return null
    }
}
