package tracer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

val json = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun JsonObject.getOrNull(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

fun JsonObject.getStringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.getIntOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

fun JsonObject.getBooleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.getStringListOrEmpty(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

fun jsonStr(value: String): JsonElement = JsonPrimitive(value)
fun jsonNum(value: Number): JsonElement = JsonPrimitive(value.toDouble())
fun jsonBool(value: Boolean): JsonElement = JsonPrimitive(value)

fun parseJsonObject(text: String, what: String = "内容"): JsonObject {
    val el = try {
        Json.parseToJsonElement(text)
    } catch (e: Exception) {
        throw ApiException(400, "$what 不是合法 JSON: ${e.message}")
    }
    return el as? JsonObject ?: throw ApiException(400, "$what 必须是 JSON 对象")
}

fun canonicalJson(element: JsonElement): String = when (element) {
    is JsonObject -> element.entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (k, v) ->
            JsonPrimitive(k).toString() + ":" + canonicalJson(v)
        }
    is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
    is JsonNull -> "null"
    is JsonPrimitive -> {
        if (element.isString) {
            element.toString()
        } else {
            val d = element.doubleOrNull
            val l = element.longOrNull
            when {
                d == null -> element.content
                l != null && l.toDouble() == d -> l.toString()
                else -> {
                    val s = d.toString()
                    if (s.endsWith(".0")) s.dropLast(2) else s
                }
            }
        }
    }
}

fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

class ApiException(val status: Int, message: String) : RuntimeException(message)
