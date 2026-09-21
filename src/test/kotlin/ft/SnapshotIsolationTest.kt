package ft

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotIsolationTest {
    private fun versionedRule(value: String) = Rule(
        "r", value,
        listOf(Condition("ready", Operator.EQ, JsonBoolean(true))),
        serve = ServeValue(value)
    )

    @Test
    fun `one batch never mixes versions when a publish happens concurrently`() {
        val store = tempStore()
        val project = store.testProject()
        store.publishSimple(
            project.id, "f",
            rules = listOf(versionedRule("v1")),
            note = "v1"
        )
        val executor = Executors.newFixedThreadPool(4)
        val gate = CountDownLatch(1)
        val snapshotTaken = CountDownLatch(1)

        // A batch large enough that the publisher is scheduled mid-run:
        // the store takes the snapshot BEFORE any evaluation occurs, so all
        // results must come from either v1 (lock semantics serialize anyway),
        // but never a mix. We assert homogeneity per batch.
        val batchResults = AtomicReference<List<String>>()
        val batchWorker = executor.submit {
            val requests = (0 until 500).map { i ->
                BatchRequest(
                    flagKey = "f",
                    contextName = "c$i",
                    context = json("""{"ready":true}""")
                )
            }
            gate.await()
            val snap = store.snapshot(project.id)
            snapshotTaken.countDown()
            val projectDef = store.getProject(project.id)
            val resolver = FlagResolver { pid, key -> snap.flags[pid to key] }
            val evaluator = Evaluator(projectDef, resolver)
            batchResults.set(requests.map { req ->
                val definition = snap.flags[project.id to "f"]!!
                evaluator.evaluate("f", definition, req.context).result.name
            })
        }

        val publisher = executor.submit {
            gate.await()
            snapshotTaken.await()
            // Publish repeatedly during evaluation; snapshot is immutable.
            repeat(20) { v ->
                store.saveDraft(
                    projectId = project.id,
                    key = "f",
                    name = "f",
                    description = "",
                    rules = listOf(versionedRule("v2-$v")),
                    prerequisites = emptyList(),
                    defaultValue = ServeValue.OFF,
                    stableIdField = "user.id",
                    publish = true,
                    note = "concurrent $v"
                )
            }
        }

        gate.countDown()
        batchWorker.get()
        publisher.get()
        executor.shutdown()

        val results = batchResults.get()
        assertEquals(500, results.size)
        assertEquals(1, results.toSet().size, "batch mixed versions: ${results.toSet()}")
        assertTrue(results.all { it == "v1" || it.startsWith("v2-") })
    }

    @Test
    fun `batch against pinned version stays on that version after later publishes`() {
        val store = tempStore()
        val project = store.testProject()
        store.publishSimple(
            project.id, "f", listOf(versionedRule("v1")), note = "v1"
        )
        store.saveDraft(
            projectId = project.id,
            key = "f",
            name = "f",
            description = "",
            rules = listOf(versionedRule("v2")),
            prerequisites = emptyList(),
            defaultValue = ServeValue.OFF,
            stableIdField = "user.id",
            publish = true,
            note = "v2"
        )

        val pinnedV1 = store.evaluateBatch(
            project.id,
            listOf(BatchRequest("f", "c", json("""{"ready":true}"""))),
            pinnedVersion = 1,
            save = false
        ).single()
        assertEquals("v1", pinnedV1.result.name)
        assertEquals(1, pinnedV1.version)
    }
}
