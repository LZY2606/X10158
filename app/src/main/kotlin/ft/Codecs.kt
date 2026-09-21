package ft

import ft.Json as JC

fun JObj.str(key: String): String =
    (map[key] as? JStr)?.value ?: throw AppException(400, "expected string field '$key'")

fun JObj.obj(key: String): JObj =
    (map[key] as? JObj) ?: throw AppException(400, "expected object field '$key'")

fun JObj.arr(key: String): JArr =
    (map[key] as? JArr) ?: throw AppException(400, "expected array field '$key'")

fun JObj.strOpt(key: String): String? = when (val v = map[key]) {
    null, JNull -> null
    is JStr -> v.value
    else -> throw AppException(400, "field '$key' must be a string or null")
}

fun JObj.intOpt(key: String): Int? {
    val v = map[key] ?: return null
    if (v !is JNum || !v.isIntegral) throw AppException(400, "field '$key' must be an integer")
    return v.num.toInt()
}

object Codecs {

    fun encodeProject(p: Project): JObj = JC.obj(
        "kind" to JC.s("feature-flag-tracker/export"),
        "exportVersion" to JC.n(1),
        "projectId" to JC.s(p.id),
        "name" to JC.s(p.name),
        "domainSecret" to JC.s(p.domainSecret),
        "flags" to JObj(p.flags.values.map { it.key to encodeFlag(it) }),
        "contexts" to JObj(p.contexts.values.map { it.id to encodeContext(it) }),
        "records" to JArr(p.records.map { encodeRecord(it) }),
    )

    fun decodeProject(v: JsonValue): Project {
        val o = v as? JObj ?: throw AppException(400, "project must be an object")
        return Project(
            id = o.str("projectId"),
            name = o.str("name"),
            domainSecret = o.str("domainSecret"),
            flags = (o.obj("flags")).entries.associate { it.first to decodeFlag(it.first, it.second) },
            contexts = (o.obj("contexts")).entries.associate { it.first to decodeContext(it.second) },
            records = o.arr("records").items.map { decodeRecord(it) },
        )
    }

    fun encodeFlag(f: Flag): JObj = JC.obj(
        "key" to JC.s(f.key),
        "name" to JC.s(f.name),
        "currentVersion" to JC.n(f.currentVersion),
        "versions" to JObj(f.versions.entries
            .sortedBy { it.key }
            .map { JC.n(it.key).raw to encodeVersion(it.value) }),
    )

    fun decodeFlag(key: String, v: JsonValue): Flag {
        val o = v as? JObj ?: throw AppException(400, "flag '$key' must be an object")
        val versions = o.obj("versions").entries.associate { (k, raw) ->
            k.toInt() to decodeVersion(k.toInt(), raw)
        }
        return Flag(
            key = o.str("key"),
            name = o.str("name"),
            currentVersion = o.intOpt("currentVersion") ?: versions.keys.max(),
            versions = versions,
        )
    }

    fun encodeVersion(fv: FlagVersion): JObj = JC.obj(
        "version" to JC.n(fv.version),
        "type" to JC.s(fv.type.wire),
        "default" to fv.default,
        "salt" to JC.s(fv.salt),
        "stableIdentityField" to JC.s(fv.stableIdentityField),
        "prerequisiteKey" to (fv.prerequisiteKey?.let { JC.s(it) }),
        "prerequisiteExpected" to fv.prerequisiteExpected,
        "sensitiveFields" to JArr(fv.sensitiveFields.map { JC.s(it) }),
        "rules" to JArr(fv.rules.map { encodeRule(it) }),
        "createdAt" to JC.s(fv.createdAt),
    )

    fun decodeVersion(num: Int, v: JsonValue): FlagVersion {
        val o = v as? JObj ?: throw AppException(400, "version $num must be an object")
        return FlagVersion(
            version = o.intOpt("version") ?: num,
            type = FlagTypeEx.from(o.str("type")),
            default = o.map["default"] ?: throw AppException(400, "version $num needs default"),
            salt = o.str("salt"),
            stableIdentityField = o.str("stableIdentityField"),
            prerequisiteKey = o.strOpt("prerequisiteKey"),
            prerequisiteExpected = o.map["prerequisiteExpected"],
            sensitiveFields = o.arr("sensitiveFields").items.map { (it as JStr).value },
            rules = o.arr("rules").items.map { decodeRule(it) },
            createdAt = o.strOpt("createdAt") ?: Store.now(),
        )
    }

