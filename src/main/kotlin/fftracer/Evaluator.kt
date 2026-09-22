package fftracer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

private fun JsonElement.isNumeric(): Boolean =
    this is JsonPrimitive && !isString && doubleOrNull != null

private fun JsonElement.asDouble(): Double = (this as JsonPrimitive).doubleOrNull!!

private fun JsonElement.isBool(): Boolean =
    this is JsonPrimitive && !isString && booleanOrNull != null

/** Strict equality: no implicit string<->number coercion. */
fun strictEquals(a: JsonElement, b: JsonElement): Boolean = when {
    a is JsonNull && b is JsonNull -> true
    a is JsonNull || b is JsonNull -> false
    a.isNumeric() && b.isNumeric() -> a.asDouble() == b.asDouble()
    a.isBool() && b.isBool() ->
        (a as JsonPrimitive).booleanOrNull == (b as JsonPrimitive).booleanOrNull
    a is JsonPrimitive && a.isString && b is JsonPrimitive && b.isString -> a.content == b.content
    else -> canonical(a) == canonical(b) && a::class == b::class
}

class Evaluator(
    private val projectSecret: String,
    private val snapshot: Map<String, Flag>,
    private val sensitive: Set<String>,
) {
    private fun display(attribute: String, value: JsonElement): JsonElement =
        if (attribute in sensitive) JsonPrimitive(Digest.of(projectSecret, attribute, value))
        else value

    fun evaluate(flagKey: String, ctx: EvalContext, version: Int? = null, depth: Int = 0): EvalResult {
        val flag = snapshot[flagKey]
            ?: return EvalResult(JsonNull, "FLAG_NOT_FOUND", flagKey, -1,
                listOf(TraceStep("flag_lookup", "开关 $flagKey 不存在", result = "fail")))
        val v = flag.version(version ?: flag.currentVersion)
            ?: return EvalResult(JsonNull, "VERSION_NOT_FOUND", flagKey, version ?: -1,
                listOf(TraceStep("version_lookup", "版本不存在", result = "fail")))
        val trace = mutableListOf<TraceStep>()
        trace += TraceStep(
            "flag_lookup", "开关 $flagKey 使用版本 v${v.version}" +
                (if (version != null) "（显式指定）" else "（当前版本）"),
            mapOf("salt" to JsonPrimitive(flag.salt)),
        )

        if (depth > 16) {
            return EvalResult(v.defaultValue, "PREREQUISITE_DEPTH_LIMIT", flagKey, v.version, trace)
        }

        for (pre in v.prerequisites) {
            val sub = evaluate(pre.flagKey, ctx, null, depth + 1)
            val ok = strictEquals(sub.value, pre.expect)
            trace += TraceStep(
                "prerequisite",
                "前置开关 ${pre.flagKey} 期望 ${canonical(pre.expect)}，实际 ${canonical(sub.value)}",
                mapOf(
                    "flagKey" to JsonPrimitive(pre.flagKey),
                    "expect" to pre.expect,
                    "actual" to sub.value,
                ),
                result = if (ok) "pass" else "fail",
                children = sub.trace,
            )
            if (!ok) {
                trace += TraceStep("final", "前置未满足，返回默认值 ${canonical(v.defaultValue)}")
                return EvalResult(v.defaultValue, "PREREQUISITE_FAILED", flagKey, v.version, trace)
            }
        }

        for ((index, rule) in v.rules.withIndex()) {
            val steps = mutableListOf<TraceStep>()
            var matched = true
            for (cond in rule.conditions) {
                val step = evalCondition(cond, ctx)
                steps += step
                if (step.result != "pass") { matched = false; break }
            }
            if (matched && rule.rollout != null) {
                val step = evalRollout(rule.rollout, flag, ctx)
                steps += step
                if (step.result != "pass") matched = false
            }
            val label = "规则 ${index + 1}" + (if (rule.name.isNotEmpty()) "（${rule.name}）" else "")
            trace += TraceStep(
                "rule", if (matched) "$label 命中" else "$label 未命中",
                mapOf("ruleId" to JsonPrimitive(rule.id)),
                result = if (matched) "pass" else "fail",
                children = steps,
            )
            if (matched) {
                trace += TraceStep("final", "返回规则值 ${canonical(rule.serve)}")
                return EvalResult(rule.serve, "RULE_MATCH", flagKey, v.version, trace)
            }
        }

        trace += TraceStep("final", "无规则命中，返回默认值 ${canonical(v.defaultValue)}")
        return EvalResult(v.defaultValue, "DEFAULT", flagKey, v.version, trace)
    }

    private fun evalCondition(cond: Condition, ctx: EvalContext): TraceStep {
        val present = ctx.attributes.containsKey(cond.attribute)
        if (!present) {
            return TraceStep(
                "condition", "字段 ${cond.attribute} 缺失（missing），条件失败",
                mapOf("attribute" to JsonPrimitive(cond.attribute), "state" to JsonPrimitive("missing")),
                result = "fail",
            )
        }
        val raw = ctx.attributes.getValue(cond.attribute)
        val shown = display(cond.attribute, raw)
        val detail = mutableMapOf<String, JsonElement>(
            "attribute" to JsonPrimitive(cond.attribute),
            "op" to JsonPrimitive(cond.op.name),
            "actual" to shown,
        )
        if (cond.attribute in sensitive) detail["sensitive"] = JsonPrimitive(true)

        if (raw is JsonNull) {
            val pass = cond.op == Op.EQ && cond.value is JsonNull
            return TraceStep(
                "condition",
                "字段 ${cond.attribute} 为 null（与缺失区分），${cond.op} " + if (pass) "通过" else "失败",
                detail + ("state" to JsonPrimitive("null")),
                result = if (pass) "pass" else "fail",
            )
        }

        val (pass, note) = compare(cond, raw)
        return TraceStep(
            "condition",
            "字段 ${cond.attribute}=${canonical(shown)} ${cond.op} ${canonical(cond.value)}：$note",
            detail, result = if (pass) "pass" else "fail",
        )
    }

    /** Returns pass/fail plus a human note; never coerces string<->number. */
    private fun compare(cond: Condition, actual: JsonElement): Pair<Boolean, String> {
        val expected = cond.value
        fun typeNote(): String =
            "类型不一致（实际 ${typeName(actual)}，期望 ${typeName(expected)}），不做隐式转换"
        return when (cond.op) {
            Op.EQ -> strictEquals(actual, expected) to
                if (strictEquals(actual, expected)) "相等" else "不相等"
            Op.NEQ -> (!strictEquals(actual, expected)) to
                if (!strictEquals(actual, expected)) "不相等" else "相等"
            Op.GT, Op.GTE, Op.LT, Op.LTE -> {
                when {
                    actual.isNumeric() && expected.isNumeric() -> {
                        val a = actual.asDouble(); val b = expected.asDouble()
                        val r = when (cond.op) {
                            Op.GT -> a > b; Op.GTE -> a >= b; Op.LT -> a < b; else -> a <= b
                        }
                        r to "数值比较 $a ${cond.op} $b"
                    }
                    actual is JsonPrimitive && actual.isString &&
                        expected is JsonPrimitive && expected.isString -> {
                        val c = actual.content.compareTo(expected.content)
                        val r = when (cond.op) {
                            Op.GT -> c > 0; Op.GTE -> c >= 0; Op.LT -> c < 0; else -> c <= 0
                        }
                        r to "字符串比较"
                    }
                    else -> false to typeNote()
                }
            }
            Op.IN -> (cond.values.any { strictEquals(actual, it) }) to "IN 列表严格匹配"
            Op.CONTAINS, Op.STARTS_WITH, Op.ENDS_WITH -> {
                if (actual is JsonPrimitive && actual.isString &&
                    expected is JsonPrimitive && expected.isString) {
                    val r = when (cond.op) {
                        Op.CONTAINS -> actual.content.contains(expected.content)
                        Op.STARTS_WITH -> actual.content.startsWith(expected.content)
                        else -> actual.content.endsWith(expected.content)
                    }
                    r to "字符串${cond.op}"
                } else if (actual is JsonArray) {
                    if (cond.op == Op.CONTAINS)
                        (actual.any { strictEquals(it, expected) }) to "数组包含（严格相等）"
                    else false to "数组仅支持 CONTAINS"
                } else false to typeNote()
            }
        }
    }

    private fun typeName(e: JsonElement): String = when {
        e is JsonNull -> "null"
        e.isNumeric() -> "number"
        e.isBool() -> "boolean"
        e is JsonPrimitive && e.isString -> "string"
        e is JsonArray -> "array"
        else -> "object"
    }

    private fun evalRollout(rollout: Rollout, flag: Flag, ctx: EvalContext): TraceStep {
        val attr = rollout.identityAttribute
        if (!ctx.attributes.containsKey(attr)) {
            return TraceStep(
                "rollout", "分流身份字段 $attr 缺失，规则不命中",
                mapOf("identityAttribute" to JsonPrimitive(attr), "state" to JsonPrimitive("missing")),
                result = "fail",
            )
        }
        val raw = ctx.attributes.getValue(attr)
        if (raw is JsonNull || raw !is JsonPrimitive) {
            return TraceStep(
                "rollout", "分流身份字段 $attr 为 null 或非标量，规则不命中",
                mapOf("identityAttribute" to JsonPrimitive(attr), "state" to JsonPrimitive("null")),
                result = "fail",
            )
        }
        val identity = raw.content
        val bucket = Bucketing.bucket(flag.key, flag.salt, identity)
        val threshold = rollout.percent * 1000.0
        val pass = bucket < threshold
        return TraceStep(
            "rollout",
            "分桶 murmur3(${flag.key}.${flag.salt}.身份) = $bucket，阈值 $threshold（${rollout.percent}%），" +
                if (pass) "落入" else "未落入",
            mapOf(
                "algorithm" to JsonPrimitive("murmur3_x86_32"),
                "flagKey" to JsonPrimitive(flag.key),
                "salt" to JsonPrimitive(flag.salt),
                "identityAttribute" to JsonPrimitive(attr),
                "identity" to display(attr, raw),
                "bucket" to JsonPrimitive(bucket),
                "threshold" to JsonPrimitive(threshold),
            ),
            result = if (pass) "pass" else "fail",
        )
    }
}
