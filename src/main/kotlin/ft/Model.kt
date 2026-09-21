package ft

/** Result value a flag can resolve to: on/off or a named variant. */
sealed class Outcome : Comparable<Outcome> {
    data object On : Outcome() { override fun toString() = "on" }
    data object Off : Outcome() { override fun toString() = "off" }
    data class Variant(val name: String) : Outcome() { override fun toString() = "variant:$name" }

    val label: String get() = when (this) {
        On -> "on"
        Off -> "off"
        is Variant -> name
    }

    override fun compareTo(other: Outcome): Int = label.compareTo(other.label)

    fun toJson(): Any? = when (this) {
        On -> mapOf("kind" to "on", "label" to "on")
        Off -> mapOf("kind" to "off", "label" to "off")
        is Variant -> mapOf("kind" to "variant", "name" to name, "label" to name)
    }

    companion object {
        fun fromJson(v: Any?): Outcome {
            val m = Json.obj(v)
            return when (Json.str(m["kind"])) {
                "on" -> On
                "off" -> Off
                "variant" -> Variant(Json.str(m["name"]))
                else -> throw IllegalArgumentException("Unknown outcome kind")
            }
        }

        fun of(kind: String, name: String?): Outcome = when (kind) {
            "on" -> On
            "off" -> Off
            "variant" -> Variant(name ?: "default")
            else -> throw IllegalArgumentException("Unknown outcome kind '$kind'")
        }
    }
}

/**
 * A single field condition.
 *
 * Type strictness:
 *  - eq/ne compare both type and value (no string<->number coercion).
 *  - gt/ge/lt/le require two numbers.
 *  - starts_with/ends_with/contains/in require strings (in: string list).
 *  - present/absent distinguish a missing field from an explicit JSON null:
 *    explicit null is "present but null".
 */
data class Condition(val field: String, val op: String, val value: Any? = null) {
    fun toJson(): Any? = linkedMapOf<String, Any?>(
        "field" to field,
        "op" to op,
        "value" to value
    )

    companion object {
        val STRING_OPS = setOf("starts_with", "ends_with", "contains")
        val NUMBER_OPS = setOf("gt", "ge", "lt", "le")
        fun fromJson(v: Any?): Condition {
            val m = Json.obj(v)
            return Condition(Json.str(m["field"]), Json.str(m["op"]), m["value"])
        }
    }
}

/** One weighted slice of a percentage rollout, in basis points (1/100 of a percent). */
data class Slice(val outcome: Outcome, val weightBps: Int) {
    fun toJson(): Any? = linkedMapOf("outcome" to outcome.toJson(), "weightBps" to weightBps)
    companion object {
        fun fromJson(v: Any?): Slice {
            val m = Json.obj(v)
            return Slice(Outcome.fromJson(m["outcome"]), Json.int(m["weightBps"]))
        }
    }
}

/**
 * An ordered targeting rule. Either serves a fixed [outcome] or performs a
 * stable percentage [rollout]. Empty condition list means it always matches.
 */
data class Rule(
    val id: String,
    val name: String,
    val conditions: List<Condition>,
    val outcome: Outcome? = null,
    val rollout: List<Slice>? = null
) {
    val isRollout get() = rollout != null

    fun toJson(): Any? = linkedMapOf<String, Any?>(
        "id" to id,
        "name" to name,
        "conditions" to conditions.map { it.toJson() },
        "outcome" to outcome?.toJson(),
        "rollout" to rollout?.map { it.toJson() }
    )

    companion object {
        fun fromJson(v: Any?): Rule {
            val m = Json.obj(v)
            return Rule(
                id = Json.str(m["id"]),
                name = Json.strOr(m["name"], Json.str(m["id"])),
                conditions = Json.arr(m["conditions"]).map { Condition.fromJson(it!!) },
                outcome = m["outcome"]?.let { Outcome.fromJson(it) },
                rollout = m["rollout"]?.let { Json.arr(it).map { s -> Slice.fromJson(s!!) } }
            )
        }
    }
}

/** Gate on another flag: this flag may only be served (non-default) if the
 *  prerequisite flag resolves to [required]. */
data class Prerequisite(val flagKey: String, val required: Outcome) {
    fun toJson(): Any? = linkedMapOf("flagKey" to flagKey, "required" to required.toJson())
    companion object {
        fun fromJson(v: Any?): Prerequisite {
            val m = Json.obj(v)
            return Prerequisite(Json.str(m["flagKey"]), Outcome.fromJson(m["required"]))
        }
    }
}

/** Immutable, published configuration of one flag. */
data class FlagVersion(
    val key: String,
    val name: String,
    val description: String,
    val version: Int,
    val salt: String,
    val stableIdField: String,
    val prerequisites: List<Prerequisite>,
    val rules: List<Rule>,
    val defaultValue: Outcome,
    val createdAt: Long
) {
    fun toJson(): Any? = linkedMapOf(
        "key" to key,
        "name" to name,
        "description" to description,
        "version" to version,
        "salt" to salt,
        "stableIdField" to stableIdField,
        "prerequisites" to prerequisites.map { it.toJson() },
        "rules" to rules.map { it.toJson() },
        "defaultValue" to defaultValue.toJson(),
        "createdAt" to createdAt
    )

    companion object {
        fun fromJson(v: Any?): FlagVersion {
            val m = Json.obj(v)
            return FlagVersion(
                key = Json.str(m["key"]),
                name = Json.strOr(m["name"], Json.str(m["key"])),
                description = Json.strOr(m["description"], ""),
                version = Json.int(m["version"]),
                salt = Json.str(m["salt"]),
                stableIdField = Json.strOr(m["stableIdField"], "id"),
                prerequisites = (m["prerequisites"]?.let { Json.arr(it) } ?: emptyList())
                    .map { Prerequisite.fromJson(it!!) },
                rules = Json.arr(m["rules"]).map { Rule.fromJson(it!!) },
                defaultValue = Outcome.fromJson(m["defaultValue"]),
                createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0L
            )
        }
    }
}

