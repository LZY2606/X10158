package tracer

import com.fasterxml.jackson.databind.JsonNode

data class Condition(
    val field: String,
    val op: String,
    val value: JsonNode,
)

data class Rollout(val percentage: Double)

data class Rule(
    val id: String,
    val name: String = "",
    val conditions: List<Condition> = emptyList(),
    val rollout: Rollout? = null,
    val serveVariant: String,
)

data class Prerequisite(val flagKey: String, val expectedVariant: String)

data class FlagVersion(
    val version: Int,
    val rules: List<Rule> = emptyList(),
    val defaultVariant: String = "off",
    val prerequisites: List<Prerequisite> = emptyList(),
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

data class Flag(
    val key: String,
    var salt: String = "s1",
    var identityAttribute: String = "userId",
    var variants: List<String> = listOf("on", "off"),
    val versions: MutableList<FlagVersion> = mutableListOf(),
) {
    fun version(n: Int?): FlagVersion? =
        if (n == null) versions.maxByOrNull { it.version } else versions.firstOrNull { it.version == n }
}

data class SavedContext(
    val id: String,
    val name: String,
    val data: JsonNode,
)

data class TraceNode(
    val type: String,
    val message: String,
    val data: MutableMap<String, Any?> = mutableMapOf(),
    val children: MutableList<TraceNode> = mutableListOf(),
)

data class EvalRecord(
    val id: String,
    val flagKey: String,
    val version: Int,
    val contextName: String?,
    val context: JsonNode,
    val result: String,
    val trace: TraceNode,
    val at: Long = System.currentTimeMillis(),
)

data class Project(
    val id: String,
    val name: String,
    val secret: String,
    val sensitiveFields: MutableList<String> = mutableListOf(),
    val flags: MutableMap<String, Flag> = mutableMapOf(),
    val contexts: MutableMap<String, SavedContext> = mutableMapOf(),
    val evaluations: MutableMap<String, EvalRecord> = mutableMapOf(),
)

data class AppState(
    val projects: MutableMap<String, Project> = mutableMapOf(),
)
