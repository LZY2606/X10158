package fftrace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

data class FlagVersionedConfig(
    val key: String,
    val version: Int,
    val config: FlagConfig
)

data class Snapshot(
    val versions: Map<String, FlagVersionedConfig>,
    val sensitiveFields: Set<String>,
    val projectSecret: String
)

class Evaluator(private val snapshot: Snapshot) {
    private val digester = Digester(snapshot.projectSecret)

    fun evaluate(flagKey: String, context: Map<String, JsonElement>): EvaluationTrace =
        evaluateInternal(flagKey, context, emptyList())

    private fun evaluateInternal(
        flagKey: String,
        context: Map<String, JsonElement>,
        chain: List<String>
    ): EvaluationTrace {
        val steps = mutableListOf<String>()
        val fv = snapshot.versions[flagKey]
            ?: return EvaluationTrace(
                flagKey, 0, "off", "flag_not_found",
                steps = listOf("开关 $flagKey 在快照中不存在，返回 off")
            )
        val config = fv.config
        steps += "求值开关 $flagKey（版本 v${fv.version}），默认值=${config.defaultValue}"

        val prereqTraces = mutableListOf<PrereqTrace>()
        for (p in config.prerequisites) {
            if (p.flagKey == flagKey || p.flagKey in chain) {
                val cyclePath = (chain + flagKey + p.flagKey).joinToString(" -> ")
                steps += "前置依赖 ${p.flagKey} 形成环：$cyclePath，求值中止"
                prereqTraces += PrereqTrace(p.flagKey, p.requiredValue, "<环>", false, null)
                return EvaluationTrace(
                    flagKey, fv.version, config.defaultValue, "dependency_cycle",
                    prereqTraces, emptyList(), steps
                )
            }
            steps += "检查前置开关 ${p.flagKey}，要求值=${p.requiredValue}"
            val sub = evaluateInternal(p.flagKey, context, chain + flagKey)
            val passed = sub.result == p.requiredValue
            prereqTraces += PrereqTrace(p.flagKey, p.requiredValue, sub.result, passed, sub)
            steps += if (passed) {
                "前置开关 ${p.flagKey} 求得 ${sub.result}，满足要求"
            } else {
                "前置开关 ${p.flagKey} 求得 ${sub.result}，不满足 ${p.requiredValue}，直接返回默认值 ${config.defaultValue}"
            }
            if (!passed) {
                return EvaluationTrace(
                    flagKey, fv.version, config.defaultValue, "prerequisite_failed",
                    prereqTraces, emptyList(), steps
                )
            }
        }

        val ruleTraces = mutableListOf<RuleTrace>()
        config.rules.forEachIndexed { index, rule ->
            val label = rule.name.ifBlank { rule.id }
            steps += "求值规则 #${index + 1} [$label]（${rule.conditions.size} 个条件）"
            val condTraces = rule.conditions.map { evalCondition(it, context, steps) }
            val matched = condTraces.all { it.passed }
            var rollout: RolloutTrace? = null
            var served: String? = null
            if (matched) {
                if (rule.serve.type == "percentage") {
                    val rt = evalRollout(flagKey, rule.serve, context)
                    rollout = rt
                    served = if (rt.inBucket) rule.serve.value else rule.serve.elseValue
                    steps += "规则 #${index + 1} 条件全部满足，进入分流：bucket=${rt.bucket}，" +
                        "阈值=${Bucketing.threshold(rt.percentage)}，" +
                        (if (rt.inBucket) "命中 ${rt.percentage}% 流量，服务值=$served"
                         else "未命中 ${rt.percentage}% 流量，服务兜底值=$served")
                } else {
                    served = rule.serve.value
                    steps += "规则 #${index + 1} 条件全部满足，服务值=$served"
                }
            } else {
                val failed = condTraces.filter { !it.passed }.joinToString("；") { "${it.field} ${it.op} 失败" }
                steps += "规则 #${index + 1} 未命中（$failed）"
            }
            ruleTraces += RuleTrace(index, rule.id, label, condTraces, matched, rollout, served)
            if (matched) {
                steps += "最终结果：$served（规则 #${index + 1} 命中）"
                return EvaluationTrace(flagKey, fv.version, served!!, "rule_match", prereqTraces, ruleTraces, steps)
            }
        }

        steps += "没有规则命中，返回默认值 ${config.defaultValue}"
        return EvaluationTrace(flagKey, fv.version, config.defaultValue, "default", prereqTraces, ruleTraces, steps)
    }

    private fun fieldState(field: String, context: Map<String, JsonElement>): Pair<FieldState, JsonElement?> {
        if (!context.containsKey(field)) return FieldState("missing", "（字段缺失）") to null
        val value = context.getValue(field)
        if (value is JsonNull) return FieldState("null", "null") to value
        if (field in snapshot.sensitiveFields) {
            return FieldState("sensitive", "摘要:${digester.digest(field, value)}") to value
        }
        return FieldState("value", value.toString()) to value
    }

