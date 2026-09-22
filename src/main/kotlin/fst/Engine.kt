package fst

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/* Field lookup result distinguishes three states: present non-null, explicit null, missing. */
sealed class FieldValue {
    data class Present(val value: Any?) : FieldValue()
    object Null : FieldValue()
    object Missing : FieldValue()
}

object Fields {
    fun resolve(context: Map<String, Any?>, path: String): FieldValue {
        if (path.isEmpty()) return FieldValue.Missing
        val parts = path.split('.')
        var cur: Any? = context
        for (part in parts) {
            val node = cur
            if (node !is Map<*, *>) return FieldValue.Missing
            if (!node.containsKey(part)) return FieldValue.Missing
            cur = node[part]
        }
        return if (cur == null) FieldValue.Null else FieldValue.Present(cur)
    }
}

/* Public, stable bucketing: SHA-256 over a canonical string.
   Bucket = first 32 bits of SHA-256(flagKey | salt | stableId) mod 100.
   The "|" delimiter cannot appear inside structured components; the function is documented
   and exported so behavior is reproducible outside the system. Percentages are weight points
   out of 100, so end points 0 and 100 are exact. */
object Bucketing {
    const val BUCKETS = 100
    fun hashInput(flagKey: String, salt: String, stableId: String): String =
        "$flagKey|$salt|$stableId"

    fun bucket(flagKey: String, salt: String, stableId: String): Int {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(hashInput(flagKey, salt, stableId).toByteArray(StandardCharsets.UTF_8))
        val unsigned = BigInteger(1, digest.copyOfRange(0, 4)).toLong()
        return (unsigned % BUCKETS).toInt()
    }

    /** Pick a variant from a weighted list. Weights are points in [0,100] and must sum to 100. */
    fun choose(rollout: List<Distribution>, bucket: Int): String {
        var cursor = 0
        for (d in rollout) {
            val start = cursor
            val endExclusive = cursor + d.weight
            if (bucket in start until endExclusive) return d.variant
            cursor = endExclusive
        }
        return rollout.last().variant
    }
}

/* Sensitive summaries: HMAC-SHA256(domainSalt, fieldPath | canonicalJson(value)).
   - Same project + same path + same value => identical summary (proves same input).
   - Different project (different domainSalt) => unlinkable.
   - The field path is included so distinct sensitive fields cannot be joined to each other. */
object Sensitive {
    fun summary(domainSalt: String, fieldPath: String, rawValue: Any?): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(domainSalt.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val msg = "$fieldPath|${Json.write(rawValue)}"
        val out = mac.doFinal(msg.toByteArray(StandardCharsets.UTF_8))
        val hex = out.joinToString("") { "%02x".format(it) }
        return "hmac:" + hex.take(16)
    }
}

/* ---------- Trace tree ---------- */
data class TNode(
    val kind: String,                 // stable machine label
    val title: String,                // human readable, zh
    var matched: Boolean?,            // null = informational
    var detail: Map<String, Any?> = emptyMap(),
    val children: MutableList<TNode> = mutableListOf(),
) {
    fun add(child: TNode): TNode { children.add(child); return child }
    fun toJson(): Map<String, Any?> = Json.obj(
        "kind" to kind, "title" to title,
        "matched" to matched,
        "detail" to detail,
        "children" to children.map { it.toJson() },
    )
}

data class EvalOutcome(val served: Served, val reason: String, val root: TNode)

/* ---------- Condition evaluation ---------- */
data class CondResult(val ok: Boolean, val state: String, val detail: Map<String, Any?>)

object Conditions {
    private fun isNum(v: Any?) = v is Number
    private fun sameJsonType(a: Any?, b: Any?): Boolean = when {
        a is String && b is String -> true
        isNum(a) && isNum(b) -> true
        a is Boolean && b is Boolean -> true
        else -> false
    }

