package ft

/** Value a flag can serve. `on`/`off` are canonical; variants are strings. */
data class ServeValue(val name: String) {
    companion object {
        val ON = ServeValue("on")
        val OFF = ServeValue("off")
        fun parse(text: String): ServeValue {
            val trimmed = text.trim()
            require(trimmed.isNotEmpty()) { "serve value must not be empty" }
            return when (trimmed.lowercase()) {
                "on", "true", "enabled" -> ON
                "off", "false", "disabled" -> OFF
                else -> ServeValue(trimmed)
            }
        }
    }

    fun isOn(): Boolean = name == "on"
    fun isOff(): Boolean = name == "off"
}

enum class Operator(val wire: String) {
    EQ("eq"),
    NE("ne"),
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
    IN("in"),
    NOT_IN("notIn"),
    CONTAINS("contains"),
    STARTS_WITH("startsWith"),
    ENDS_WITH("endsWith"),
    EXISTS("exists");

    companion object {
        fun fromWire(wire: String): Operator =
            entries.firstOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("unknown operator '$wire'")
    }
}

data class Condition(
    val field: String,
    val operator: Operator,
    val value: JsonValue? = null
)

/**
 * Weighted rollout clause. [weight] is in basis points (0..10000). Buckets
 * in [startBp] until startBp + weight serve [serve]. A clause carries its
 * own [salt]; leaving it empty means "use the rule's salt". Reordering rules
 * without touching a clause therefore keeps users in the same bucket.
 */
data class RolloutClause(
    val serve: ServeValue,
    val weightBp: Int,
) {
    init {
        require(weightBp in 0..10000) { "weight must be within 0..10000" }
    }
}

data class Rule(
    val id: String,
    val name: String,
    val conditions: List<Condition>,
    /** Match-all behaviour when no rollout is configured. */
    val serve: ServeValue? = null,
    /** Optional weighted split; evaluated after all conditions match. */
    val rollout: List<RolloutClause>? = null,
    /** Served when the key falls outside every clause of a partial rollout. */
    val fallbackServe: ServeValue? = null,
    /** Participates in the bucketing key together with flag key + stable id. */
    val salt: String = ""
) {
    val hasRollout: Boolean get() = rollout != null
}

data class Prerequisite(
    val flagKey: String,
    /** Only proceed when the prerequisite serves one of these values. */
    val anyOf: List<ServeValue>,
    /** Result when the prerequisite is missing or evaluates otherwise. */
    val gateServe: ServeValue = ServeValue.OFF
)

data class FlagVersion(
    val version: Int,
    val rules: List<Rule>,
    val prerequisites: List<Prerequisite>,
    val defaultValue: ServeValue,
    val stableIdField: String,
    val note: String = "",
    val createdAtMs: Long
)

data class Flag(
    val projectId: String,
    val key: String,
    val name: String,
    val description: String,
    val versions: List<FlagVersion>,
    val draftRules: List<Rule> = emptyList(),
    val draftPrerequisites: List<Prerequisite> = emptyList(),
    val draftDefaultValue: ServeValue = ServeValue.OFF,
    val draftStableIdField: String = "user.id"
) {
    fun version(n: Int): FlagVersion =
        versions.firstOrNull { it.version == n }
            ?: throw IllegalArgumentException("flag '$key' has no version $n")

    fun latestVersion(): FlagVersion = versions.maxBy { it.version }
}

data class Project(
    val id: String,
    val name: String,
    /** Per-project random salt; makes sensitive digests unlinkable cross-project. */
    val digestSalt: String,
    val sensitiveFields: List<String>
)

data class SavedContext(
    val id: String,
    val name: String,
    val context: JsonObject,
    val createdAtMs: Long
)

/**
 * A persisted evaluation is permanently bound to the exact flag version it
 * ran against. Replaying it later uses that pinned version, so historical
 * traces never silently follow later rule edits.
 */
data class EvalRecord(
    val id: String,
    val projectId: String,
    val flagKey: String,
    val version: Int,
    val contextName: String,
    val context: JsonObject,
    val result: ServeValue,
    val trace: JsonObject,
    val createdAtMs: Long
)

data class Bundle(
    val exportedAtMs: Long,
    val projects: List<Project>,
    val flags: List<Flag>,
    val contexts: List<SavedContext>,
    val records: List<EvalRecord>
)

/** Builds the fully traceable bucketing key: flag key + clause/rule salt + id. */
fun bucketingKey(
    flagKey: String,
    ruleSalt: String,
    stableId: String
): String {
    return "$flagKey.$ruleSalt.$stableId"
}
