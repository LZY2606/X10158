package fst

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DependSnapshotTest {
    private fun simpleVersion(onVariants: List<String> = listOf("a"), default: Serve = Serve("off")): Triple<String, List<Rule>, Serve> =
        Triple("", emptyList<Rule>(), default)

    private fun makeFlag(store: Store, key: String, prereq: List<Prerequisite> = emptyList(), default: Serve = Serve("off")) {
        store.createFlag(key, key, "id", emptyList())
        store.publishVersion(key, listOf("a", "b"), prereq,
            listOf(Rule("r$key", "always", listOf(Condition("c$key", "on", "eq", true)), Serve("variant", "a"), null)),
            default, "salt-$key", "")
    }

    @Test
    fun `prerequisite gate controls downstream value`() {
        val store = Store(java.nio.file.Files.createTempDirectory("d"))
        makeFlag(store, "base")
        makeFlag(store, "child", listOf(Prerequisite("base", "a")))
        val snap = Engine.snapshot(store.project)
        val off = Engine.evaluate(snap, "child", mapOf("id" to "1"))
        assertEquals("off", off.served.display())
        assertEquals("prerequisite_failed", off.reason)
        val on = Engine.evaluate(snap, "child", mapOf("id" to "1", "on" to true))
        // base has on=true => a, child rule also fires
        assertEquals("a", on.served.display())
    }

    @Test
    fun `dependency cycle is rejected with path`() {
        val store = Store(java.nio.file.Files.createTempDirectory("d"))
        makeFlag(store, "a", listOf(Prerequisite("c", "a")))
        makeFlag(store, "b", listOf(Prerequisite("a", "a")))
        val ex = assertThrows(CycleException::class.java) {
            makeFlag(store, "c", listOf(Prerequisite("b", "a")))
        }
        assertTrue(ex.path.containsAll(listOf("a", "b", "c")), "path=${ex.path}")
        assertTrue(ex.path.first() == ex.path.last())
    }

    @Test
    fun `batch uses one snapshot even when a version is published mid-batch`() {
        val store = Store(java.nio.file.Files.createTempDirectory("d"))
        store.createFlag("f", "f", "id", emptyList())
        store.publishVersion("f", listOf("a"), emptyList(), emptyList(), Serve("variant", "a"), "S", "v1")
        val ctxs = (0 until 50).map { "ctx$it" to mapOf("id" to "$it") }
        val batch = EvalService.batch(store, listOf("f"), ctxs)
        // simulate concurrent publish: all batch rows must still be version 1 => "a"
        store.publishVersion("f", listOf("a", "b"), emptyList(), emptyList(), Serve("variant", "b"), "S", "v2 published")
        assertTrue(batch.results.all { it["version"] == 1 })
        assertTrue(batch.results.all { it["result"] == "a" })

        // A second batch now sees the new version uniformly.
        val batch2 = EvalService.batch(store, listOf("f"), ctxs.take(3))
        assertTrue(batch2.results.all { it["version"] == 2 })
        assertTrue(batch2.results.all { it["result"] == "b" })
    }

    @Test
    fun `snapshot can pin explicit historical versions`() {
        val store = Store(java.nio.file.Files.createTempDirectory("d"))
        store.createFlag("f", "f", "id", emptyList())
        store.publishVersion("f", listOf("a"), emptyList(), emptyList(), Serve("variant", "a"), "S", "v1")
        store.publishVersion("f", listOf("a", "b"), emptyList(), emptyList(), Serve("variant", "b"), "S", "v2")
        val res = EvalService.batch(store, listOf("f"), listOf("x" to mapOf("id" to "1")), mapOf("f" to 1))
        assertEquals("a", res.results.single()["result"])
    }
}
