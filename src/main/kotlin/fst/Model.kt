package fst

/* ---------- Value served by a flag: either the off sentinel or a named variant ---------- */
data class Served(val off: Boolean, val variant: String? = null) {
    init { require(off || variant != null) }
    fun display(): String = if (off) "off" else variant!!
    fun toJson(): Any? = if (off) "off" else variant
    companion object {
        fun parse(v: Any?): Served = when (v) {
            null, "off" -> Served(true)
            is String -> Served(false, v)
            else -> throw IllegalArgumentException("bad serve value: $v")
        }
    }
}

/** How a clause answers: a fixed variant / off, or a percentage rollout. */
data class Serve(val type: String, val variant: String? = null) { // type: variant | off
    fun toJson(): Any? = if (type == "off") "off" else variant
    fun resolve(distributionVariant: String? = null): Served = when (type) {
        "off" -> Served(true)
        "variant" -> Served(false, variant ?: distributionVariant ?: error("variant serve without name"))
        else -> error("unknown serve type $type")
    }
    companion object { fun parse(v: Any?): Serve = if (v == "off" || v == null) Serve("off") else Serve("variant", v.asString()) }
}

data class Condition(val id: String, val field: String, val op: String, val value: Any?) {
    fun toJson() = Json.obj("id" to id, "field" to field, "op" to op, "value" to value)
    companion object {
        fun from(m: Map<String, Any?>): Condition = Condition(
            id = (m["id"] as? String)?.ifBlank { null } ?: IdGen.next("c"),
            field = m.str("field"),
            op = m.str("op"),
            value = m["value"],
        )
    }
}

data class Distribution(val variant: String, val weight: Int) {
    fun toJson() = Json.obj("variant" to variant, "weight" to weight)
    companion object { fun from(m: Map<String, Any?>) = Distribution(m.str("variant"), m.num("weight").toInt()) }
}

data class Rule(
    val id: String,
    val name: String,
    val conditions: List<Condition>,
    val fixed: Serve?,          // non-null => serve fixed value
    val rollout: List<Distribution>?, // non-null => percentage split
) {
    fun toJson() = Json.obj(
        "id" to id, "name" to name,
        "conditions" to conditions.map { it.toJson() },
        "serve" to fixed?.toJson(),
        "rollout" to rollout?.map { it.toJson() },
    )
    companion object {
        fun from(m: Map<String, Any?>): Rule {
            val rollout = (m["rollout"] as? List<*>)?.takeIf { it.isNotEmpty() }?.map { Distribution.from(it.asMap()) }
            val serve = m["serve"]
            return Rule(
                id = (m["id"] as? String)?.ifBlank { null } ?: IdGen.next("r"),
                name = (m["name"] as? String) ?: "",
                conditions = (m["conditions"] as? List<*>)?.map { Condition.from(it.asMap()) } ?: emptyList(),
                fixed = if (rollout == null && serve != null) Serve.parse(serve) else null,
                rollout = rollout,
            )
        }
    }
}

data class Prerequisite(val flagKey: String, val expected: String) {
    fun toJson() = Json.obj("flag" to flagKey, "expected" to expected)
    companion object { fun from(m: Map<String, Any?>) = Prerequisite(m.str("flag"), m.str("expected")) }
}