    fun evaluate(cond: Condition, context: Map<String, Any?>, sensitive: Set<String>, digestSalt: String): Pair<CondResult, TNode> {
        val fv = Fields.resolve(context, cond.field)
        val isSensitive = cond.field in sensitive
        fun display(v: Any?): Any? = if (isSensitive) Sensitive.summary(digestSalt, cond.field, v) else v
        val node = TNode("condition", "条件 ${cond.id}（${cond.field} ${cond.op}）", null)

        if (fv is FieldValue.Missing) {
            node.detail = Json.obj("field" to cond.field, "fieldState" to "missing", "op" to cond.op)
            node.matched = false
            node.add(TNode("field-read", "读取字段 ${cond.field}：上下文中缺失（missing，非 null）", null,
                Json.obj("field" to cond.field, "state" to "missing")))
            node.add(TNode("missing-fail", "字段缺失，条件判为失败（缺失与显式 null 分开处理）", false))
            return CondResult(false, "missing", Json.obj("field" to cond.field, "state" to "missing")) to node
        }
        if (fv is FieldValue.Null) {
            node.detail = Json.obj("field" to cond.field, "fieldState" to "null", "op" to cond.op)
            node.add(TNode("field-read", "读取字段 ${cond.field}：显式 null", null,
                Json.obj("field" to cond.field, "state" to "null", "value" to null)))
            val ok = when (cond.op) {
                "eq" -> cond.value == null
                "ne" -> cond.value != null
                "is_null" -> true
                "not_null" -> false
                else -> false
            }
            val supports = cond.op in setOf("eq", "ne", "is_null", "not_null")
            if (!supports) node.add(TNode("type-mismatch", "null 不支持 ${cond.op} 运算，条件失败", false,
                Json.obj("expectedType" to typeName(cond.value))))
            else node.add(TNode(if (ok) "match" else "no-match",
                if (ok) "null 比较成立" else "null 比较不成立（无隐式类型转换）", ok))
            node.matched = ok
            return CondResult(ok, "null", Json.obj("field" to cond.field, "state" to "null")) to node
        }

        val actual = (fv as FieldValue.Present).value
        val readDetail = Json.obj(
            "field" to cond.field, "state" to "present",
            "valueType" to typeName(actual),
            "value" to display(actual),
            "sensitive" to isSensitive,
        )
        node.add(TNode("field-read", "读取字段 ${cond.field}：${typeName(actual)}${if (isSensitive) "（敏感，仅显示摘要）" else ""}", null, readDetail))

        val ok = compare(cond.op, actual, cond.value, node)
        node.matched = ok
        node.detail = Json.obj(
            "field" to cond.field, "fieldState" to "present", "op" to cond.op,
            "actualType" to typeName(actual), "actual" to display(actual),
            "expectedType" to typeName(cond.value), "expected" to cond.value,
            "strictTypes" to true, "sensitive" to isSensitive,
        )
        node.add(TNode(if (ok) "match" else "no-match", if (ok) "条件成立" else "条件不成立", ok))
        return CondResult(ok, "present", node.detail) to node
    }

    private fun compare(op: String, actual: Any?, expected: Any?, node: TNode): Boolean = when (op) {
        "eq" -> typeCheck(actual, expected, node) && numAwareEq(actual, expected)
        "ne" -> {
            if (!sameTypeOrMismatch(actual, expected, node)) true else !numAwareEq(actual, expected)
        }
        "gt", "ge", "lt", "le" -> {
            if (!(isNum(actual) && isNum(expected))) {
                mismatch(actual, expected, node); false
            } else {
                val a = (actual as Number).toDouble(); val b = (expected as Number).toDouble()
                when (op) { "gt" -> a > b; "ge" -> a >= b; "lt" -> a < b; else -> a <= b }
            }
        }
        "in" -> {
            if (expected !is List<*>) {
                node.add(TNode("type-mismatch", "in 运算右侧必须是数组", false)); false
            } else
            expected.any { it != null && sameJsonType(actual, it) && numAwareEq(actual, it) }
        }
        "starts_with", "ends_with", "contains_s" -> {
            if (actual !is String || expected !is String) { mismatch(actual, expected, node); false }
            else when (op) { "starts_with" -> actual.startsWith(expected); "ends_with" -> actual.endsWith(expected); else -> actual.contains(expected) }
        }
        "is_null" -> false
        "not_null" -> true
        else -> { node.add(TNode("bad-op", "未知运算符 $op", false)); false }
    }