    private fun evalCondition(
        cond: Condition,
        context: Map<String, JsonElement>,
        steps: MutableList<String>
    ): ConditionTrace {
        val expected = cond.values.map { it.toString() }
        val (state, actual) = fieldState(cond.field, context)
        steps += "读取字段 ${cond.field}：${state.display}"

        if (cond.op == "exists") {
            val passed = state.kind != "missing"
            return ConditionTrace(cond.field, cond.op, expected, state, passed,
                if (passed) "字段存在" else "字段缺失")
        }
        if (cond.op == "notExists") {
            val passed = state.kind == "missing"
            return ConditionTrace(cond.field, cond.op, expected, state, passed,
                if (passed) "字段缺失，符合 notExists" else "字段存在")
        }
        if (state.kind == "missing") {
            return ConditionTrace(cond.field, cond.op, expected, state, false, "字段缺失，条件不成立")
        }
        if (actual is JsonNull) {
            return ConditionTrace(cond.field, cond.op, expected, state, false, "字段为 null，不参与比较")
        }
        if (cond.values.isEmpty()) {
            return ConditionTrace(cond.field, cond.op, expected, state, false, "条件缺少比较值")
        }
        return compare(cond.field, cond.op, actual!!, cond.values, state)
    }

    private fun compare(
        field: String,
        op: String,
        actual: JsonElement,
        expectedValues: List<JsonElement>,
        state: FieldState
    ): ConditionTrace {
        val expected = expectedValues.map { it.toString() }
        fun result(passed: Boolean, note: String) =
            ConditionTrace(field, op, expected, state, passed, note)

        if (op == "in") {
            val hit = expectedValues.any { strictEquals(actual, it) }
            return result(hit, if (hit) "实际值在候选列表中" else "实际值不在候选列表中（严格类型匹配）")
        }
        val expected0 = expectedValues[0]
        if (actual is JsonArray) {
            if (op == "contains") {
                val hit = actual.any { strictEquals(it, expected0) }
                return result(hit, if (hit) "数组包含期望值" else "数组不包含期望值（严格类型匹配）")
            }
            return result(false, "数组类型仅支持 contains 操作")
        }
        if (actual !is JsonPrimitive || expected0 !is JsonPrimitive) {
            return result(false, "仅支持基本类型之间的比较")
        }
        val aNum = actual.numericContent()
        val eNum = expected0.numericContent()
        val aIsBool = actual.isBooleanLike()
        val eIsBool = expected0.isBooleanLike()

        if (!actual.isString && !expected0.isString && aNum == null && eNum == null && (aIsBool || eIsBool)) {
            if (!aIsBool || !eIsBool) return result(false, "类型不匹配：布尔值不与非布尔值比较")
            return when (op) {
                "eq" -> result(actual.content == expected0.content, "布尔相等比较")
                "neq" -> result(actual.content != expected0.content, "布尔不等比较")
                else -> result(false, "布尔值仅支持 eq/neq")
            }
        }
        if ((aNum != null) != (eNum != null)) {
            return result(false, "类型不匹配：数字与字符串不做隐式转换")
        }
        if (aNum != null && eNum != null) {
            val cmp = aNum.compareTo(eNum)
            val passed = when (op) {
                "eq" -> cmp == 0
                "neq" -> cmp != 0
                "lt" -> cmp < 0
                "lte" -> cmp <= 0
                "gt" -> cmp > 0
                "gte" -> cmp >= 0
                else -> return result(false, "数字不支持操作 $op")
            }
            return result(passed, "数字比较 $aNum $op $eNum")
        }
        if (!actual.isString || !expected0.isString) {
            return result(false, "类型不匹配：字符串与数字/布尔不做隐式转换")
        }
        val a = actual.content
        val e = expected0.content
        val passed = when (op) {
            "eq" -> a == e
            "neq" -> a != e
            "lt" -> a < e
            "lte" -> a <= e
            "gt" -> a > e
            "gte" -> a >= e
            "contains" -> a.contains(e)
            "startsWith" -> a.startsWith(e)
            "endsWith" -> a.endsWith(e)
            else -> return result(false, "字符串不支持操作 $op")
        }
        return result(passed, "字符串比较 \"$a\" $op \"$e\"")
    }

    private fun strictEquals(a: JsonElement, b: JsonElement): Boolean {
        if (a is JsonNull || b is JsonNull) return a is JsonNull && b is JsonNull
        if (a is JsonPrimitive && b is JsonPrimitive) {
            if (a.isString != b.isString) return false
            val an = a.numericContent()
            val bn = b.numericContent()
            if (an != null && bn != null) return an.compareTo(bn) == 0
            if (an != null || bn != null) return false
            return a.content == b.content
        }
        return a == b
    }

    private fun evalRollout(
        flagKey: String,
        serve: Serve,
        context: Map<String, JsonElement>
    ): RolloutTrace {
        val (state, actual) = fieldState(serve.identityField, context)
        val identityRaw = when (actual) {
            null, is JsonNull -> ""
            is JsonPrimitive -> actual.content
            else -> actual.toString()
        }
        val config = snapshot.versions.getValue(flagKey).config
        val (bucket, hash) = Bucketing.bucketOf(config.salt, flagKey, identityRaw)
        val threshold = Bucketing.threshold(serve.percentage)
        val shownInput = if (state.kind == "sensitive") {
            "${config.salt}:$flagKey:${state.display}"
        } else {
            "${config.salt}:$flagKey:$identityRaw"
        }
        return RolloutTrace(
            flagKey = flagKey,
            identityField = serve.identityField,
            identity = state,
            salt = config.salt,
            hashInput = shownInput,
            algorithm = Bucketing.ALGORITHM,
            hash = hash,
            bucket = bucket,
            percentage = serve.percentage,
            inBucket = bucket < threshold
        )
    }
}

private fun JsonPrimitive.numericContent(): java.math.BigDecimal? =
    if (isString) null else content.toBigDecimalOrNull()

private fun JsonPrimitive.isBooleanLike(): Boolean =
    !isString && (content == "true" || content == "false")
