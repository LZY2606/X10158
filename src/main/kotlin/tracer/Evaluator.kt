package tracer

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ApiException(val status: Int, message: String) : RuntimeException(message)

class CycleException(val path: List<String>) :
    RuntimeException("检测到前置依赖环: ${path.joinToString(" → ")}")

/** 一次批量求值共享的不可变快照：发布新版本不会改变已捕获的快照。 */
data class Snapshot(val flags: Map<String, Pair<Flag, FlagVersion>>)

data class EvalOutcome(val flagKey: String, val version: Int, val result: String, val trace: TraceNode)

class Evaluator(private val project: Project) {

    /** 捕获当前（或指定版本）的开关快照。 */
    fun snapshot(versionOverrides: Map<String, Int> = emptyMap()): Snapshot {
        val map = LinkedHashMap<String, Pair<Flag, FlagVersion>>()
        for ((key, flag) in project.flags) {
            val v = flag.version(versionOverrides[key])
                ?: throw ApiException(404, "开关 $key 不存在版本 ${versionOverrides[key] ?: "(最新)"}")
            map[key] = flag to v
        }
        return Snapshot(map)
    }

    fun evaluate(flagKey: String, context: JsonNode, snapshot: Snapshot): EvalOutcome {
        val visited = mutableListOf<String>()
        val (result, trace, version) = eval(flagKey, context, snapshot, visited)
        return EvalOutcome(flagKey, version, result, trace)
    }

    private fun eval(
        flagKey: String,
        context: JsonNode,
        snapshot: Snapshot,
        visited: MutableList<String>,
    ): Triple<String, TraceNode, Int> {
        if (flagKey in visited) {
            throw CycleException(visited.drop(visited.indexOf(flagKey)) + flagKey)
        }
        visited.add(flagKey)
        val (flag, version) = snapshot.flags[flagKey]
            ?: throw ApiException(404, "开关 $flagKey 不在快照中")
        val root = TraceNode("flag", "求值开关 $flagKey（版本 v${version.version}）")
        root.data["flagKey"] = flagKey
        root.data["version"] = version.version

        // 1. 前置依赖
        for (pr in version.prerequisites) {
            val (prResult, prTrace, _) = eval(pr.flagKey, context, snapshot, visited)
            val pass = prResult == pr.expectedVariant
            val node = TraceNode(
                "prerequisite",
                "前置 ${pr.flagKey}：期望 ${pr.expectedVariant}，实际 $prResult → ${if (pass) "满足" else "不满足"}",
            )
            node.data["expected"] = pr.expectedVariant
            node.data["actual"] = prResult
            node.data["pass"] = pass
            node.children.add(prTrace)
            root.children.add(node)
            if (!pass) {
                return finish(root, flagKey, version.version, version.defaultVariant,
                    "前置 ${pr.flagKey} 未满足，短路返回默认值 ${version.defaultVariant}")
            }
        }

        // 2. 按顺序求值规则
        for ((index, rule) in version.rules.withIndex()) {
            val ruleNode = TraceNode("rule", "规则 ${index + 1}「${rule.name.ifBlank { rule.id }}」→ 命中则返回 ${rule.serveVariant}")
            ruleNode.data["ruleId"] = rule.id
            root.children.add(ruleNode)
            var ruleOk = true

            for (cond in rule.conditions) {
                val (ok, condNode) = evalCondition(cond, context)
                ruleNode.children.add(condNode)
                if (!ok) ruleOk = false
            }

            if (ruleOk && rule.rollout != null) {
                val (ok, bucketNode) = evalRollout(flag, rule.rollout, context)
                ruleNode.children.add(bucketNode)
                if (!ok) ruleOk = false
            }

            ruleNode.data["matched"] = ruleOk
            ruleNode.message += if (ruleOk) " ✓ 命中" else " ✗ 未命中"
            if (ruleOk) {
                return finish(root, flagKey, version.version, rule.serveVariant,
                    "规则 ${index + 1} 命中，返回 ${rule.serveVariant}")
            }
        }

        // 3. 默认值
        return finish(root, flagKey, version.version, version.defaultVariant,
            "无规则命中，返回默认值 ${version.defaultVariant}")
    }

    private fun finish(
        root: TraceNode, flagKey: String, version: Int, result: String, reason: String,
    ): Triple<String, TraceNode, Int> {
        val finalNode = TraceNode("final", "最终值：$result（$reason）")
        finalNode.data["result"] = result
        root.children.add(finalNode)
        root.data["result"] = result
        return Triple(result, root, version)
    }

