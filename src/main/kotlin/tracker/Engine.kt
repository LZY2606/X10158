package tracker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object JsonUtil {
    fun isNumber(e: JsonElement): Boolean {
        if (e !is JsonPrimitive || e.isString) return false
        if (e.booleanOrNull != null) return false
        return try {
            BigDecimal(e.content); true
        } catch (ex: Exception) {
            false
        }
    }

    fun isBoolean(e: JsonElement): Boolean = e is JsonPrimitive && !e.isString && e.booleanOrNull != null

    fun typeName(e: JsonElement?): String = when (e) {
        null -> "缺失"
        JsonNull -> "null"
        is JsonArray -> "数组"
        is JsonObject -> "对象"
        is JsonPrimitive -> when {
            e.isString -> "字符串"
            isBoolean(e) -> "布尔"
            isNumber(e) -> "数字"
            else -> "未知"
        }
        else -> "未知"
    }

    fun strictEquals(a: JsonElement, b: JsonElement): Boolean {
        if (a is JsonNull || b is JsonNull) return a is JsonNull && b is JsonNull
        if (a is JsonPrimitive && b is JsonPrimitive) {
            if (a.isString != b.isString) return false
            if (a.isString) return a.content == b.content
            if (isBoolean(a) || isBoolean(b)) return a.booleanOrNull == b.booleanOrNull
            if (isNumber(a) && isNumber(b)) {
                return BigDecimal(a.content).compareTo(BigDecimal(b.content)) == 0
            }
            return a.content == b.content
        }
        if (a is JsonArray && b is JsonArray) {
            return a.size == b.size && a.zip(b).all { (x, y) -> strictEquals(x, y) }
        }
        if (a is JsonObject && b is JsonObject) {
            return a.keys == b.keys && a.keys.all { strictEquals(a.getValue(it), b.getValue(it)) }
        }
        return false
    }

    fun canonical(e: JsonElement): String = when (e) {
        is JsonNull -> "null"
        is JsonPrimitive -> {
            if (e.isString) "\"" + e.content.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            else if (isNumber(e)) BigDecimal(e.content).stripTrailingZeros().toPlainString()
            else e.content
        }
        is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
        is JsonObject -> e.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { (k, v) -> canonical(JsonPrimitive(k)) + ":" + canonical(v) }
        else -> e.toString()
    }
}

object Murmur3 {
    fun hash32(data: ByteArray, seed: Int = 0): Int {
        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593
        var h1 = seed
        val len = data.size
        var i = 0
        while (i + 4 <= len) {
            var k1 = (data[i].toInt() and 0xff) or
                ((data[i + 1].toInt() and 0xff) shl 8) or
                ((data[i + 2].toInt() and 0xff) shl 16) or
                ((data[i + 3].toInt() and 0xff) shl 24)
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
            h1 = Integer.rotateLeft(h1, 13)
            h1 = h1 * 5 + 0xe6546b64.toInt()
            i += 4
        }
        var k1 = 0
        when (len and 3) {
            3 -> {
                k1 = k1 xor ((data[i + 2].toInt() and 0xff) shl 16)
                k1 = k1 xor ((data[i + 1].toInt() and 0xff) shl 8)
                k1 = k1 xor (data[i].toInt() and 0xff)
            }
            2 -> {
                k1 = k1 xor ((data[i + 1].toInt() and 0xff) shl 8)
                k1 = k1 xor (data[i].toInt() and 0xff)
            }
            1 -> {
                k1 = k1 xor (data[i].toInt() and 0xff)
            }
        }
        if (len and 3 != 0) {
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
        }
        h1 = h1 xor len
        h1 = h1 xor (h1 ushr 16)
        h1 *= 0x85ebca6b.toInt()
        h1 = h1 xor (h1 ushr 13)
        h1 *= 0xc2b2ae35.toInt()
        h1 = h1 xor (h1 ushr 16)
        return h1
    }
}

object Bucketing {
    const val ALGORITHM = "murmur3_32"

    fun bucketPercent(flagKey: String, identity: String, salt: String): Pair<String, Pair<Long, Double>> {
        val input = "$flagKey:$identity:$salt"
        val hash = Murmur3.hash32(input.toByteArray(Charsets.UTF_8))
        val unsigned = hash.toLong() and 0xffffffffL
        val percent = (unsigned % 10000) / 100.0
        return input to (unsigned to percent)
    }
}

object SensitiveDigest {
    const val DOMAIN = "feature-flag-tracer/sensitive-field/v1"

    fun digest(secret: String, field: String, value: JsonElement): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(DOMAIN.toByteArray(Charsets.UTF_8))
        mac.update(0)
        mac.update(field.toByteArray(Charsets.UTF_8))
        mac.update(0)
        mac.update(JsonUtil.canonical(value).toByteArray(Charsets.UTF_8))
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }
}

