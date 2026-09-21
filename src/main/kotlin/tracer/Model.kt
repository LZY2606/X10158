package tracer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
enum class Op { EQ, NEQ, LT, LTE, GT, GTE, CONTAINS, STARTS_WITH, ENDS_WITH, IN }

@Serializable
data class Condition(val field: String, val op: Op, val value: JsonElement)

@Serializable
data class Rollout(
    val enabled: Boolean = false,
    val percentage: Int = 100,
    val identityField: String = "id",
    val salt: String = "",
)

@Serializable
data class Clause(
    val id: String,
    val conditions: List<Condition> = emptyList(),
    val rollout: Rollout = Rollout(),
    val value: JsonElement,
)

@Serializable
data class Rule(val id: String, val clauses: List<Clause>)

@Serializable
data class FlagVersion(
    val version: Int,
    val defaultValue: JsonElement,
    val rules: List<Rule>,
    val prerequisites: List<String> = emptyList(),
    val createdAt: Long,
)

@Serializable
data class Flag(
    val key: String,
    val sensitiveFields: List<String> = emptyList(),
    val versions: List<FlagVersion> = emptyList(),
) {
    val currentVersion: FlagVersion? get() = versions.lastOrNull()
}

@Serializable
data class SavedContext(
    val id: String,
    val name: String,
    val context: JsonObject,
    val createdAt: Long,
)

@Serializable
data class EvalRecord(
    val id: String,
    val flagKey: String,
    val version: Int,
    val context: JsonObject,
    val result: JsonElement,
    val trace: JsonObject,
    val createdAt: Long,
)

@Serializable
data class Store(
    val flags: MutableMap<String, Flag> = mutableMapOf(),
    val contexts: MutableMap<String, SavedContext> = mutableMapOf(),
    val records: MutableMap<String, EvalRecord> = mutableMapOf(),
)

@Serializable
data class ExportBundle(
    val format: String = "flag-tracer-export",
    val formatVersion: Int = 1,
    val exportedAt: Long,
    val flags: List<Flag>,
    val contexts: List<SavedContext>,
)

fun Clause.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("conditions", kotlinx.serialization.json.JsonArray(conditions.map { it.toJson() }))
    put("rollout", rollout.toJson())
    put("value", value)
}

fun Condition.toJson(): JsonObject = buildJsonObject {
    put("field", field)
    put("op", op.name)
    put("value", value)
}

fun Rollout.toJson(): JsonObject = buildJsonObject {
    put("enabled", enabled)
    put("percentage", percentage)
    put("identityField", identityField)
    put("salt", salt)
}

fun Rule.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("clauses", kotlinx.serialization.json.JsonArray(clauses.map { it.toJson() }))
}

fun FlagVersion.toJson(): JsonObject = buildJsonObject {
    put("version", version)
    put("defaultValue", defaultValue)
    put("rules", kotlinx.serialization.json.JsonArray(rules.map { it.toJson() }))
    put("prerequisites", kotlinx.serialization.json.JsonArray(prerequisites.map { jsonStr(it) }))
    put("createdAt", createdAt)
}

fun Flag.toJson(): JsonObject = buildJsonObject {
    put("key", key)
    put("sensitiveFields", kotlinx.serialization.json.JsonArray(sensitiveFields.map { jsonStr(it) }))
    put("currentVersion", currentVersion?.version?.let { jsonNum(it) } ?: kotlinx.serialization.json.JsonNull)
    put("versions", kotlinx.serialization.json.JsonArray(versions.map { it.toJson() }))
}

fun FlagVersion.summaryJson(): JsonObject = buildJsonObject {
    put("version", version)
    put("defaultValue", defaultValue)
    put("rules", kotlinx.serialization.json.JsonArray(rules.map { it.toJson() }))
    put("prerequisites", kotlinx.serialization.json.JsonArray(prerequisites.map { jsonStr(it) }))
    put("createdAt", createdAt)
}