    /** 读取上下文字段：缺失与 null 分开处理；敏感字段只输出摘要。 */
    private fun readField(context: JsonNode, field: String): Triple<JsonNode?, TraceNode, FieldStatus> {
        val sensitive = field in project.sensitiveFields
        val node = TraceNode("read", "")
        node.data["field"] = field
        node.data["sensitive"] = sensitive
        when {
            !context.has(field) -> {
                node.message = "读取字段 $field → 缺失（missing）"
                node.data["status"] = "missing"
                return Triple(null, node, FieldStatus.MISSING)
            }
            context.get(field).isNull -> {
                node.message = "读取字段 $field → null"
                node.data["status"] = "null"
                return Triple(null, node, FieldStatus.NULL)
            }
            else -> {
                val value = context.get(field)
                node.data["status"] = "found"
                if (sensitive) {
                    val digest = digest(field, value)
                    node.message = "读取字段 $field → [敏感] 摘要 $digest"
                    node.data["digest"] = digest
                } else {
                    node.message = "读取字段 $field → ${value.toString()}"
                    node.data["value"] = value
                }
                return Triple(value, node, FieldStatus.FOUND)
            }
        }
    }

    /** HMAC-SHA256(projectSecret, field=canonical)，不同项目 secret 不同 → 跨项目不可关联。 */
    fun digest(field: String, value: JsonNode): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(project.secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val canonical = "$field=${value.toString()}"
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    private enum class FieldStatus { FOUND, MISSING, NULL }

    private fun evalCondition(cond: Condition, context: JsonNode): Pair<Boolean, TraceNode> {
        val node = TraceNode("condition", "条件 ${cond.field} ${cond.op} ${cond.value}")
        val (raw, readNode, status) = readField(context, cond.field)
        node.children.add(readNode)
        when (status) {
            FieldStatus.MISSING -> {
                node.data["result"] = false
                node.data["reason"] = "字段缺失"
                node.message += " → 失败（字段缺失）"
                return false to node
            }
            FieldStatus.NULL -> {
                // null 只与 EQ/NEQ null 比较，其余比较一律失败
                val ok = when {
                    cond.op == "EQ" && cond.value.isNull -> true
                    cond.op == "NEQ" && !cond.value.isNull -> true
                    cond.op == "NEQ" && cond.value.isNull -> false
                    else -> false
                }
                node.data["result"] = ok
                node.data["reason"] = if (ok) "null 比较" else "字段为 null，比较失败"
                node.message += " → ${if (ok) "成功" else "失败"}（${node.data["reason"]}）"
                return ok to node
            }
            FieldStatus.FOUND -> {
                val (ok, reason) = compare(cond.op, raw!!, cond.value)
                node.data["result"] = ok
                node.data["reason"] = reason
                node.message += " → ${if (ok) "成功" else "失败"}（$reason）"
                return ok to node
            }
        }
    }

    /** 类型严格比较：字符串与数字不隐式互转。 */
    private fun compare(op: String, actual: JsonNode, expected: JsonNode): Pair<Boolean, String> {
        if (op == "IN") {
            if (!expected.isArray) return false to "IN 的条件值必须是数组"
            val hit = expected.any { typeStrictEquals(actual, it) }
            return hit to if (hit) "在集合中" else "不在集合中"
        }
        // 类型严格：number / string / boolean 分属不同域，不做隐式互转
        val domainMismatch =
            (actual.isNumber != expected.isNumber && (actual.isNumber || expected.isNumber)) ||
            (actual.isTextual != expected.isTextual && (actual.isTextual || expected.isTextual)) ||
            (actual.isBoolean != expected.isBoolean && (actual.isBoolean || expected.isBoolean))
        if (domainMismatch) {
            return false to "类型不匹配：字段为 ${typeName(actual)}，条件值为 ${typeName(expected)}（不做隐式转换）"
        }
        return when (op) {
            "EQ" -> typeStrictEquals(actual, expected) to "相等比较"
            "NEQ" -> (!typeStrictEquals(actual, expected)) to "不等比较"
            "LT", "LTE", "GT", "GTE" -> compareOrder(op, actual, expected)
            "CONTAINS" -> compareContains(actual, expected)
            "STARTS_WITH" -> {
                if (!actual.isTextual || !expected.isTextual) false to "STARTS_WITH 需要字符串"
                else actual.textValue().startsWith(expected.textValue()) to "前缀比较"
            }
            "ENDS_WITH" -> {
                if (!actual.isTextual || !expected.isTextual) false to "ENDS_WITH 需要字符串"
                else actual.textValue().endsWith(expected.textValue()) to "后缀比较"
            }
            else -> false to "未知操作符 $op"
        }
    }

    private fun typeStrictEquals(a: JsonNode, b: JsonNode): Boolean = when {
        a.isNumber && b.isNumber -> a.decimalValue().compareTo(b.decimalValue()) == 0
        a.isTextual && b.isTextual -> a.textValue() == b.textValue()
        a.isBoolean && b.isBoolean -> a.booleanValue() == b.booleanValue()
        a.isNull && b.isNull -> true
        else -> false
    }

    private fun compareOrder(op: String, a: JsonNode, b: JsonNode): Pair<Boolean, String> {
        val cmp: Int = when {
            a.isNumber && b.isNumber -> a.decimalValue().compareTo(b.decimalValue())
            a.isTextual && b.isTextual -> a.textValue().compareTo(b.textValue())
            else -> return false to "排序比较需要双方同为数字或同为字符串（当前 ${typeName(a)} vs ${typeName(b)}）"
        }
        val ok = when (op) {
            "LT" -> cmp < 0
            "LTE" -> cmp <= 0
            "GT" -> cmp > 0
            "GTE" -> cmp >= 0
            else -> false
        }
        return ok to "排序比较 cmp=$cmp"
    }

    private fun compareContains(actual: JsonNode, expected: JsonNode): Pair<Boolean, String> = when {
        actual.isTextual && expected.isTextual ->
            actual.textValue().contains(expected.textValue()) to "字符串包含比较"
        actual.isArray ->
            actual.any { typeStrictEquals(it, expected) } to "数组包含比较（类型严格）"
        else -> false to "CONTAINS 需要字符串或数组字段"
    }

    private fun typeName(v: JsonNode): String = when {
        v.isNumber -> "number"
        v.isTextual -> "string"
        v.isBoolean -> "boolean"
        v.isNull -> "null"
        v.isArray -> "array"
        v.isObject -> "object"
        else -> "unknown"
    }

    private fun evalRollout(flag: Flag, rollout: Rollout, context: JsonNode): Pair<Boolean, TraceNode> {
        val pct = rollout.percentage
        val node = TraceNode("rollout", "百分比分流 $pct%")
        node.data["percentage"] = pct
        when {
            pct <= 0.0 -> {
                node.message += " → 0%，全部不通过"
                node.data["pass"] = false
                return false to node
            }
            pct >= 100.0 -> {
                node.message += " → 100%，全部通过"
                node.data["pass"] = true
                return true to node
            }
        }
        val identityField = flag.identityAttribute
        val (raw, readNode, status) = readField(context, identityField)
        node.children.add(readNode)
        if (status != FieldStatus.FOUND) {
            node.message += " → 失败（稳定身份字段 $identityField ${if (status == FieldStatus.MISSING) "缺失" else "为 null"}，无法分桶）"
            node.data["pass"] = false
            node.data["reason"] = "identity ${if (status == FieldStatus.MISSING) "missing" else "null"}"
            return false to node
        }
        val identity = when {
            raw!!.isTextual -> raw.textValue()
            raw.isNumber -> raw.decimalValue().stripTrailingZeros().toPlainString()
            raw.isBoolean -> raw.booleanValue().toString()
            else -> {
                node.message += " → 失败（身份字段类型不支持分桶）"
                node.data["pass"] = false
                return false to node
            }
        }
        val bucket = Bucketing.bucket(flag.key, flag.salt, identity)
        val pass = bucket < pct
        node.data["bucketInput"] = "${flag.key}:${flag.salt}:$identity"
        node.data["bucket"] = bucket
        node.data["pass"] = pass
        node.message += " → 分桶输入「${flag.key}:${flag.salt}:${if (identityField in project.sensitiveFields) "[敏感]" else identity}」= $bucket，${if (pass) "$bucket < $pct 通过" else "$bucket ≥ $pct 不通过"}"
        return pass to node
    }
}

/** 发布前校验：把“新版本的依赖”叠加到图上做环检测，返回环路径。 */
fun detectCycle(flags: Map<String, Flag>, overrideKey: String, overridePrereqs: List<Prerequisite>): List<String>? {
    val graph = HashMap<String, List<String>>()
    for ((key, flag) in flags) {
        val prereqs = if (key == overrideKey) overridePrereqs else flag.version(null)?.prerequisites ?: emptyList()
        graph[key] = prereqs.map { it.flagKey }.filter { flags.containsKey(it) || it == overrideKey }
    }
    val state = HashMap<String, Int>() // 0=未访问 1=访问中 2=完成
    val stack = ArrayDeque<String>()
    fun dfs(node: String): List<String>? {
        state[node] = 1
        stack.addLast(node)
        for (next in graph[node].orEmpty()) {
            when (state[next]) {
                1 -> {
                    val idx = stack.indexOf(next)
                    return stack.drop(idx) + next
                }
                null, 0 -> {
                    val found = dfs(next)
                    if (found != null) return found
                }
            }
        }
        stack.removeLast()
        state[node] = 2
        return null
    }
    for (key in graph.keys) {
        if (state[key] == null) {
            val found = dfs(key)
            if (found != null) return found
        }
    }
    return null
}