class Snapshot(val versions: Map<String, FlagVersion>)

class Evaluator(
    private val snapshot: Snapshot,
    private val settings: ProjectSettings,
) {
    fun evaluate(flagKey: String, context: JsonObject): TraceNode = eval(flagKey, context, emptyList())

    private fun eval(flagKey: String, context: JsonObject, stack: List<String>): TraceNode {
        val version = snapshot.versions[flagKey]
        if (version == null) {
            return TraceNode(flagKey, "-", emptyList(), JsonNull, "开关不存在")
        }
        val doc = version.doc
        if (flagKey in stack) {
            val cycle = (stack.dropWhile { it != flagKey } + flagKey).joinToString(" -> ")
            return TraceNode(flagKey, version.versionId, emptyList(), doc.defaultValue, "检测到依赖环: $cycle，返回默认值")
        }
        val steps = mutableListOf<TraceStep>()

        for (prereq in doc.prerequisites) {
            val child = eval(prereq.flagKey, context, stack + flagKey)
            val met = JsonUtil.strictEquals(child.result, prereq.value)
            steps += TraceStep(
                type = "prereq",
                message = if (met) "前置开关 ${prereq.flagKey} 满足" else "前置开关 ${prereq.flagKey} 不满足",
                prereq = PrereqTrace(prereq.flagKey, prereq.value, child.result, met, child),
            )
            if (!met) {
                return TraceNode(
                    flagKey, version.versionId, steps, doc.defaultValue,
                    "前置开关 ${prereq.flagKey} 未满足（期望 ${JsonUtil.canonical(prereq.value)}，实际 ${JsonUtil.canonical(child.result)}），返回默认值",
                )
            }
        }

        for (rule in doc.rules) {
            val condTraces = rule.conditions.map { evalCondition(it, context) }
            var matched = condTraces.all { it.passed }
            var bucketTrace: BucketTrace? = null
            var served: JsonElement? = null
            var note = ""
            if (matched) {
                val rollout = rule.rollout
                if (rollout == null) {
                    served = rule.serve
                } else {
                    val identity = readIdentity(context)
                    if (identity == null) {
                        matched = false
                        note = "上下文缺少稳定身份字段 key，无法分桶，规则视为未命中"
                    } else {
                        val (input, hp) = Bucketing.bucketPercent(doc.key, identity, doc.salt)
                        val (hash, percent) = hp
                        var cumulative = 0.0
                        var chosen = -1
                        val ranges = mutableListOf<String>()
                        for ((idx, v) in rollout.withIndex()) {
                            val from = cumulative
                            cumulative += v.weight
                            ranges += "#$idx [${fmt(from)}, ${fmt(cumulative)}) -> ${JsonUtil.canonical(v.value)}"
                            if (chosen < 0 && percent >= from && percent < cumulative) chosen = idx
                        }
                        bucketTrace = BucketTrace(Bucketing.ALGORITHM, input, hash, percent, chosen, ranges)
                        if (chosen < 0) {
                            matched = false
                            note = "分桶 ${fmt(percent)}% 未落入任何变体区间（权重合计 ${fmt(cumulative)}%），规则视为未命中"
                        } else {
                            served = rollout[chosen].value
                            note = "分桶 ${fmt(percent)}% 命中变体 #$chosen"
                        }
                    }
                }
            }
            steps += TraceStep(
                type = "rule",
                message = if (matched) "规则 ${ruleLabel(rule)} 命中" else "规则 ${ruleLabel(rule)} 未命中",
                rule = RuleTrace(rule.id, rule.name, matched, condTraces, served, bucketTrace, note),
            )
            if (matched) {
                return TraceNode(flagKey, version.versionId, steps, served ?: JsonNull, "命中规则 ${ruleLabel(rule)}")
            }
        }

        steps += TraceStep(type = "default", message = "无规则命中，返回默认值 ${JsonUtil.canonical(doc.defaultValue)}")
        return TraceNode(flagKey, version.versionId, steps, doc.defaultValue, "无规则命中，返回默认值")
    }

    private fun ruleLabel(rule: Rule): String = if (rule.name.isNotEmpty()) "${rule.name}(${rule.id})" else rule.id

    private fun readIdentity(context: JsonObject): String? {
        val key = context["key"] ?: return null
        if (key is JsonPrimitive) {
            if (key.isString) return key.content
            if (JsonUtil.isNumber(key)) return JsonUtil.canonical(key)
        }
        return null
    }

    private fun fmt(d: Double): String = BigDecimal(d).stripTrailingZeros().toPlainString()

    private fun readField(context: JsonObject, field: String): FieldRead {
        val sensitive = field in settings.sensitiveFields
        if (!context.containsKey(field)) {
            return FieldRead(field, "missing", sensitive = sensitive)
        }
        val value = context.getValue(field)
        if (value is JsonNull) {
            return FieldRead(field, "null", sensitive = sensitive)
        }
        return if (sensitive) {
            FieldRead(field, "value", digest = SensitiveDigest.digest(settings.secret, field, value), sensitive = true)
        } else {
            FieldRead(field, "value", value = value)
        }
    }

    private fun evalCondition(cond: Condition, context: JsonObject): ConditionTrace {
        val read = readField(context, cond.field)
        if (cond.op == "exists") {
            val passed = read.status != "missing"
            return ConditionTrace(cond, read, passed, if (passed) "字段存在" else "字段缺失")
        }
        if (read.status == "missing") {
            return ConditionTrace(cond, read, false, "字段缺失（与 null 区分处理），条件失败")
        }
        if (read.status == "null") {
            return ConditionTrace(cond, read, false, "字段值为 null（与缺失区分处理），条件失败")
        }
        val actual = context.getValue(cond.field)
        val expected = cond.value
        val notePrefix = "上下文类型 ${JsonUtil.typeName(actual)}，条件类型 ${JsonUtil.typeName(expected)}"
        return when (cond.op) {
            "eq" -> ConditionTrace(cond, read, JsonUtil.strictEquals(actual, expected), "严格相等比较（$notePrefix）")
            "neq" -> ConditionTrace(cond, read, !JsonUtil.strictEquals(actual, expected), "严格不等比较（$notePrefix）")
            "lt", "lte", "gt", "gte" -> compareOp(cond, read, actual, expected, notePrefix)
            "in" -> {
                if (expected is JsonArray) {
                    val hit = expected.any { JsonUtil.strictEquals(actual, it) }
                    ConditionTrace(cond, read, hit, "在列表中严格匹配（$notePrefix）")
                } else {
                    ConditionTrace(cond, read, false, "in 的条件值必须是数组")
                }
            }
            "contains" -> when {
                actual is JsonArray -> ConditionTrace(cond, read, actual.any { JsonUtil.strictEquals(it, expected) }, "数组包含严格匹配")
                actual is JsonPrimitive && actual.isString && expected is JsonPrimitive && expected.isString ->
                    ConditionTrace(cond, read, actual.content.contains(expected.content), "字符串包含")
                else -> ConditionTrace(cond, read, false, "contains 需要数组或字符串（$notePrefix），不做隐式转换")
            }
            "startsWith", "endsWith" -> {
                if (actual is JsonPrimitive && actual.isString && expected is JsonPrimitive && expected.isString) {
                    val ok = if (cond.op == "startsWith") actual.content.startsWith(expected.content) else actual.content.endsWith(expected.content)
                    ConditionTrace(cond, read, ok, "字符串${if (cond.op == "startsWith") "前缀" else "后缀"}比较")
                } else {
                    ConditionTrace(cond, read, false, "${cond.op} 需要双方都是字符串（$notePrefix），不做隐式转换")
                }
            }
            "matches" -> {
                if (actual is JsonPrimitive && actual.isString && expected is JsonPrimitive && expected.isString) {
                    val ok = try {
                        Regex(expected.content).containsMatchIn(actual.content)
                    } catch (e: Exception) {
                        return ConditionTrace(cond, read, false, "正则无效: ${e.message}")
                    }
                    ConditionTrace(cond, read, ok, "正则匹配")
                } else {
                    ConditionTrace(cond, read, false, "matches 需要双方都是字符串（$notePrefix）")
                }
            }
            else -> ConditionTrace(cond, read, false, "未知操作符 ${cond.op}")
        }
    }

    private fun compareOp(cond: Condition, read: FieldRead, actual: JsonElement, expected: JsonElement, notePrefix: String): ConditionTrace {
        val cmp: Int? = when {
            JsonUtil.isNumber(actual) && JsonUtil.isNumber(expected) ->
                BigDecimal((actual as JsonPrimitive).content).compareTo(BigDecimal((expected as JsonPrimitive).content))
            actual is JsonPrimitive && actual.isString && expected is JsonPrimitive && expected.isString ->
                actual.content.compareTo(expected.content)
            else -> null
        }
        if (cmp == null) {
            return ConditionTrace(cond, read, false, "大小比较需要双方同为数字或同为字符串（$notePrefix），不做隐式转换")
        }
        val ok = when (cond.op) {
            "lt" -> cmp < 0
            "lte" -> cmp <= 0
            "gt" -> cmp > 0
            "gte" -> cmp >= 0
            else -> false
        }
        return ConditionTrace(cond, read, ok, "大小比较（$notePrefix）")
    }
}
