package fftracer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class Project(
    val id: String,
    val name: String,
    val secret: String,
)

@Serializable
enum class Op { EQ, NEQ, GT, GTE, LT, LTE, IN, CONTAINS, STARTS_WITH, ENDS_WITH }

@Serializable
data class Condition(
    val attribute: String,
    val op: Op,
    val value: JsonElement = JsonNull,
    val values: List<JsonElement> = emptyList(),
)

@Serializable
data class Rollout(
    val percent: Double,
    val identityAttribute: String,
)

@Serializable
data class Rule(
    val id: String,
    val name: String = "",
    val conditions: List<Condition> = emptyList(),
    val rollout: Rollout? = null,
    val serve: JsonElement,
)

@Serializable
data class Prerequisite(
    val flagKey: String,
    val expect: JsonElement,
)

@Serializable
data class FlagVersion(
    val version: Int,
    val defaultValue: JsonElement,
    val rules: List<Rule> = emptyList(),
    val prerequisites: List<Prerequisite> = emptyList(),
    val createdAt: Long,
)

@Serializable
data class Flag(
    val key: String,
    val projectId: String,
    val salt: String,
    val currentVersion: Int,
    val versions: List<FlagVersion>,
) {
    val head: FlagVersion get() = versions.first { it.version == currentVersion }
    fun version(v: Int): FlagVersion? = versions.firstOrNull { it.version == v }
}

@Serializable
data class EvalContext(
    val id: String,
    val projectId: String,
    val attributes: Map<String, JsonElement>,
    val sensitiveAttributes: List<String> = emptyList(),
)

@Serializable
data class TraceStep(
    val kind: String,
    val summary: String,
    val detail: Map<String, JsonElement> = emptyMap(),
    val result: String? = null,
    val children: List<TraceStep> = emptyList(),
)

@Serializable
data class EvalResult(
    val value: JsonElement,
    val reason: String,
    val flagKey: String,
    val version: Int,
    val trace: List<TraceStep>,
)

@Serializable
data class EvalRecord(
    val id: String,
    val projectId: String,
    val flagKey: String,
    val version: Int,
    val contextId: String,
    val result: EvalResult,
    val createdAt: Long,
)

fun canonical(e: JsonElement): String = when (e) {
    is JsonNull -> "null"
    is JsonObject -> "{" + e.entries.sortedBy { it.key }
        .joinToString(",") { (k, v) -> "\"$k\":" + canonical(v) } + "}"
    is JsonArray -> "[" + e.joinToString(",") { canonical(it) } + "]"
    is JsonPrimitive -> when {
        e.isString -> "\"" + e.content + "\""
        else -> e.content
    }
    else -> e.toString()
}