/** Editable, not-yet-published configuration for a flag. */
data class Draft(
    val name: String,
    val description: String,
    val salt: String,
    val stableIdField: String,
    val prerequisites: List<Prerequisite>,
    val rules: List<Rule>,
    val defaultValue: Outcome
) {
    fun toJson(): Any? = linkedMapOf(
        "name" to name,
        "description" to description,
        "salt" to salt,
        "stableIdField" to stableIdField,
        "prerequisites" to prerequisites.map { it.toJson() },
        "rules" to rules.map { it.toJson() },
        "defaultValue" to defaultValue.toJson()
    )

    companion object {
        fun fromJson(v: Any?): Draft {
            val m = Json.obj(v)
            return Draft(
                name = Json.strOr(m["name"], ""),
                description = Json.strOr(m["description"], ""),
                salt = Json.str(m["salt"]),
                stableIdField = Json.strOr(m["stableIdField"], "id"),
                prerequisites = (m["prerequisites"]?.let { Json.arr(it) } ?: emptyList())
                    .map { Prerequisite.fromJson(it!!) },
                rules = Json.arr(m["rules"]).map { Rule.fromJson(it!!) },
                defaultValue = Outcome.fromJson(m["defaultValue"])
            )
        }

        fun of(fv: FlagVersion): Draft = Draft(
            fv.name, fv.description, fv.salt, fv.stableIdField,
            fv.prerequisites, fv.rules, fv.defaultValue
        )
    }
}

data class Flag(
    val key: String,
    val draft: Draft,
    val versions: List<FlagVersion>,
    val createdAt: Long
) {
    val current: FlagVersion? get() = versions.maxByOrNull { it.version }

    fun toJson(): Any? = linkedMapOf(
        "key" to key,
        "draft" to draft.toJson(),
        "versions" to versions.map { it.toJson() },
        "createdAt" to createdAt
    )

    companion object {
        fun fromJson(v: Any?): Flag {
            val m = Json.obj(v)
            val key = Json.str(m["key"])
            val versions = Json.arr(m["versions"]).map { FlagVersion.fromJson(it!!) }
            val draft = m["draft"]?.let { Draft.fromJson(it) }
                ?: versions.maxByOrNull { it.version }?.let { Draft.of(it) }
                ?: throw IllegalArgumentException("Flag $key has no draft or versions")
            return Flag(key, draft, versions, (m["createdAt"] as? Number)?.toLong() ?: 0L)
        }
    }
}

/** A context saved for side-by-side comparison and batch evaluation. */
data class SavedContext(val id: String, val name: String, val context: Map<String, Any?>) {
    fun toJson(): Any? = linkedMapOf("id" to id, "name" to name, "context" to context)
    companion object {
        fun fromJson(v: Any?): SavedContext {
            val m = Json.obj(v)
            return SavedContext(Json.str(m["id"]), Json.strOr(m["name"], Json.str(m["id"])), Json.obj(m["context"]))
        }
    }
}

data class Project(
    val name: String,
    /** Participates in every bucket key. Exported, so bucketing survives import. */
    val bucketSalt: String,
    /** Secret for sensitive summaries. Regenerated on import, giving domain isolation. */
    val summarySecret: String,
    val sensitiveFields: List<String>,
    val flags: Map<String, Flag>,
    val savedContexts: List<SavedContext>,
    val records: List<EvaluationRecord>
) {
    fun toJson(): Any? = linkedMapOf(
        "name" to name,
        "bucketSalt" to bucketSalt,
        "summarySecret" to summarySecret,
        "sensitiveFields" to sensitiveFields,
        "flags" to flags.mapValues { it.value.toJson() },
        "savedContexts" to savedContexts.map { it.toJson() },
        "records" to records.map { it.toJson() }
    )

    companion object {
        fun fromJson(v: Any?): Project {
            val m = Json.obj(v)
            return Project(
                name = Json.strOr(m["name"], "default"),
                bucketSalt = Json.strOr(m["bucketSalt"], "bucket"),
                summarySecret = Json.str(m["summarySecret"]),
                sensitiveFields = (m["sensitiveFields"]?.let { Json.arr(it) } ?: emptyList())
                    .map { Json.str(it) },
                flags = Json.obj(m["flags"]).mapValues { Flag.fromJson(it.value) },
                savedContexts = (m["savedContexts"]?.let { Json.arr(it) } ?: emptyList())
                    .map { SavedContext.fromJson(it!!) },
                records = (m["records"]?.let { Json.arr(it) } ?: emptyList())
                    .map { EvaluationRecord.fromJson(it!!) }
            )
        }
    }
}