    private fun sameTypeOrMismatch(actual: Any?, expected: Any?, node: TNode): Boolean {
        if (sameJsonType(actual, expected)) return true
        mismatch(actual, expected, node); return false
    }
    private fun typeCheck(actual: Any?, expected: Any?, node: TNode): Boolean = sameTypeOrMismatch(actual, expected, node)
    private fun mismatch(actual: Any?, expected: Any?, node: TNode) {
        node.add(TNode("type-mismatch",
            "类型不匹配：${typeName(actual)} 不能与 ${typeName(expected)} 比较（不做隐式转换）", false,
            Json.obj("actualType" to typeName(actual), "expectedType" to typeName(expected))))
    }
    private fun numAwareEq(a: Any?, b: Any?): Boolean =
        if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b

    fun typeName(v: Any?): String = when (v) {
        null -> "null"; is String -> "string"; is Boolean -> "boolean"
        is Number -> "number"; is Map<*, *> -> "object"; is List<*> -> "array"; else -> "unknown"
    }
}

/* ---------- Snapshot & dependency graph ---------- */
class Snapshot(val versions: Map<String, FlagVersion>, val project: Project) {
    fun flag(key: String): Flag? = project.flags.firstOrNull { it.key == key }
}

class CycleException(val path: List<String>) : RuntimeException("检测到前置依赖环：${path.joinToString(" -> ")}")

class RuleValidationException(message: String) : RuntimeException(message)

object Engine {
    fun snapshot(project: Project, versionSelections: Map<String, Int>? = null): Snapshot {
        val map = LinkedHashMap<String, FlagVersion>()
        for (flag in project.flags) {
            val selected = versionSelections?.get(flag.key)
            val v = selected?.let { flag.version(it) } ?: flag.current()
            if (v != null) map[flag.key] = v
        }
        return Snapshot(map, project)
    }

    /** DFS cycle detection with the actual prerequisite path. Throws CycleException. */
    fun checkCycles(snap: Snapshot) {
        val state = HashMap<String, Int>() // 0=visiting 1=done
        fun dfs(key: String, stack: List<String>) {
            when (state[key]) {
                0 -> throw CycleException(stack.drop(stack.indexOf(key)) + key)
                1 -> return
            }
            state[key] = 0
            val v = snap.versions[key] ?: throw RuleValidationException("前置依赖指向不存在的开关：$key")
            for (pre in v.prerequisites) dfs(pre.flagKey, stack + key)
            state[key] = 1
        }
        for (key in snap.versions.keys) dfs(key, emptyList())
    }

    fun validateVersion(flag: Flag, v: FlagVersion) {
        val variantSet = v.onVariants.toSet()
        if (v.onVariants.size != variantSet.size) throw RuleValidationException("变体名重复")
        if (v.onVariants.isEmpty()) throw RuleValidationException("至少需要一个变体或使用 off 默认值")
        fun checkServe(s: Serve, where: String) {
            if (s.type == "variant" && s.variant !in variantSet)
                throw RuleValidationException("$where 引用了未声明的变体 ${s.variant}")
        }
        checkServe(v.defaultServe, "默认值")
        for (pre in v.prerequisites) {
            if (pre.flagKey == flag.key) throw RuleValidationException("前置开关不能引用自身：${flag.key}")
        }
        for ((i, rule) in v.rules.withIndex()) {
            if (rule.conditions.isEmpty()) throw RuleValidationException("规则 ${rule.name.ifBlank { (i + 1).toString() }} 至少需要一个条件")
            val rollout = rule.rollout
            if (rollout != null) {
                if (rollout.isEmpty()) throw RuleValidationException("规则 ${rule.name} 的分流不能为空")
                val sum = rollout.sumOf { it.weight }
                if (sum != Bucketing.BUCKETS) throw RuleValidationException("规则 ${rule.name} 分流权重之和必须为 ${Bucketing.BUCKETS}（当前 $sum）")
                for (d in rollout) {
                    if (d.weight < 0 || d.weight > Bucketing.BUCKETS)
                        throw RuleValidationException("规则 ${rule.name} 权重必须在 0..${Bucketing.BUCKETS}")
                    if (d.variant !in variantSet)
                        throw RuleValidationException("规则 ${rule.name} 分流引用了未声明的变体 ${d.variant}")
                }
                if (rollout.map { it.variant }.toSet().size != rollout.size)
                    throw RuleValidationException("规则 ${rule.name} 分流变体重复")
            } else {
                checkServe(rule.fixed ?: throw RuleValidationException("规则 ${rule.name} 缺少 serve 或 rollout"), "规则 ${rule.name}")
            }
        }
    }

