package ft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DependencyCycleTest {
    private fun basicRule() = Rule(
        "r", "r", emptyList(), serve = ServeValue.ON
    )

    @Test
    fun `direct self dependency is rejected with path`() {
        val edges = mapOf("a" to listOf("a"))
        val cycle = DependencyGraph.findCycle(edges)
        assertEquals(listOf("a", "a"), cycle)
    }

    @Test
    fun `three flag cycle is rejected and names the full path`() {
        val edges = mapOf(
            "a" to listOf("b"),
            "b" to listOf("c"),
            "c" to listOf("a")
        )
        val error = assertFailsWith<DependencyGraph.CycleError> {
            DependencyGraph.assertAcyclic(edges)
        }
        assertEquals(listOf("a", "b", "c", "a"), error.path)
        assertTrue(error.message!!.contains("a -> b -> c -> a"))
    }

    @Test
    fun `publishing a flag that closes a cycle throws`() {
        val store = tempStore()
        val project = store.testProject()
        // b exists first with no dependency.
        store.publishSimple(project.id, "b", listOf(basicRule()))
        // a depends on b — still acyclic.
        store.publishSimple(
            project.id, "a", listOf(basicRule()),
            prerequisites = listOf(Prerequisite("b", listOf(ServeValue.ON)))
        )
        // Now editing b to depend on a closes a -> b -> a; the publish
        // (and therefore the cycle) must be rejected.
        val error = assertFailsWith<DependencyGraph.CycleError> {
            store.saveDraft(
                projectId = project.id,
                key = "b",
                name = "b",
                description = "",
                rules = listOf(basicRule()),
                prerequisites = listOf(Prerequisite("a", listOf(ServeValue.ON))),
                defaultValue = ServeValue.OFF,
                stableIdField = "user.id",
                publish = true,
                note = "cycle"
            )
        }
        assertEquals(listOf("a", "b", "a"), error.path)
    }

    @Test
    fun `acyclic graph is accepted`() {
        DependencyGraph.assertAcyclic(
            mapOf(
                "a" to listOf("b"),
                "b" to listOf("c"),
                "c" to emptyList()
            )
        )
    }
}
