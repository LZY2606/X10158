package ft

import ft.Json as J

/**
 * Domain model. Everything in a published [FlagVersion] is immutable; editing a
 * flag produces a new version with a new version number. Evaluation traces are
 * stored with the resolved values embedded so historical replay never follows
 * later rule edits.
 */

enum class FlagType { BOOLEAN, STRING }

enum class Operator(val wire: String) {
    EQ("eq"), NEQ("neq"),
    CONTAINS("contains"),
    IN("in"), NOT_IN("not_in"),
    GT("gt"), GTE("gte"), LT("lt"), LTE("lte"),
    EXISTS("exists"),
    ;

    companion object {
        fun from(wire: String): Operator =
            entries.firstOrNull { it.wire == wire } ?: throw AppException(400, "unknown operator '$wire'")
    }
}

data class Condition(
    val field: String,
    val op: Operator,
    val argument: JsonValue,
)

data class RolloutSlice(
    val variant: String,
    /** Bucket weight in basis points; total across slices must be <= 10000. */
    val weightBp: Int,
)

/** A single ordered rule. A rule either returns [value] directly or rolls a percentage. */
data class Rule(
    val id: String,
    val conditions: List<Condition>,
    val value: JsonValue?,
    val rollout: List<RolloutSlice>?,
)

/** Immutable published version of a flag. */
data class FlagVersion(
    val version: Int,
    val type: FlagType,
    val default: JsonValue,
    val salt: String,
    val stableIdentityField: String,
    val prerequisiteKey: String?,
    val prerequisiteExpected: JsonValue?,
    val sensitiveFields: List<String>,
    val rules: List<Rule>,
    val createdAt: String,
)

data class Flag(
    val key: String,
    val name: String,
    val currentVersion: Int,
    val versions: Map<Int, FlagVersion>,
)

data class SavedContext(
    val id: String,
    val name: String,
    val data: JObj,
    val createdAt: String,
)

/** One stored evaluation, bound to a concrete version. */
data class EvalRecord(
    val id: String,
    val flagKey: String,
    val version: Int,
    val contextId: String?,
    val contextName: String?,
    val contextSnapshot: JObj,
    val result: JsonValue,
    val trace: TraceNode,
    val createdAt: String,
)

data class Project(
    val id: String,
    val name: String,
    /** Random bytes (hex) used to key sensitive-field summaries; regenerated per imported copy. */
    val domainSecret: String,
    val flags: Map<String, Flag>,
    val contexts: Map<String, SavedContext>,
    val records: List<EvalRecord>,
)

/**
 * Tree of evaluation steps. Each node records what fields were read so the
 * "which fields were read" trail is complete at every level.
 */
data class TraceNode(
    val step: String,
    val outcome: String,
    val detail: JObj,
    val reads: List<String>,
    val children: List<TraceNode>,
)

class AppException(val status: Int, message: String) : RuntimeException(message)
