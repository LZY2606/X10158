package tracker

/** 比较运算符。字符串与数字之间不做隐式转换。 */
enum class Op(val wire: String, val label: String) {
    EQ("eq", "等于"), NEQ("neq", "不等于"),
    LT("lt", "小于"), LTE("lte", "小于等于"),
    GT("gt", "大于"), GTE("gte", "大于等于"),
    IN("in", "在列表中"), CONTAINS("contains", "包含");

    companion object {
        fun of(w: String) = entries.firstOrNull { it.wire == w }
            ?: throw IllegalArgumentException("未知运算符: $w")
    }
}

data class Condition(val attr: String, val op: Op, val value: JVal) {
    fun toJson() = jObjOf("attr" to JStr(attr), "op" to JStr(op.wire), "value" to value)
    companion object {
        fun fromJson(j: JObj) = Condition(
            (j["attr"] as JStr).v, Op.of((j["op"] as JStr).v), j["value"] ?: JNull
        )
    }
}

/** 规则命中后的服务方式：固定值或百分比分流。 */
sealed interface Serve {
    fun toJson(): JObj

    data class Value(val value: JVal) : Serve {
        override fun toJson() = jObjOf("type" to JStr("value"), "value" to value)
    }

    /**
     * 百分比分流。分桶只依赖 flagKey + salt + 身份值，与规则顺序无关。
     * 各变体权重为 0..100 的整数百分比，总和必须为 100。
     */
    data class Rollout(val salt: String, val identityAttr: String, val variations: List<Variation>) : Serve {
        override fun toJson() = jObjOf(
            "type" to JStr("rollout"),
            "salt" to JStr(salt),
            "identityAttr" to JStr(identityAttr),
            "variations" to JArr(variations.map { it.toJson() })
        )
    }

    companion object {
        fun fromJson(j: JObj): Serve = when ((j["type"] as JStr).v) {
            "value" -> Value(j["value"] ?: JNull)
            "rollout" -> Rollout(
                (j["salt"] as JStr).v,
                (j["identityAttr"] as JStr).v,
                (j["variations"] as JArr).items.map { Variation.fromJson(it as JObj) }
            )
            else -> throw IllegalArgumentException("未知 serve 类型")
        }
    }
}

data class Variation(val weight: Int, val value: JVal) {
    fun toJson() = jObjOf("weight" to JNum(weight.toString()), "value" to value)
    companion object {
        fun fromJson(j: JObj) = Variation((j["weight"] as JNum).dec.toInt(), j["value"] ?: JNull)
    }
}

data class Rule(val name: String, val conditions: List<Condition>, val serve: Serve) {
    fun toJson() = jObjOf(
        "name" to JStr(name),
        "conditions" to JArr(conditions.map { it.toJson() }),
        "serve" to serve.toJson()
    )
    companion object {
        fun fromJson(j: JObj) = Rule(
            (j["name"] as JStr).v,
            (j["conditions"] as JArr).items.map { Condition.fromJson(it as JObj) },
            Serve.fromJson(j["serve"] as JObj)
        )
    }
}

/** 前置开关依赖：要求目标开关求值结果等于 requiredValue（默认 true）。 */
data class Prereq(val flagKey: String, val requiredValue: JVal = JBool(true)) {
    fun toJson() = jObjOf("flag" to JStr(flagKey), "value" to requiredValue)
    companion object {
        fun fromJson(j: JObj) = Prereq((j["flag"] as JStr).v, j["value"] ?: JBool(true))
    }
}

/** 不可变的开关版本。发布后不再修改，历史轨迹因此稳定。 */
data class FlagVersion(
    val version: Int,
    val defaultValue: JVal,
    val rules: List<Rule>,
    val prerequisites: List<Prereq>,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson() = jObjOf(
        "version" to JNum(version.toString()),
        "defaultValue" to defaultValue,
        "rules" to JArr(rules.map { it.toJson() }),
        "prerequisites" to JArr(prerequisites.map { it.toJson() }),
        "createdAt" to JNum(createdAt.toString())
    )
    companion object {
        fun fromJson(j: JObj) = FlagVersion(
            (j["version"] as JNum).dec.toInt(),
            j["defaultValue"] ?: JNull,
            (j["rules"] as JArr).items.map { Rule.fromJson(it as JObj) },
            (j["prerequisites"] as JArr).items.map { Prereq.fromJson(it as JObj) },
            (j["createdAt"] as JNum).dec.toLong()
        )
    }
}

data class Flag(val key: String, val salt: String, val versions: MutableList<FlagVersion>) {
    val current: FlagVersion get() = versions.last()
    fun version(n: Int) = versions.firstOrNull { it.version == n }
    fun toJson() = jObjOf(
        "key" to JStr(key),
        "salt" to JStr(salt),
        "versions" to JArr(versions.map { it.toJson() })
    )
    companion object {
        fun fromJson(j: JObj) = Flag(
            (j["key"] as JStr).v,
            (j["salt"] as JStr).v,
            (j["versions"] as JArr).items.map { FlagVersion.fromJson(it as JObj) }.toMutableList()
        )
    }
}

/** 保存的求值上下文。sensitive 声明了哪些字段是敏感字段。 */
data class Ctx(
    val id: String,
    val name: String,
    val attrs: JObj,
    val sensitive: Set<String> = emptySet()
) {
    fun toJson() = jObjOf(
        "id" to JStr(id),
        "name" to JStr(name),
        "attrs" to attrs,
        "sensitive" to JArr(sensitive.sorted().map { JStr(it) })
    )
    companion object {
        fun fromJson(j: JObj) = Ctx(
            (j["id"] as JStr).v,
            (j["name"] as JStr).v,
            j["attrs"] as JObj,
            ((j["sensitive"] as? JArr)?.items?.map { (it as JStr).v } ?: emptyList()).toSet()
        )
    }
}

data class Project(val id: String, val name: String, val secret: String) {
    fun toJson() = jObjOf("id" to JStr(id), "name" to JStr(name), "secret" to JStr(secret))
    companion object {
        fun fromJson(j: JObj) = Project(
            (j["id"] as JStr).v, (j["name"] as JStr).v, (j["secret"] as JStr).v
        )
    }
}

/** 保存的求值记录：绑定开关版本与当时的上下文快照，不随规则变化。 */
data class EvalRecord(
    val id: String,
    val flagKey: String,
    val version: Int,
    val contextId: String,
    val contextAttrs: JObj,
    val sensitive: Set<String>,
    val result: JVal,
    val trace: JObj,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson() = jObjOf(
        "id" to JStr(id),
        "flagKey" to JStr(flagKey),
        "version" to JNum(version.toString()),
        "contextId" to JStr(contextId),
        "contextAttrs" to contextAttrs,
        "sensitive" to JArr(sensitive.sorted().map { JStr(it) }),
        "result" to result,
        "trace" to trace,
        "createdAt" to JNum(createdAt.toString())
    )
    companion object {
        fun fromJson(j: JObj) = EvalRecord(
            (j["id"] as JStr).v,
            (j["flagKey"] as JStr).v,
            (j["version"] as JNum).dec.toInt(),
            (j["contextId"] as JStr).v,
            j["contextAttrs"] as JObj,
            ((j["sensitive"] as? JArr)?.items?.map { (it as JStr).v } ?: emptyList()).toSet(),
            j["result"] ?: JNull,
            j["trace"] as JObj,
            (j["createdAt"] as JNum).dec.toLong()
        )
    }
}