data class FlagVersion(
    val version: Int,
    val salt: String,
    val onVariants: List<String>,
    val prerequisites: List<Prerequisite>,
    val rules: List<Rule>,
    val defaultServe: Serve,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson() = Json.obj(
        "version" to version, "salt" to salt, "onVariants" to onVariants,
        "prerequisites" to prerequisites.map { it.toJson() },
        "rules" to rules.map { it.toJson() },
        "defaultServe" to defaultServe.toJson(),
        "note" to note, "createdAt" to createdAt,
    )
    companion object {
        fun from(m: Map<String, Any?>) = FlagVersion(
            version = m.num("version").toInt(),
            salt = m.str("salt"),
            onVariants = (m["onVariants"] as? List<*>)?.map { it.asString() } ?: emptyList(),
            prerequisites = (m["prerequisites"] as? List<*>)?.map { Prerequisite.from(it.asMap()) } ?: emptyList(),
            rules = (m["rules"] as? List<*>)?.map { Rule.from(it.asMap()) } ?: emptyList(),
            defaultServe = Serve.parse(m["defaultServe"]),
            note = (m["note"] as? String) ?: "",
            createdAt = (m["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        )
    }
}

data class Flag(
    val key: String,
    val name: String,
    val stableIdField: String,
    val sensitiveFields: List<String>,
    val versions: List<FlagVersion>,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val currentVersion: Int get() = versions.lastOrNull()?.version ?: 0
    fun version(v: Int): FlagVersion? = versions.firstOrNull { it.version == v }
    fun current(): FlagVersion? = versions.lastOrNull()
    fun toJson() = Json.obj(
        "key" to key, "name" to name, "stableIdField" to stableIdField,
        "sensitiveFields" to sensitiveFields, "currentVersion" to currentVersion,
        "createdAt" to createdAt, "versions" to versions.map { it.toJson() },
    )
    companion object {
        fun from(m: Map<String, Any?>) = Flag(
            key = m.str("key"), name = (m["name"] as? String) ?: m.str("key"),
            stableIdField = (m["stableIdField"] as? String)?.ifBlank { null } ?: "id",
            sensitiveFields = (m["sensitiveFields"] as? List<*>)?.map { it.asString() } ?: emptyList(),
            versions = (m["versions"] as? List<*>)?.map { FlagVersion.from(it.asMap()) } ?: emptyList(),
            createdAt = (m["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        )
    }
}

data class SavedContext(val id: String, val name: String, val value: Map<String, Any?>, val createdAt: Long = System.currentTimeMillis()) {
    fun toJson() = Json.obj("id" to id, "name" to name, "value" to value, "createdAt" to createdAt)
    companion object { fun from(m: Map<String, Any?>) = SavedContext(m.str("id"), m.str("name"), (m["value"] as? Map<*,*>)?.let { it.entries.associate { e -> e.key.toString() to e.value } } ?: emptyMap(), (m["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()) }
}

data class EvalRecord(
    val id: String,
    val flagKey: String,
    val version: Int,
    val contextId: String?,
    val contextName: String?,
    val context: Map<String, Any?>,
    val result: String,
    val reason: String,
    val trace: Map<String, Any?>,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson() = Json.obj(
        "id" to id, "flagKey" to flagKey, "version" to version,
        "contextId" to contextId, "contextName" to contextName, "context" to context,
        "result" to result, "reason" to reason, "trace" to trace, "createdAt" to createdAt,
        "frozen" to true,
    )
    companion object { fun from(m: Map<String, Any?>) = EvalRecord(
        m.str("id"), m.str("flagKey"), m.num("version").toInt(),
        m["contextId"] as? String, m["contextName"] as? String,
        (m["context"] as? Map<*,*>)?.let { it.entries.associate { e -> e.key.toString() to e.value } } ?: emptyMap(),
        m.str("result"), m.str("reason"),
        (m["trace"] as? Map<*,*>)?.let { it.entries.associate { e -> e.key.toString() to e.value } } ?: emptyMap(),
        (m["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
    )}
}

data class Project(
    val id: String,
    val name: String,
    var digestSalt: String,
    val flags: MutableList<Flag> = mutableListOf(),
    val contexts: MutableList<SavedContext> = mutableListOf(),
    val records: MutableList<EvalRecord> = mutableListOf(),
) {
    fun toJson() = Json.obj(
        "id" to id, "name" to name, "digestSalt" to digestSalt,
        "flags" to flags.map { it.toJson() },
        "contexts" to contexts.map { it.toJson() },
        "records" to records.map { it.toJson() },
    )
    companion object {
        fun from(m: Map<String, Any?>): Project = Project(
            m.str("id"), m.str("name"), m.str("digestSalt"),
            (m["flags"] as? List<*>)?.mapTo(mutableListOf()) { Flag.from(it.asMap()) } ?: mutableListOf(),
            (m["contexts"] as? List<*>)?.mapTo(mutableListOf()) { SavedContext.from(it.asMap()) } ?: mutableListOf(),
            (m["records"] as? List<*>)?.mapTo(mutableListOf()) { EvalRecord.from(it.asMap()) } ?: mutableListOf(),
        )
    }
}

object IdGen {
    private var n = 0
    @Synchronized fun next(prefix: String): String = "${prefix}_${System.currentTimeMillis().toString(36)}_${(n++).toString(36)}"
}
