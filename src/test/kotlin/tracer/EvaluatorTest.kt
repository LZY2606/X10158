package tracer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue

private fun ctx(vararg pairs: Pair<String, Any?>): JsonObject = kotlinx.serialization.json.buildJsonObject {
    for ((k, v) in pairs) {
        when (v) {
            null -> put(k, kotlinx.serialization.json.JsonNull)
            is String -> put(k, v)
            is Int -> put(k, v)
            is Double -> put(k, v)
            is Boolean -> put(k, v)
            else -> error("unsupported")
        }
    }
}

private fun tempService(name: String): FlagService {
    val dir = File("build/test-data/$name")
    dir.deleteRecursively()
    dir.mkdirs()
    return FlagService(File(dir, "flags.json"), "proj-$name")
}

private fun FlagService.createAndPublish(
    key: String,
    body: JsonObject,
    sensitive: List<String> = emptyList(),
) {
    createFlag(key, sensitive)
    publishVersion(key, body)
}

class EvaluatorTest {

    @Test
    fun `missing field and null field traced separately`() {
        val svc = tempService("missing-null")
        svc.createAndPublish(
            "f1",
            parseJsonObject(
                """{"defaultValue":"off","rules":[{"clauses":[{"conditions":[{"field":"age","op":"GTE","value":18}],"value":"on"}]}]}"""
            ),
        )
        val missing = svc.evaluate("f1", ctx(), false)
        assertEquals(JsonPrimitive("off"), missing.result)
        val condNode = (((missing.trace["rules"] as kotlinx.serialization.json.JsonArray)[0]
            .jsonObject["clauses"] as kotlinx.serialization.json.JsonArray)[0]
            .jsonObject["conditions"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals("missing", condNode["status"]!!.jsonPrimitive.content)
        assertEquals(false, condNode["matched"]!!.jsonPrimitive.booleanOrNull)

        val nulled = svc.evaluate("f1", ctx("age" to null), false)
        val condNode2 = (((nulled.trace["rules"] as kotlinx.serialization.json.JsonArray)[0]
            .jsonObject["clauses"] as kotlinx.serialization.json.JsonArray)[0]
            .jsonObject["conditions"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals("null", condNode2["status"]!!.jsonPrimitive.content)
        assertEquals(false, condNode2["matched"]!!.jsonPrimitive.booleanOrNull)
        assertNotEquals(condNode["status"], condNode2["status"])
    }

    private val JsonObject?.jsonObject: JsonObject get() = this as JsonObject
    private val kotlinx.serialization.json.JsonElement?.jsonObject: JsonObject get() = this as JsonObject

    @Test
    fun `no implicit string number conversion`() {
        val svc = tempService("types")
        svc.createAndPublish(
            "f2",
            parseJsonObject(
                """{"defaultValue":"off","rules":[{"clauses":[{"conditions":[{"field":"level","op":"GTE","value":10}],"value":"on"}]}]}"""
            ),
        )
        val asString = svc.evaluate("f2", ctx("level" to "15"), false)
        assertEquals(JsonPrimitive("off"), asString.result)
        val condNode = (((asString.trace["rules"] as kotlinx.serialization.json.JsonArray)[0]
            .jsonObject["clauses"] as kotlinx.serialization.json.JsonArray)[0]
            .jsonObject["conditions"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertTrue(condNode["reason"]!!.jsonPrimitive.content.contains("类型不匹配"))

        val asNumber = svc.evaluate("f2", ctx("level" to 15), false)
        assertEquals(JsonPrimitive("on"), asNumber.result)

        val svc2 = tempService("types2")
        svc2.createAndPublish(
            "f3",
            parseJsonObject(
                """{"defaultValue":"off","rules":[{"clauses":[{"conditions":[{"field":"name","op":"EQ","value":"42"}],"value":"on"}]}]}"""
            ),
        )
        val numVsStr = svc2.evaluate("f3", ctx("name" to 42), false)
        assertEquals(JsonPrimitive("off"), numVsStr.result)
        val strVsStr = svc2.evaluate("f3", ctx("name" to "42"), false)
        assertEquals(JsonPrimitive("on"), strVsStr.result)
    }

    @Test
    fun `rollout percentage endpoints`() {
        val svc = tempService("endpoints")
        svc.createAndPublish(
            "f4",
            parseJsonObject(
                """{"defaultValue":"off","rules":[{"clauses":[{"rollout":{"enabled":true,"percentage":0,"identityField":"id","salt":"s"},"value":"on"}]}]}"""
            ),
        )
        val zero = svc.evaluate("f4", ctx("id" to "user-1"), false)
        assertEquals(JsonPrimitive("off"), zero.result)

        svc.publishVersion(
            "f4",
            parseJsonObject(
                """{"defaultValue":"off","rules":[{"clauses":[{"rollout":{"enabled":true,"percentage":100,"identityField":"id","salt":"s"},"value":"on"}]}]}"""
            ),
        )
        val hundred = svc.evaluate("f4", ctx("id" to "user-1"), false)
        assertEquals(JsonPrimitive("on"), hundred.result)
    }

    @Test
    fun `reordering rules keeps bucket assignment`() {
        val svc = tempService("reorder")
        val clauseA = """{"id":"clause-a","conditions":[{"field":"plan","op":"EQ","value":"pro"}],"rollout":{"enabled":true,"percentage":50,"identityField":"id","salt":"abc"},"value":"A"}"""
        val clauseB = """{"id":"clause-b","rollout":{"enabled":true,"percentage":50,"identityField":"id","salt":"xyz"},"value":"B"}"""
        svc.createAndPublish(
            "f5",
            parseJsonObject("""{"defaultValue":"off","rules":[{"id":"r1","clauses":[$clauseA]},{"id":"r2","clauses":[$clauseB]}]}"""),
        )
        val context = ctx("id" to "user-9", "plan" to "free")
        val before = svc.evaluate("f5", context, false)
        svc.publishVersion(
            "f5",
            parseJsonObject("""{"defaultValue":"off","rules":[{"id":"r2","clauses":[$clauseB]},{"id":"r1","clauses":[$clauseA]}]}"""),
        )
        val after = svc.evaluate("f5", context, false)
        assertEquals(before.result, after.result)
        val bucketOf = { trace: JsonObject ->
            ((((trace["rules"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject)
                ["clauses"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject)
                .let { it["rollout"] as JsonObject }["bucket"]!!.jsonPrimitive.intOrNull
        }
        assertEquals(bucketOf(before.trace), bucketOf(after.trace))
    }

    @Test
    fun `prerequisite cycle rejected with path`() {
        val svc = tempService("cycle")
        svc.createAndPublish("a", parseJsonObject("""{"defaultValue":true}"""))
        svc.createAndPublish("b", parseJsonObject("""{"defaultValue":true,"prerequisites":["a"]}"""))
        val ex = assertThrows<ApiException> {
            svc.publishVersion("a", parseJsonObject("""{"defaultValue":true,"prerequisites":["b"]}"""))
        }
        assertTrue(ex.message!!.contains("a -> b -> a"), "cycle path should be reported, got: ${ex.message}")

        val self = assertThrows<ApiException> {
            svc.publishVersion("a", parseJsonObject("""{"defaultValue":true,"prerequisites":["a"]}"""))
        }
        assertTrue(self.message!!.contains("a"))
    }

    @Test
    fun `prerequisite failure blocks with trace`() {
        val svc = tempService("prereq")
        svc.createAndPublish("base", parseJsonObject("""{"defaultValue":false}"""))
        svc.createAndPublish("child", parseJsonObject("""{"defaultValue":"off","prerequisites":["base"],"rules":[{"clauses":[{"value":"on"}]}]}"""))
        val out = svc.evaluate("child", ctx(), false)
        assertEquals(JsonPrimitive(false), out.result)
        assertEquals("prerequisite_blocked", out.trace["outcome"]!!.jsonPrimitive.content)
        assertEquals("base", out.trace["blockedBy"]!!.jsonPrimitive.content)
        svc.publishVersion("base", parseJsonObject("""{"defaultValue":true}"""))
        val out2 = svc.evaluate("child", ctx(), false)
        assertEquals(JsonPrimitive("on"), out2.result)
    }

    @Test
    fun `batch evaluation uses single snapshot`() {
        val svc = tempService("snapshot")
        svc.createAndPublish("s1", parseJsonObject("""{"defaultValue":"v1"}"""))
        svc.createAndPublish("s2", parseJsonObject("""{"defaultValue":"v1"}"""))
        val result = svc.evaluateBatch(listOf("s1", "s2"), ctx())
        val results = result["results"] as JsonObject
        val v1 = (results["s1"] as JsonObject)["version"]!!.jsonPrimitive.intOrNull
        val v2 = (results["s2"] as JsonObject)["version"]!!.jsonPrimitive.intOrNull
        assertEquals(1, v1)
        assertEquals(1, v2)
        svc.publishVersion("s1", parseJsonObject("""{"defaultValue":"v2"}"""))
        val single = svc.evaluate("s1", ctx(), false)
        assertEquals(JsonPrimitive("v2"), single.result)
    }

    @Test
    fun `sensitive digest proves equality but isolated across projects`() {
        val svcA = tempService("projA")
        val svcB = tempService("projB")
        val body = parseJsonObject(
            """{"defaultValue":"off","rules":[{"clauses":[{"conditions":[{"field":"email","op":"EQ","value":"a@x.com"}],"value":"on"}]}]}"""
        )
        svcA.createAndPublish("sec", body, sensitive = listOf("email"))
        svcB.createAndPublish("sec", body, sensitive = listOf("email"))
        val traceA1 = svcA.evaluate("sec", ctx("email" to "a@x.com"), false)
        val traceA2 = svcA.evaluate("sec", ctx("email" to "a@x.com"), false)
        val traceB = svcB.evaluate("sec", ctx("email" to "a@x.com"), false)

        fun digestOf(trace: JsonObject): String {
            val cond = (((trace["rules"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject)
                ["clauses"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject
            val condNode = (cond["conditions"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject
            val actual = condNode["actual"] as JsonObject
            assertTrue(actual.containsKey("digest"), "sensitive value must be masked")
            return actual["digest"]!!.jsonPrimitive.content
        }
        assertEquals(digestOf(traceA1.trace), digestOf(traceA2.trace), "same input must give same digest")
        assertNotEquals(digestOf(traceA1.trace), digestOf(traceB.trace), "different projects must not correlate")
        assertEquals(JsonPrimitive("on"), traceA1.result)
        assertFalse(traceA1.trace.toString().contains("a@x.com"), "raw sensitive value must not leak into trace")
    }

    @Test
    fun `historical records replay old version trace`() {
        val svc = tempService("replay")
        svc.createAndPublish("h", parseJsonObject("""{"defaultValue":"old"}"""))
        val record = svc.evaluate("h", ctx(), record = true)
        assertEquals(1, record.version)
        assertEquals(JsonPrimitive("old"), record.result)
        svc.publishVersion("h", parseJsonObject("""{"defaultValue":"new"}"""))
        val fetched = svc.getRecord(record.id)
        assertEquals(1, fetched.version)
        assertEquals(JsonPrimitive("old"), fetched.result)
        assertEquals("old", fetched.trace["value"]!!.jsonPrimitive.content)
        val now = svc.evaluate("h", ctx(), false)
        assertEquals(JsonPrimitive("new"), now.result)
    }

    @Test
    fun `export import keeps buckets and trace order`() {
        val svc = tempService("export")
        svc.createAndPublish(
            "e1",
            parseJsonObject(
                """{"defaultValue":"off","rules":[{"id":"r1","clauses":[{"id":"c1","rollout":{"enabled":true,"percentage":60,"identityField":"id","salt":"pepper"},"value":"on"}]},{"id":"r2","clauses":[{"id":"c2","conditions":[{"field":"region","op":"EQ","value":"jp"}],"value":"jp-on"}]}]}"""
            ),
        )
        val saved = svc.saveContext("ctx1", ctx("id" to "u-7", "region" to "jp"))
        val bundle = svc.exportAll(null, null)

        val svc2 = tempService("export2")
        svc2.importBundle(bundle)
        val context = ctx("id" to "u-7", "region" to "jp")
        val t1 = svc.evaluate("e1", context, false)
        val t2 = svc2.evaluate("e1", context, false)
        assertEquals(t1.result, t2.result)
        assertEquals(t1.trace, t2.trace, "trace order and content must match after import")
        assertEquals(saved.context, svc2.listContexts().single().context)
    }

    @Test
    fun `compare versions reports changes`() {
        val svc = tempService("compare")
        svc.createAndPublish("cmp", parseJsonObject("""{"defaultValue":"off"}"""))
        svc.publishVersion("cmp", parseJsonObject("""{"defaultValue":"on"}"""))
        val saved = svc.saveContext("c1", ctx("id" to "u1"))
        val result = svc.compareVersions("cmp", 1, 2, listOf(saved.context))
        assertEquals(1, result["changedCount"]!!.jsonPrimitive.intOrNull)
    }
}
