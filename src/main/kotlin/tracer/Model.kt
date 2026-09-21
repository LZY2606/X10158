package tracer

import com.fasterxml.jackson.databind.JsonNode

data class Clause(
    val attribute: String,
    val op: String,
    val values: List<JsonNode> = emptyList(),
    val negate: Boolean = false,
)

data class WeightedVariation(
    val variation: Int,
    val weight: Double,
)

data class Outcome(
    val kind: String = "variation", // "variation" | "rollout"
    val variation: Int? = null,
    val buckets: List<WeightedVariation> = emptyList(),
    val stickyAttribute: String = "key",
)

data class Rule(
    val id: String,
    val description: String = "",
    val clauses: List<Clause> = emptyList(),
    val outcome: Outcome = Outcome(variation = 0),
)

data class Prerequisite(
    val flagKey: String,
    val variation: Int,
)

data class FlagVersion(
    val version: Int,
    val enabled: Boolean = true,
    val prerequisites: List<Prerequisite> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val fallthrough: Outcome = Outcome(variation = 0),
    val offVariation: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

data class Flag(
    val key: String,
    val salt: String,
    val variations: List<JsonNode>,
    val versions: List<FlagVersion> = emptyList(),
)

data class TraceNode(
    val kind: String = "",
    val message: String = "",
    val detail: Map<String, JsonNode> = emptyMap(),
    val children: MutableList<TraceNode> = mutableListOf(),
)

data class EvaluationRecord(
    val id: String = "",
    val flagKey: String = "",
    val version: Int = 0,
    val context: JsonNode = Json.mapper.nullNode(),
    val variation: Int = 0,
    val value: JsonNode = Json.mapper.nullNode(),
    val trace: TraceNode = TraceNode(),
    val timestamp: Long = 0L,
)

data class State(
    val projectSecret: String = "",
    val sensitiveFields: MutableSet<String> = mutableSetOf(),
    val flags: MutableMap<String, Flag> = mutableMapOf(),
    val contexts: MutableMap<String, JsonNode> = mutableMapOf(),
    val evaluations: MutableList<EvaluationRecord> = mutableListOf(),
)

data class Snapshot(
    val projectSecret: String,
    val sensitiveFields: Set<String>,
    val flags: Map<String, Flag>,
)
