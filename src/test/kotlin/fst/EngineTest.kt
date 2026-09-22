package fst

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EngineTest {
    private fun store(tmp: java.nio.file.Path) = Store(tmp)

    private fun publishBasic(store: Store, key: String = "pay"): FlagVersion {
        store.createFlag(key, "支付", "id", listOf("ssn"))
        return store.publishVersion(
            key = key,
            onVariants = listOf("on", "gray"),
            prerequisites = emptyList(),
            rules = listOf(
                Rule("r1", "白名单", listOf(Condition("c1", "vip", "eq", true)), Serve("variant", "on"), null),
                Rule("r2", "年龄条件+分流", listOf(Condition("c2", "age", "ge", 18)),
                    null, listOf(Distribution("gray", 50), Distribution("on", 50))),
            ),
            defaultServe = Serve("off"),
            salt = "salty",
            note = "v1",
        )
    }

    @Test
    fun `fixed rule hit and miss trace`() {
        val dir = java.nio.file.Files.createTempDirectory("fst")
        val store = store(dir)
        publishBasic(store)
        val snap = Engine.snapshot(store.project)
        val hit = Engine.evaluate(snap, "pay", mapOf("id" to "u1", "vip" to true))
        assertEquals("on", hit.served.display())
        assertEquals("rule_match", hit.reason)
        val skip = Engine.evaluate(snap, "pay", mapOf("id" to "u1", "vip" to false, "age" to 10))
        assertEquals("off", skip.served.display())
        val kinds = flatten(skip.root).map { it.kind }
        assertTrue(kinds.contains("rules-exhausted"))
    }

    @Test
    fun `missing field differs from explicit null`() {
        val dir = java.nio.file.Files.createTempDirectory("fst")
        val store = store(dir)
        store.createFlag("f", "f", "id", emptyList())
        store.publishVersion("f", listOf("a"), emptyList(),
            listOf(Rule("r", "n", listOf(Condition("c", "x", "eq", null)), Serve("variant", "a"), null)),
            Serve("off"), "s", "")
        val snap = Engine.snapshot(store.project)
        val missing = Engine.evaluate(snap, "f", mapOf("id" to "1"))
        val explicitNull = Engine.evaluate(snap, "f", mapOf("id" to "1", "x" to null))
        assertEquals("off", missing.served.display())
        assertEquals("a", explicitNull.served.display())
        val states = flatten(explicitNull.root).filter { it.kind == "field-read" }.map { it.detail["state"] }
        assertTrue(states.contains("null"))
        val missingStates = flatten(missing.root).filter { it.kind == "field-read" }.map { it.detail["state"] }
        assertTrue(missingStates.contains("missing"))
    }

    @Test
    fun `string and number never implicitly convert`() {
        val dir = java.nio.file.Files.createTempDirectory("fst")
        val store = store(dir)
        store.createFlag("f", "f", "id", emptyList())
        store.publishVersion("f", listOf("a"), emptyList(),
            listOf(Rule("r", "n", listOf(Condition("c", "x", "eq", 18)), Serve("variant", "a"), null)),
            Serve("off"), "s", "")
        val snap = Engine.snapshot(store.project)
        val str = Engine.evaluate(snap, "f", mapOf("id" to "1", "x" to "18"))
        assertEquals("off", str.served.display())
        assertTrue(flatten(str.root).any { it.kind == "type-mismatch" })
        val num = Engine.evaluate(snap, "f", mapOf("id" to "1", "x" to 18))
        assertEquals("a", num.served.display())
        val dbl = Engine.evaluate(snap, "f", mapOf("id" to "1", "x" to 18.0))
        assertEquals("a", dbl.served.display(), "numbers compare numerically (18 == 18.0)")
    }

    @Test
    fun `nested missing through object`() {
        val dir = java.nio.file.Files.createTempDirectory("fst")
        val store = store(dir)
        store.createFlag("f", "f", "id", emptyList())
        store.publishVersion("f", listOf("a"), emptyList(),
            listOf(Rule("r", "n", listOf(Condition("c", "user.tier", "eq", "gold")), Serve("variant", "a"), null)),
            Serve("off"), "s", "")
        val snap = Engine.snapshot(store.project)
        val out = Engine.evaluate(snap, "f", mapOf("id" to "1", "user" to mapOf("other" to 1)))
        assertEquals("off", out.served.display())
        assertTrue(flatten(out.root).any { n -> n.kind == "field-read" && n.detail["state"] == "missing" })
    }

    private fun flatten(n: TNode): List<TNode> = listOf(n) + n.children.flatMap { flatten(it) }
}
