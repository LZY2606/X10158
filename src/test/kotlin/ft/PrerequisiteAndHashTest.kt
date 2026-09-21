package ft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrerequisiteAndHashTest {
    private fun onRule() = Rule("r", "always on", emptyList(), serve = ServeValue.ON)

    @Test
    fun `failing prerequisite applies gate serve and stops rule evaluation`() {
        val store = tempStore()
        val project = store.testProject()
        store.publishSimple(
            project.id, "base",
            rules = listOf(Rule("r", "off", emptyList(), serve = ServeValue.OFF)),
            note = "v1"
        )
        store.publishSimple(
            project.id, "dependent",
            rules = listOf(onRule()),
            prerequisites = listOf(
                Prerequisite("base", listOf(ServeValue("blue")), ServeValue("blocked"))
            ),
            note = "v1"
        )
        val record = store.evalOnce(project.id, "dependent", json("{}"))
        assertEquals("blocked", record.result.name)
        val prereq = record.trace["steps"].asArray!!.items.first().asObject!!
        assertEquals("prerequisite", prereq["type"].asString)
        assertEquals("off", prereq["observed"].asString)
        assertEquals(false, prereq["passed"].asBoolean)
        assertEquals("blocked", prereq["served"].asString)
        // No rule steps after a failed prerequisite.
        assertTrue(
            record.trace["steps"].asArray!!.items.none {
                it.asObject!!.get("type").asString == "rule"
            }
        )
    }

    @Test
    fun `missing prerequisite flag gates without throwing`() {
        val store = tempStore()
        val project = store.testProject()
        store.publishSimple(
            project.id, "dependent",
            rules = listOf(onRule()),
            prerequisites = listOf(
                Prerequisite("ghost", listOf(ServeValue.ON), ServeValue.OFF)
            )
        )
        assertEquals("off", store.evalOnce(project.id, "dependent", json("{}")).result.name)
    }

    @Test
    fun `murmur3 matches the published reference vector for empty key`() {
        // MurmurHash3 x86 32-bit, seed 0: hash of empty input = 0.
        assertEquals(0, Murmur3.hash32(""))
    }

    @Test
    fun `bucketing is an unsigned value below 10000`() {
        repeat(1000) { i ->
            val bucket = Murmur3.bucket10k("flag.salt.user-$i")
            assertTrue(bucket in 0..9999)
        }
    }

    @Test
    fun `nested prerequisite field reads surface in the top-level trace`() {
        val store = tempStore()
        val project = store.testProject()
        store.publishSimple(
            project.id, "a",
            rules = listOf(
                Rule("r", "needs region",
                    listOf(Condition("region", Operator.EQ, JsonString("eu"))),
                    serve = ServeValue.ON)
            )
        )
        store.publishSimple(
            project.id, "b",
            rules = listOf(Rule("r", "on", emptyList(), serve = ServeValue.ON)),
            prerequisites = listOf(Prerequisite("a", listOf(ServeValue.ON)))
        )
        store.publishSimple(
            project.id, "c",
            rules = listOf(Rule("r", "on", emptyList(), serve = ServeValue("feature"))),
            prerequisites = listOf(Prerequisite("b", listOf(ServeValue.ON)))
        )
        val record = store.evalOnce(project.id, "c", json("""{"region":"eu"}"""))
        assertEquals("feature", record.result.name)
        val reads = record.trace["fieldReads"].asObject!!
        assertTrue(reads.members.containsKey("region"))
        assertEquals("present", reads.get("region").asObject!!.get("state").asString)
    }
}
