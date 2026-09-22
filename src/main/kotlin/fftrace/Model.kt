package fftrace

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Condition(
    val field: String,
    val op: String,
    val values: List<JsonElement> = emptyList()
)

@Serializable
data class Serve(
    val type: String = "fixed", // fixed | percentage
    val value: String = "on",
    val percentage: Double = 100.0,
    val elseValue: String = "off",
    val identityField: String = "key"
)

@Serializable
data class Rule(
    val id: String,
    val name: String = "",
    val conditions: List<Condition> = emptyList(),
    val serve: Serve = Serve()
)

@Serializable
data class Prerequisite(
    val flagKey: String,
    val requiredValue: String = "on"
)

@Serializable
data class FlagConfig(
    val rules: List<Rule> = emptyList(),
    val defaultValue: String = "off",
    val prerequisites: List<Prerequisite> = emptyList(),
    val salt: String = ""
)

@Serializable
data class FlagVersion(
    val version: Int,
    val config: FlagConfig,
    val createdAt: Long
)

@Serializable
data class Flag(
    val key: String,
    val description: String = "",
    val draft: FlagConfig = FlagConfig(),
    val versions: List<FlagVersion> = emptyList()
) {
    val latestVersion: FlagVersion? get() = versions.lastOrNull()
}

@Serializable
data class SavedContext(
    val id: String,
    val name: String = "",
    val attributes: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class EvaluationRecord(
    val id: String,
    val flagKey: String,
    val version: Int,
    val contextId: String? = null,
    val contextName: String = "",
    val context: Map<String, JsonElement>,
    val result: String,
    val trace: EvaluationTrace,
    val createdAt: Long
)

@Serializable
data class StoreData(
    val projectId: String = java.util.UUID.randomUUID().toString(),
    val projectSecret: String = java.util.UUID.randomUUID().toString(),
    val sensitiveFields: List<String> = emptyList(),
    val flags: Map<String, Flag> = emptyMap(),
    val contexts: Map<String, SavedContext> = emptyMap(),
    val evaluations: List<EvaluationRecord> = emptyList(),
    val nextEvalId: Long = 1
)

@Serializable
data class ExportData(
    val format: String = "fftrace-export-v1",
    val sensitiveFields: List<String> = emptyList(),
    val flags: List<Flag> = emptyList(),
    val contexts: List<SavedContext> = emptyList()
)