    /** Evaluate one flag within a fixed snapshot. */
    fun evaluate(snap: Snapshot, flagKey: String, context: Map<String, Any?>, _stack: List<String> = emptyList()): EvalOutcome {
        val flag = snap.flag(flagKey) ?: throw RuleValidationException("开关不存在：$flagKey")
        val v = snap.versions[flagKey] ?: throw RuleValidationException("开关没有已发布版本：$flagKey")
        val sensitive = flag.sensitiveFields.toSet()
        val root = TNode("flag", "开关 $flagKey（版本 ${v.version}）", null,
            Json.obj("flagKey" to flagKey, "version" to v.version, "salt" to v.salt, "stableIdField" to flag.stableIdField))

        if (_stack.contains(flagKey)) throw CycleException(_stack.drop(_stack.indexOf(flagKey)) + flagKey)
        val stack = _stack + flagKey

        // 1) prerequisites in order
        if (v.prerequisites.isNotEmpty()) {
            val preNode = root.add(TNode("prerequisites", "前置依赖（按顺序）", null))
            for (pre in v.prerequisites) {
                val pn = preNode.add(TNode("prerequisite", "前置开关 ${pre.flagKey} 必须为 ${pre.expected}", null,
                    Json.obj("flag" to pre.flagKey, "expected" to pre.expected)))
                val depVersion = snap.versions[pre.flagKey]
                if (depVersion == null) {
                    pn.matched = false
                    pn.add(TNode("missing-flag", "前置开关 ${pre.flagKey} 不存在或没有版本，依赖不满足", false))
                } else {
                    val dep = evaluate(snap, pre.flagKey, context, stack)
                    pn.add(dep.root)
                    val got = dep.served.display()
                    val ok = got == pre.expected
                    pn.matched = ok
                    pn.add(TNode("prereq-compare", "前置结果=$got，期望=${pre.expected}", ok,
                        Json.obj("actual" to got, "expected" to pre.expected)))
                    if (!ok) {
                        preNode.matched = false
                        root.matched = false
                        return finishWithOff(root, "prerequisite_failed", "前置依赖 ${pre.flagKey}=$got 不等于期望 ${pre.expected}，短路返回 off")
                    }
                }
                if (pn.matched == false) {
                    root.matched = false
                    return finishWithOff(root, "prerequisite_failed", "前置依赖 ${pre.flagKey} 无法满足，短路返回 off")
                }
            }
            preNode.matched = true
        }

        // 2) stable identity
        val idFv = Fields.resolve(context, flag.stableIdField)
        val stableRaw = (idFv as? FieldValue.Present)?.value
        val stableId = stableRaw?.takeIf { it is String || it is Number }?.let { if (it is Number) trimNum(it) else it as String }
        val idNode = root.add(TNode("stable-id", "稳定身份字段 ${flag.stableIdField}", null,
            Json.obj("field" to flag.stableIdField)))
        if (stableId == null) {
            val state = if (idFv is FieldValue.Null) "null" else if (idFv is FieldValue.Missing) "missing" else "wrong_type"
            idNode.add(TNode("stable-id-unusable", when (state) {
                "null" -> "稳定身份显式为 null，无法分桶"
                "missing" -> "稳定身份字段缺失，无法分桶"
                else -> "稳定身份不是 string/number，无法分桶"
            }, false, Json.obj("state" to state)))
        } else {
            val idSensitive = flag.stableIdField in sensitive
            val shown = if (idSensitive) Sensitive.summary(snap.project.digestSalt, flag.stableIdField, stableRaw) else stableId
            idNode.add(TNode("stable-id-read", "稳定身份 = $shown（${if (idSensitive) "敏感摘要" else "明文"}）", null,
                Json.obj("state" to "present", "stableId" to shown, "sensitive" to idSensitive)))
        }

        // 3) rules in order
        if (v.rules.isNotEmpty()) {
            val rulesNode = root.add(TNode("rules", "规则按顺序求值", null))
            for ((index, rule) in v.rules.withIndex()) {
                val rn = rulesNode.add(TNode("rule", "规则 #${index + 1} ${rule.name.ifBlank { rule.id }}", null,
                    Json.obj("ruleId" to rule.id, "order" to index, "mode" to if (rule.rollout != null) "rollout" else "fixed")))
                var allMatched = true
                for (cond in rule.conditions) {
                    val (res, cn) = Conditions.evaluate(cond, context, sensitive, snap.project.digestSalt)
                    rn.add(cn)
                    if (!res.ok) { allMatched = false; break }
                }
                if (!allMatched) {
                    rn.matched = false
                    rn.add(TNode("rule-skip", "条件未全部满足，跳过本规则，继续下一条", false))
                    continue
                }
                rn.matched = true
                val matchNode = rn.add(TNode("rule-match", "规则命中，使用其分流/固定值", true))
                val served = serveClause(rule, flagKey, v.salt, stableId, matchNode)
                root.matched = true
                return EvalOutcome(served, "rule_match", root)
            }
            rulesNode.add(TNode("rules-exhausted", "所有规则均未命中，进入默认值", null))
        }

        // 4) default
        val dn = root.add(TNode("default", "默认值", null, Json.obj("serve" to v.defaultServe.toJson())))
        dn.matched = true
        root.matched = true
        dn.add(TNode("served", "返回默认值 ${v.defaultServe.resolve().display()}", true))
        return EvalOutcome(v.defaultServe.resolve(), "default", root)
    }

