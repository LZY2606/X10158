package tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@Serializable
data class Condition(
    val field: String,
    val op: String,
    val value: JsonElement = JsonNull,
)

@Serializable
data class WeightedVariation(
    val value: JsonElement,
    val weight: Double,
)

@Serializable
data class Rule(
    val id: String,
    val name: String = "",
    val conditions: List<Condition> = emptyList(),
    val serve: JsonElement? = null,
    val rollout: List<WeightedVariation>? = null,
)

@Serializable
data class Prereq(
    val flagKey: String,
    val value: JsonElement,
)

@Serializable
data class FlagDoc(
    val key: String,
    val salt: String,
    val defaultValue: JsonElement,
    val rules: List<Rule> = emptyList(),
    val prerequisites: List<Prereq> = emptyList(),
)

@Serializable
data class FlagVersion(
    val versionId: String,
    val version: Int,
    val createdAt: Long,
    val doc: FlagDoc,
)

@Serializable
data class Flag(
    val key: String,
    val draft: FlagDoc,
    val versions: List<FlagVersion> = emptyList(),
)

@Serializable
data class SavedContext(
    val id: String,
    val name: String,
    val attributes: JsonObject,
)

@Serializable
data class FieldRead(
    val field: String,
    val status: String,
    val value: JsonElement? = null,
    val digest: String? = null,
    val sensitive: Boolean = false,
)

@Serializable
data class ConditionTrace(
    val condition: Condition,
    val read: FieldRead,
    val passed: Boolean,
    val note: String = "",
)

@Serializable
data class BucketTrace(
    val algorithm: String,
    val input: String,
    val hash: Long,
    val bucketPercent: Double,
    val chosenIndex: Int,
    val ranges: List<String>,
)

@Serializable
data class RuleTrace(
    val ruleId: String,
    val ruleName: String,
    val matched: Boolean,
    val conditions: List<ConditionTrace> = emptyList(),
    val served: JsonElement? = null,
    val bucket: BucketTrace? = null,
    val note: String = "",
)

@Serializable
data class PrereqTrace(
    val flagKey: String,
    val expected: JsonElement,
    val actual: JsonElement,
    val met: Boolean,
    val child: TraceNode,
)

@Serializable
data class TraceStep(
    val type: String,
    val message: String = "",
    val prereq: PrereqTrace? = null,
    val rule: RuleTrace? = null,
)

@Serializable
data class TraceNode(
    val flagKey: String,
    val versionId: String,
    val steps: List<TraceStep> = emptyList(),
    val result: JsonElement = JsonNull,
    val reason: String = "",
)

@Serializable
data class EvaluationRecord(
    val id: String,
    val flagKey: String,
    val versionId: String,
    val context: JsonObject,
    val result: JsonElement,
    val trace: TraceNode,
    val createdAt: Long,
)

@Serializable
data class ProjectSettings(
    val projectId: String,
    val secret: String,
    val sensitiveFields: Set<String> = emptySet(),
)

@Serializable
data class StoreData(
    val project: ProjectSettings,
    val flags: Map<String, Flag> = emptyMap(),
    val contexts: List<SavedContext> = emptyList(),
    val evaluations: List<EvaluationRecord> = emptyList(),
)

@Serializable
data class ExportBundle(
    val format: String = "feature-flag-tracer/v1",
    val project: ProjectSettings,
    val flags: List<Flag>,
    val contexts: List<SavedContext>,
)
