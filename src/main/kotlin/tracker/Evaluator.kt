package tracker

/** 一次批量求值使用的版本快照：捕获后不受新版本发布影响。 */
data class Snapshot(val versions: Map<String, FlagVersion>) {
    fun with(flagKey: String, v: FlagVersion) = Snapshot(versions + (flagKey to v))
}

class EvalException(msg: String) : Exception(msg)
class CycleException(val path: List<String>) : Exception("前置依赖存在环: ${path.joinToString(" → ")}")

class Evaluator(
    private val snapshot: Snapshot,
    private val project: Project,
    private val visiting: MutableSet<String> = mutableSetOf()
) {
    /** 求值并返回完整轨迹（JObj）。结果值在轨迹的 "result" 字段。 */
    fun evaluate(flagKey: String, attrs: JObj, sensitive: Set<String>): JObj {
        val fv = snapshot.versions[flagKey] ?: throw EvalException("开关不存在: $flagKey")
        if (!visiting.add(flagKey)) {
            throw CycleException(visiting.toList() + flagKey)
        }
        try {
            val steps = mutableListOf<JVal>()
            var result: JVal = fv.defaultValue
            var decided = false

            // 1. 前置依赖
            var prereqOk = true
            for (p in fv.prerequisites) {
                val child = evaluate(p.flagKey, attrs, sensitive)
                val actual = child["result"]!!
                val met = Json.strictEquals(actual, p.requiredValue)
                steps.add(jObjOf(
                    "type" to JStr("prereq"),
                    "flag" to JStr(p.flagKey),
                    "required" to p.requiredValue,
                    "actual" to actual,
                    "met" to JBool(met),
                    "trace" to child
                ))
                if (!met) prereqOk = false
            }
            if (!prereqOk) {
                steps.add(jObjOf(
                    "type" to JStr("default"),
                    "reason" to JStr("前置开关未满足，返回默认值"),
                    "value" to fv.defaultValue
                ))
                return flagTrace(flagKey, fv.version, fv.defaultValue, steps)
            }

            // 2. 按顺序求值规则
            fv.rules.forEachIndexed { idx, rule ->
                val condSteps = rule.conditions.map { evalCondition(it, attrs, sensitive) }
                val matched = condSteps.all { (it["passed"] as JBool).v }
                val step = mutableMapOf<String, JVal>(
                    "type" to JStr("rule"),
                    "index" to JNum(idx.toString()),
                    "name" to JStr(rule.name),
                    "conditions" to JArr(condSteps),
                    "matched" to JBool(matched)
                )
                if (matched) {
                    when (val serve = rule.serve) {
                        is Serve.Value -> {
                            step["serve"] = jObjOf("type" to JStr("value"), "value" to serve.value)
                            result = serve.value
                            decided = true
                        }
                        is Serve.Rollout -> {
                            val rs = evalRollout(flagKey, serve, attrs, sensitive)
                            step["serve"] = rs.first
                            val rv = rs.second
                            if (rv != null) {
                                result = rv
                                decided = true
                            } else {
                                // 身份缺失/为 null：分流无法求值，规则视为未命中
                                step["matched"] = JBool(false)
                                step["note"] = JStr("分流身份不可用，规则未命中")
                            }
                        }
                    }
                }
                steps.add(JObj(LinkedHashMap(step)))
                if (decided) return flagTrace(flagKey, fv.version, result, steps)
            }

            // 3. 默认值
            steps.add(jObjOf(
                "type" to JStr("default"),
                "reason" to JStr("没有规则命中，返回默认值"),
                "value" to fv.defaultValue
            ))
            return flagTrace(flagKey, fv.version, fv.defaultValue, steps)
        } finally {
            visiting.remove(flagKey)
        }
    }

    private fun flagTrace(flagKey: String, version: Int, result: JVal, steps: List<JVal>) = jObjOf(
        "type" to JStr("flag"),
        "flag" to JStr(flagKey),
        "version" to JNum(version.toString()),
        "result" to result,
        "steps" to JArr(steps)
    )

    /** 读取上下文字段：区分 缺失 / null / 有值；敏感字段只输出摘要。 */
    private fun readField(attrs: JObj, sensitive: Set<String>, attr: String): MutableMap<String, JVal> {
        val m = mutableMapOf<String, JVal>("attr" to JStr(attr))
        val isSensitive = attr in sensitive
        m["sensitive"] = JBool(isSensitive)
        when {
            !attrs.has(attr) -> m["status"] = JStr("missing")
            attrs[attr] is JNull -> m["status"] = JStr("null")
            else -> {
                m["status"] = JStr("value")
                val v = attrs[attr]!!
                if (isSensitive) m["digest"] = JStr(Digest.of(project, attr, v))
                else m["actual"] = v
            }
        }
        return m
    }

    private fun evalCondition(c: Condition, attrs: JObj, sensitive: Set<String>): JObj {
        val step = readField(attrs, sensitive, c.attr)
        step["op"] = JStr(c.op.wire)
        step["expected"] = c.value
        var passed = false
        var reason: String
        when (step["status"]) {
            JStr("missing") -> reason = "字段缺失，条件不成立"
            JStr("null") -> {
                when (c.op) {
                    Op.EQ -> { passed = c.value is JNull; reason = if (passed) "null 等于 null" else "字段为 null，不等于非 null 期望值" }
                    Op.NEQ -> { passed = c.value !is JNull; reason = if (passed) "字段为 null，不等于非 null 期望值" else "null 等于 null" }
                    else -> reason = "字段为 null，无法做 ${c.op.label} 比较"
                }
            }
            else -> {
                val actual = attrs[c.attr]!!
                val r = compare(actual, c.op, c.value)
                passed = r.first
                reason = r.second
            }
        }
        step["passed"] = JBool(passed)
        step["reason"] = JStr(reason)
        return JObj(LinkedHashMap(step))
    }

    /** 严格类型比较：数字只与数字、字符串只与字符串比较，绝不隐式互转。 */
    private fun compare(actual: JVal, op: Op, expected: JVal): Pair<Boolean, String> {
        fun typeName(v: JVal) = when (v) {
            is JNull -> "null"; is JBool -> "布尔"; is JNum -> "数字"
            is JStr -> "字符串"; is JArr -> "数组"; is JObj -> "对象"
        }
        return when (op) {
            Op.EQ -> Json.strictEquals(actual, expected) to
                if (Json.strictEquals(actual, expected)) "严格相等" else "不相等（类型或值不同）"
            Op.NEQ -> !Json.strictEquals(actual, expected) to
                if (!Json.strictEquals(actual, expected)) "不相等" else "严格相等"
            Op.LT, Op.LTE, Op.GT, Op.GTE -> {
                val cmp: Int? = when {
                    actual is JNum && expected is JNum -> actual.dec.compareTo(expected.dec)
                    actual is JStr && expected is JStr -> actual.v.compareTo(expected.v)
                    else -> null
                }
                if (cmp == null) {
                    false to "类型不匹配：${typeName(actual)} 不能与 ${typeName(expected)} 做大小比较（不隐式转换）"
                } else {
                    val ok = when (op) {
                        Op.LT -> cmp < 0; Op.LTE -> cmp <= 0; Op.GT -> cmp > 0; else -> cmp >= 0
                    }
                    ok to "比较结果 $cmp，${op.label} " + (if (ok) "成立" else "不成立")
                }
            }
            Op.IN -> {
                if (expected is JArr) {
                    val ok = expected.items.any { Json.strictEquals(it, actual) }
                    ok to if (ok) "在列表中" else "不在列表中"
                } else false to "IN 的期望值必须是数组"
            }
            Op.CONTAINS -> when {
                actual is JStr && expected is JStr ->
                    (expected.v in actual.v) to if (expected.v in actual.v) "字符串包含" else "字符串不包含"
                actual is JArr ->
                    actual.items.any { Json.strictEquals(it, expected) } to "数组成员判断"
                else -> false to "类型不匹配：${typeName(actual)} 不支持包含判断"
            }
        }
    }

    /** 百分比分流。返回 (轨迹step, 结果值?)；身份缺失或 null 时结果为 null。 */
    private fun evalRollout(
        flagKey: String, serve: Serve.Rollout, attrs: JObj, sensitive: Set<String>
    ): Pair<JObj, JVal?> {
        val step = mutableMapOf<String, JVal>(
            "type" to JStr("rollout"),
            "identityAttr" to JStr(serve.identityAttr),
            "salt" to JStr(serve.salt)
        )
        val field = readField(attrs, sensitive, serve.identityAttr)
        step["identityStatus"] = field["status"]!!
        step["sensitive"] = field["sensitive"]!!
        when (field["status"]) {
            JStr("missing"), JStr("null") -> {
                step["reason"] = JStr("身份字段${if (field["status"] == JStr("missing")) "缺失" else "为 null"}，无法分桶")
                return JObj(LinkedHashMap(step)) to null
            }
        }
        val identityVal = attrs[serve.identityAttr]!!
        val identity = Json.renderCanonical(identityVal)
        if (field["sensitive"] == JBool(true)) {
            step["identityDigest"] = field["digest"]!!
        } else {
            step["identity"] = identityVal
            step["bucketInput"] = JStr("${serve.salt}:$flagKey:$identity")
        }
        val bucket = Bucket.bucketOf(serve.salt, flagKey, identity)
        val chosen = Bucket.choose(bucket, serve.variations)
        var acc = 0
        val vars = serve.variations.mapIndexed { i, v ->
            val from = acc; acc += v.weight
            jObjOf(
                "weight" to JNum(v.weight.toString()),
                "from" to JNum(from.toString()),
                "to" to JNum(acc.toString()),
                "value" to v.value,
                "chosen" to JBool(i == chosen)
            )
        }
        step["bucket"] = JNum(bucket.toString())
        step["buckets"] = JNum(Bucket.BUCKETS.toString())
        step["variations"] = JArr(vars)
        step["chosen"] = JNum(chosen.toString())
        step["value"] = serve.variations[chosen].value
        return JObj(LinkedHashMap(step)) to serve.variations[chosen].value
    }
}
