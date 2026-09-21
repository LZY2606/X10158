package tracer

import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class Op(val id: String) {
    EQ("eq"), NEQ("neq"), LT("lt"), LTE("lte"), GT("gt"), GTE("gte"),
    CONTAINS("contains"), IN("in");

    companion object {
        fun of(id: String): Op = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("unknown op: $id")
    }
}

data class Condition(val field: String, val op: Op, val value: JVal) {
    fun toJson() = objOf("field" to JVal.JStr(field), "op" to JVal.JStr(op.id), "value" to value)

    companion object {
        fun fromJson(j: JVal.JObj) = Condition(
            field = j["field"].strOrNull() ?: throw IllegalArgumentException("condition.field required"),
            op = Op.of(j["op"].strOrNull() ?: "eq"),
            value = j["value"] ?: JVal.JNull
        )
    }
}

data class Variant(val name: String, val value: JVal, val weight: Double) {
    fun toJson() = objOf(
        "name" to JVal.JStr(name), "value" to value, "weight" to JVal.JNum(weight)
    )

    companion object {
        fun fromJson(j: JVal.JObj) = Variant(
            name = j["name"].strOrNull() ?: "variant",
            value = j["value"] ?: JVal.JNull,
            weight = j["weight"].numOrNull() ?: 0.0
        )
    }
}

/** Result of a matched rule: either a fixed value or a percentage split across variants. */
data class RuleOutcome(val value: JVal?, val split: List<Variant>?) {
    init { require((value != null) xor (split != null)) { "outcome must be value xor split" } }

    fun toJson(): JVal.JObj = if (value != null)
        objOf("type" to JVal.JStr("value"), "value" to value)
    else
        objOf("type" to JVal.JStr("split"), "variants" to JVal.JArr(split!!.map { it.toJson() }))

    companion object {
        fun fromJson(j: JVal.JObj): RuleOutcome = when (j["type"].strOrNull()) {
            "split" -> RuleOutcome(null, j["variants"].arrOrNull()?.map { Variant.fromJson(it as JVal.JObj) } ?: emptyList())
            else -> RuleOutcome(j["value"] ?: JVal.JNull, null)
        }
    }
}

data class Rule(val name: String, val conditions: List<Condition>, val outcome: RuleOutcome) {
    fun toJson() = objOf(
        "name" to JVal.JStr(name),
        "conditions" to JVal.JArr(conditions.map { it.toJson() }),
        "outcome" to outcome.toJson()
    )

    companion object {
        fun fromJson(j: JVal.JObj) = Rule(
            name = j["name"].strOrNull() ?: "rule",
            conditions = j["conditions"].arrOrNull()?.map { Condition.fromJson(it as JVal.JObj) } ?: emptyList(),
            outcome = RuleOutcome.fromJson(j["outcome"].objOrNull() ?: objOf("type" to JVal.JStr("value"), "value" to JVal.JNull))
        )
    }
}

/** Immutable flag definition as published under one version. */
data class FlagDef(
    val key: String,
    val salt: String,
    val stableIdField: String,
    val prerequisites: List<String>,
    val rules: List<Rule>,
    val rollout: List<Variant>,   // flag-level percentage split, applied when no rule matches
    val defaultValue: JVal
) {
    fun toJson() = objOf(
        "key" to JVal.JStr(key),
        "salt" to JVal.JStr(salt),
        "stableIdField" to JVal.JStr(stableIdField),
        "prerequisites" to JVal.JArr(prerequisites.map { JVal.JStr(it) }),
        "rules" to JVal.JArr(rules.map { it.toJson() }),
        "rollout" to JVal.JArr(rollout.map { it.toJson() }),
        "defaultValue" to defaultValue
    )

    companion object {
        fun fromJson(j: JVal.JObj) = FlagDef(
            key = j["key"].strOrNull() ?: throw IllegalArgumentException("flag key required"),
            salt = j["salt"].strOrNull() ?: UUID.randomUUID().toString(),
            stableIdField = j["stableIdField"].strOrNull() ?: "id",
            prerequisites = j["prerequisites"].arrOrNull()?.mapNotNull { it.strOrNull() } ?: emptyList(),
            rules = j["rules"].arrOrNull()?.map { Rule.fromJson(it as JVal.JObj) } ?: emptyList(),
            rollout = j["rollout"].arrOrNull()?.map { Variant.fromJson(it as JVal.JObj) } ?: emptyList(),
            defaultValue = j["defaultValue"] ?: JVal.JNull
        )
    }
}