    fun encodeRule(r: Rule): JObj = JC.obj(
        "id" to JC.s(r.id),
        "conditions" to JArr(r.conditions.map { encodeCondition(it) }),
        "value" to r.value,
        "rollout" to (r.rollout?.let { JArr(it.map { s -> encodeSlice(s) }) }),
    )

    fun decodeRule(v: JsonValue): Rule {
        val o = v as? JObj ?: throw AppException(400, "rule must be an object")
        val rawValue = o.map["value"]
        return Rule(
            id = o.str("id"),
            conditions = o.arr("conditions").items.map { decodeCondition(it) },
            value = if (rawValue == null || rawValue === JNull) null else rawValue,
            rollout = (o.map["rollout"] as? JArr)?.items?.map { decodeSlice(it) },
        )
    }

    fun encodeCondition(c: Condition): JObj = JC.obj(
        "field" to JC.s(c.field),
        "op" to JC.s(c.op.wire),
        "argument" to c.argument,
    )

    fun decodeCondition(v: JsonValue): Condition {
        val o = v as? JObj ?: throw AppException(400, "condition must be an object")
        return Condition(
            field = o.str("field"),
            op = Operator.from(o.str("op")),
            argument = o.map["argument"] ?: JNull,
        )
    }

    fun encodeSlice(s: RolloutSlice): JObj = JC.obj(
        "variant" to JC.s(s.variant),
        "weightBp" to JC.n(s.weightBp),
    )

    fun decodeSlice(v: JsonValue): RolloutSlice {
        val o = v as? JObj ?: throw AppException(400, "rollout slice must be an object")
        val w = o.intOpt("weightBp") ?: throw AppException(400, "weightBp must be an integer")
        return RolloutSlice(o.str("variant"), w)
    }

    fun encodeContext(c: SavedContext): JObj = JC.obj(
        "id" to JC.s(c.id),
        "name" to JC.s(c.name),
        "data" to c.data,
        "createdAt" to JC.s(c.createdAt),
    )

    fun decodeContext(v: JsonValue): SavedContext {
        val o = v as? JObj ?: throw AppException(400, "context must be an object")
        return SavedContext(
            id = o.str("id"),
            name = o.str("name"),
            data = o.obj("data"),
            createdAt = o.strOpt("createdAt") ?: Store.now(),
        )
    }

    fun encodeRecord(r: EvalRecord): JObj = JC.obj(
        "id" to JC.s(r.id),
        "flagKey" to JC.s(r.flagKey),
        "version" to JC.n(r.version),
        "contextId" to (r.contextId?.let { JC.s(it) }),
        "contextName" to (r.contextName?.let { JC.s(it) }),
        "contextSnapshot" to r.contextSnapshot,
        "result" to r.result,
        "trace" to encodeTrace(r.trace),
        "createdAt" to JC.s(r.createdAt),
    )

    fun decodeRecord(v: JsonValue): EvalRecord {
        val o = v as? JObj ?: throw AppException(400, "record must be an object")
        return EvalRecord(
            id = o.str("id"),
            flagKey = o.str("flagKey"),
            version = o.intOpt("version") ?: throw AppException(400, "record needs version"),
            contextId = o.strOpt("contextId"),
            contextName = o.strOpt("contextName"),
            contextSnapshot = o.obj("contextSnapshot"),
            result = o.map["result"] ?: JNull,
            trace = decodeTrace(o.obj("trace")),
            createdAt = o.strOpt("createdAt") ?: Store.now(),
        )
    }

    fun encodeTrace(t: TraceNode): JObj = JC.obj(
        "step" to JC.s(t.step),
        "outcome" to JC.s(t.outcome),
        "detail" to t.detail,
        "reads" to JArr(t.reads.map { JC.s(it) }),
        "children" to JArr(t.children.map { encodeTrace(it) }),
    )

    fun decodeTrace(v: JsonValue): TraceNode {
        val o = v as? JObj ?: throw AppException(400, "trace must be an object")
        return TraceNode(
            step = o.str("step"),
            outcome = o.str("outcome"),
            detail = o.obj("detail"),
            reads = o.arr("reads").items.map { (it as JStr).value },
            children = o.arr("children").items.map { decodeTrace(it) },
        )
    }
}

val FlagType.wire: String get() = name.lowercase()

object FlagTypeEx {
    fun from(w: String): FlagType = when (w) {
        "boolean" -> FlagType.BOOLEAN
        "string" -> FlagType.STRING
        else -> throw AppException(400, "unknown flag type '$w'")
    }
}
