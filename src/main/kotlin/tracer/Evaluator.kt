package tracer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

data class EvalOutcome(val value: JsonElement, val trace: JsonObject)

class Evaluator(
    private val projectId: String,
    private val versionOf: (String) -> FlagVersion?,
    private val sensitiveFieldsOf: (String) -> Set<String>,
) {
    fun evaluate(flagKey: String, context: JsonObject): EvalOutcome {
        val root = TraceBuilder("flag", projectId)
        root.attr("flagKey", flagKey)
        val value = evalFlag(flagKey, context, root, emptyList())
        return EvalOutcome(value, root.build())
    }

    private fun evalFlag(
        flagKey: String,
        context: JsonObject,
        node: TraceBuilder,
        stack: List<String>,
    ): JsonElement {
        if (flagKey in stack) {
            val cyclePath = (stack.dropWhile { it != flagKey } + flagKey)
            node.attr("cycle", cyclePath.joinToString(" -> "))
            node.attr("error", "检测到前置开关依赖环")
            node.attr("outcome", "error")
            throw ApiException(409, "检测到前置开关依赖环: ${cyclePath.joinToString(" -> ")}")
        }
        val version = versionOf(flagKey)
        if (version == null) {
            node.attr("error", "开关不存在或没有已发布版本")
            node.attr("outcome", "error")
            throw ApiException(404, "开关 $flagKey 不存在或没有已发布版本")
        }
        node.attr("version", version.version)
        node.attr("flagKey", flagKey)
        val sensitive = sensitiveFieldsOf(flagKey)

        val prereqNodes = mutableListOf<JsonObject>()
        var prereqFailed: JsonObject? = null
        for (depKey in version.prerequisites) {
            val depNode = TraceBuilder("prerequisite", projectId)
            depNode.attr("flagKey", depKey)
            val depVersion = versionOf(depKey)
            if (depVersion == null) {
                depNode.attr("error", "前置开关不存在或没有已发布版本")
                depNode.attr("required", true)
                depNode.attr("passed", false)
                prereqFailed = depNode.build()
                prereqNodes += depNode.build()
                break
            }
            val depValue = evalFlag(depKey, context, depNode, stack + flagKey)
            val passed = (depValue as? JsonPrimitive)?.booleanOrNull == true
            depNode.attr("required", true)
            depNode.attr("passed", passed)
            val built = depNode.build()
            prereqNodes += built
            if (!passed) {
                prereqFailed = built
                break
            }
        }
        node.children("prerequisites", prereqNodes)

        if (prereqFailed != null) {
            node.attr("outcome", "prerequisite_blocked")
            node.attr("blockedBy", prereqFailed["flagKey"]!!.let { (it as JsonPrimitive).content })
            node.attr("value", false)
            return JsonPrimitive(false)
        }

        val ruleNodes = mutableListOf<JsonObject>()
        for (rule in version.rules) {
            val ruleNode = TraceBuilder("rule", projectId)
            ruleNode.attr("ruleId", rule.id)
            val clauseNodes = mutableListOf<JsonObject>()
            var matchedValue: JsonElement? = null
            for (clause in rule.clauses) {
                val clauseNode = TraceBuilder("clause", projectId)
                clauseNode.attr("clauseId", clause.id)
                val condNodes = mutableListOf<JsonObject>()
                var clauseOk = true
                for (cond in clause.conditions) {
                    val condNode = evalCondition(cond, context, sensitive)
                    condNodes += condNode
                    if ((condNode["matched"] as? JsonPrimitive)?.booleanOrNull != true) {
                        clauseOk = false
                    }
                }
                clauseNode.children("conditions", condNodes)
                var bucketNode: JsonObject? = null
                if (clauseOk && clause.rollout.enabled) {
                    val bucket = evalRollout(flagKey, clause, context, sensitive)
                    bucketNode = bucket
                    if ((bucket["included"] as? JsonPrimitive)?.booleanOrNull != true) {
                        clauseOk = false
                    }
                }
                if (bucketNode != null) clauseNode.child("rollout", bucketNode)
                clauseNode.attr("matched", clauseOk)
                if (clauseOk) clauseNode.attr("value", clause.value)
                clauseNodes += clauseNode.build()
                if (clauseOk) {
                    matchedValue = clause.value
                    break
                }
            }
            ruleNode.children("clauses", clauseNodes)
            if (matchedValue != null) {
                ruleNode.attr("matched", true)
                ruleNode.attr("value", matchedValue)
                ruleNodes += ruleNode.build()
                node.children("rules", ruleNodes)
                node.attr("outcome", "rule_matched")
                node.attr("value", matchedValue)
                return matchedValue
            }
            ruleNode.attr("matched", false)
            ruleNodes += ruleNode.build()
        }
        node.children("rules", ruleNodes)
        node.attr("outcome", "default")
        node.attr("value", version.defaultValue)
        return version.defaultValue
    }

    private fun evalCondition(
        cond: Condition,
        context: JsonObject,
        sensitive: Set<String>,
    ): JsonObject {
        val node = TraceBuilder("condition", projectId)
        node.attr("field", cond.field)
        node.attr("op", cond.op.name)
        val isSensitive = cond.field in sensitive
        node.attr("sensitive", isSensitive)
        node.attr("expected", maskValue(cond.field, cond.value, sensitive))
        val raw = context[cond.field]
        when {
            raw == null -> {
                node.attr("status", "missing")
                node.attr("matched", false)
                node.attr("reason", "上下文字段缺失")
            }
            raw is JsonNull -> {
                node.attr("status", "null")
                node.attr("actual", JsonNull)
                node.attr("matched", false)
                node.attr("reason", "上下文字段为 null")
            }
            else -> {
                node.attr("status", "present")
                node.attr("actual", maskValue(cond.field, raw, sensitive))
                val result = compare(cond.op, raw, cond.value)
                node.attr("matched", result.matched)
                if (result.reason != null) node.attr("reason", result.reason)
            }
        }
        return node.build()
    }

    private data class CompareResult(val matched: Boolean, val reason: String? = null)

    private fun compare(op: Op, actual: JsonElement, expected: JsonElement): CompareResult {
        val a = actual as? JsonPrimitive ?: return CompareResult(false, "上下文值不是基本类型")
        val e = expected as? JsonPrimitive ?: return CompareResult(false, "条件期望值不是基本类型")
        if (op == Op.IN) {
            val arr = expected as? JsonArray ?: return CompareResult(false, "IN 条件的期望值必须是数组")
            val matched = arr.any { item -> compare(Op.EQ, actual, item).matched }
            return CompareResult(matched, if (matched) null else "值不在列表中")
        }
        if (a.isString != e.isString) {
            return CompareResult(false, "类型不匹配：字符串与数字/布尔不做隐式转换")
        }
        if (a.isString) {
            val av = a.content
            val ev = e.content
            return when (op) {
                Op.EQ -> CompareResult(av == ev, if (av == ev) null else "字符串不相等")
                Op.NEQ -> CompareResult(av != ev, if (av != ev) null else "字符串相等")
                Op.CONTAINS -> CompareResult(av.contains(ev))
                Op.STARTS_WITH -> CompareResult(av.startsWith(ev))
                Op.ENDS_WITH -> CompareResult(av.endsWith(ev))
                else -> CompareResult(false, "字符串不支持比较运算符 ${op.name}")
            }
        }
        val ab = a.booleanOrNull
        val eb = e.booleanOrNull
        if (ab != null || eb != null) {
            if (ab == null || eb == null) return CompareResult(false, "类型不匹配：布尔与其它类型不做隐式转换")
            return when (op) {
                Op.EQ -> CompareResult(ab == eb)
                Op.NEQ -> CompareResult(ab != eb)
                else -> CompareResult(false, "布尔值不支持运算符 ${op.name}")
            }
        }
        val ad = a.doubleOrNull
        val ed = e.doubleOrNull
        if (ad == null || ed == null) {
            return CompareResult(false, "类型不匹配：无法按数字比较")
        }
        return when (op) {
            Op.EQ -> CompareResult(ad == ed)
            Op.NEQ -> CompareResult(ad != ed)
            Op.LT -> CompareResult(ad < ed)
            Op.LTE -> CompareResult(ad <= ed)
            Op.GT -> CompareResult(ad > ed)
            Op.GTE -> CompareResult(ad >= ed)
            else -> CompareResult(false, "数字不支持运算符 ${op.name}")
        }
    }

    private fun evalRollout(
        flagKey: String,
        clause: Clause,
        context: JsonObject,
        sensitive: Set<String>,
    ): JsonObject {
        val rollout = clause.rollout
        val node = TraceBuilder("rollout", projectId)
        node.attr("percentage", rollout.percentage)
        node.attr("identityField", rollout.identityField)
        node.attr("salt", rollout.salt)
        node.attr("algorithm", "sha256(flagKey|salt|identity)[0:8] % 100")
        val identity = context.getOrNull(rollout.identityField)
        if (identity == null || identity !is JsonPrimitive || !identity.isString && identity.doubleOrNull == null) {
            node.attr("included", false)
            node.attr("reason", "上下文缺少稳定身份字段 ${rollout.identityField}")
            return node.build()
        }
        node.attr("identity", maskValue(rollout.identityField, identity, sensitive))
        val identityText = canonicalJson(identity)
        val hashInput = "$flagKey|${rollout.salt}|$identityText"
        val bucket = bucketOf(flagKey, rollout.salt, identityText)
        node.attr("hashInput", if (rollout.identityField in sensitive) "sha256 域隔离摘要(见 identity)" else hashInput)
        node.attr("bucket", bucket)
        val included = bucket < rollout.percentage
        node.attr("included", included)
        if (!included) node.attr("reason", "分桶 $bucket 不在 0..${rollout.percentage - 1} 范围内")
        return node.build()
    }

    private fun maskValue(field: String, value: JsonElement, sensitive: Set<String>): JsonElement {
        if (field !in sensitive) return value
        val digest = sha256Hex("$projectId|$field|${canonicalJson(value)}")
        return buildJsonObject {
            put("digest", digest)
            put("digestAlg", "sha256(projectId|field|canonical(value))")
        }
    }
}

fun bucketOf(flagKey: String, salt: String, identityCanonical: String): Int {
    val hex = sha256Hex("$flagKey|$salt|$identityCanonical")
    return (hex.substring(0, 8).toLong(16) % 100).toInt()
}

class TraceBuilder(private val kind: String, private val projectId: String) {
    private val obj = mutableMapOf<String, JsonElement>()
    private val childLists = mutableMapOf<String, MutableList<JsonObject>>()

    init {
        obj["kind"] = JsonPrimitive(kind)
    }

    fun attr(key: String, value: String) { obj[key] = JsonPrimitive(value) }
    fun attr(key: String, value: Int) { obj[key] = JsonPrimitive(value) }
    fun attr(key: String, value: Boolean) { obj[key] = JsonPrimitive(value) }
    fun attr(key: String, value: JsonElement) { obj[key] = value }

    fun child(key: String, child: JsonObject) {
        childLists.getOrPut(key) { mutableListOf() }.add(child)
    }

    fun children(key: String, children: List<JsonObject>) {
        childLists[key] = children.toMutableList()
    }

    fun build(): JsonObject = buildJsonObject {
        for ((k, v) in obj) put(k, v)
        for ((k, list) in childLists) put(k, JsonArray(list))
    }
}