data class FlagVersion(val version: Int, val def: FlagDef, val publishedAt: Long) {
    fun toJson() = objOf(
        "version" to JVal.JNum(version.toDouble()),
        "publishedAt" to JVal.JNum(publishedAt.toDouble()),
        "def" to def.toJson()
    )

    companion object {
        fun fromJson(j: JVal.JObj) = FlagVersion(
            version = j["version"].numOrNull()?.toInt() ?: 1,
            publishedAt = j["publishedAt"].numOrNull()?.toLong() ?: 0L,
            def = FlagDef.fromJson(j["def"].objOrNull()!!)
        )
    }
}

data class Flag(
    val key: String,
    val draft: FlagDef,
    val versions: List<FlagVersion>   // immutable published versions, ascending
) {
    val latestVersion: Int get() = versions.lastOrNull()?.version ?: 0

    fun versionDef(version: Int?): FlagDef? =
        if (version == null) versions.lastOrNull()?.def
        else versions.firstOrNull { it.version == version }?.def

    fun toJson() = objOf(
        "key" to JVal.JStr(key),
        "draft" to draft.toJson(),
        "versions" to JVal.JArr(versions.map { it.toJson() })
    )

    companion object {
        fun fromJson(j: JVal.JObj) = Flag(
            key = j["key"].strOrNull()!!,
            draft = FlagDef.fromJson(j["draft"].objOrNull()!!),
            versions = j["versions"].arrOrNull()?.map { FlagVersion.fromJson(it as JVal.JObj) } ?: emptyList()
        )
    }
}

data class SavedContext(val id: String, val name: String, val fields: JVal.JObj) {
    fun toJson() = objOf("id" to JVal.JStr(id), "name" to JVal.JStr(name), "fields" to fields)

    companion object {
        fun fromJson(j: JVal.JObj) = SavedContext(
            id = j["id"].strOrNull() ?: UUID.randomUUID().toString(),
            name = j["name"].strOrNull() ?: "context",
            fields = j["fields"].objOrNull() ?: JVal.JObj(LinkedHashMap())
        )
    }
}

/** A recorded evaluation, bound to the exact flag version evaluated. */
data class SavedEvaluation(
    val id: String,
    val flagKey: String,
    val version: Int,
    val contextName: String,
    val context: JVal.JObj,
    val result: JVal,
    val trace: JVal.JObj,
    val createdAt: Long
) {
    fun toJson() = objOf(
        "id" to JVal.JStr(id),
        "flagKey" to JVal.JStr(flagKey),
        "version" to JVal.JNum(version.toDouble()),
        "contextName" to JVal.JStr(contextName),
        "context" to context,
        "result" to result,
        "trace" to trace,
        "createdAt" to JVal.JNum(createdAt.toDouble())
    )

    companion object {
        fun fromJson(j: JVal.JObj) = SavedEvaluation(
            id = j["id"].strOrNull() ?: UUID.randomUUID().toString(),
            flagKey = j["flagKey"].strOrNull() ?: "",
            version = j["version"].numOrNull()?.toInt() ?: 0,
            contextName = j["contextName"].strOrNull() ?: "",
            context = j["context"].objOrNull() ?: JVal.JObj(LinkedHashMap()),
            result = j["result"] ?: JVal.JNull,
            trace = j["trace"].objOrNull() ?: JVal.JObj(LinkedHashMap()),
            createdAt = j["createdAt"].numOrNull()?.toLong() ?: 0L
        )
    }
}

/**
 * Digests for sensitive context fields: HMAC-SHA256 keyed by a per-project secret.
 * Same input within one project yields the same digest (proving equality of inputs),
 * while different projects cannot correlate digests of the same raw value.
 */
class SensitiveDigester(secret: String) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    fun digest(field: String, value: JVal): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        val bytes = mac.doFinal("field=$field|value=${Json.render(value)}".toByteArray(Charsets.UTF_8))
        return "hmac256:" + bytes.joinToString("") { "%02x".format(it) }.substring(0, 24)
    }

    companion object {
        fun newSecret(): String {
            val b = ByteArray(32)
            java.security.SecureRandom().nextBytes(b)
            return MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        }
    }
}