    private fun trimNum(n: Number): String {
        val d = n.toDouble()
        return if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }

    private fun serveClause(rule: Rule, flagKey: String, salt: String, stableId: String?, node: TNode): Served {
        rule.rollout?.let { rollout ->
            val bn = node.add(TNode("rollout", "百分比分流", null,
                Json.obj("algorithm" to "SHA-256(flagKey|salt|stableId)[0..3] mod 100", "flagKey" to flagKey, "salt" to salt)))
            if (stableId == null) {
                bn.add(TNode("rollout-no-id", "缺少可用稳定身份，分流无法进行，落到默认 off", false))
                return Served(true)
            }
            val bucket = Bucketing.bucket(flagKey, salt, stableId)
            val input = Bucketing.hashInput(flagKey, salt, stableId)
            bn.add(TNode("bucket", "分桶输入 = \"$input\" → 桶号 $bucket", null,
                Json.obj("hashInput" to input, "bucket" to bucket, "buckets" to Bucketing.BUCKETS)))
            val bands = StringBuilder()
            var cursor = 0
            for (d in rollout) {
                bands.append("${d.variant}[${cursor}-${cursor + d.weight - 1}] ")
                cursor += d.weight
            }
            val variant = Bucketing.choose(rollout, bucket)
            bn.add(TNode("band", "区间：${bands.trim()}；桶 $bucket 落入 $variant", true,
                Json.obj("chosen" to variant, "bands" to rollout.map { it.toJson() })))
            return Served(false, variant)
        }
        val s = rule.fixed!!.resolve()
        node.add(TNode("served", "固定返回 ${s.display()}", true, Json.obj("serve" to s.toJson())))
        return s
    }

    private fun finishWithOff(root: TNode, reason: String, title: String): EvalOutcome {
        root.add(TNode("short-circuit", title, false, Json.obj("serve" to "off")))
        return EvalOutcome(Served(true), reason, root)
    }
}
