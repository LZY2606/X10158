package ft

/** Outcome of one flag evaluation plus its full trace tree. */
data class Evaluation(
    val flagKey: String,
    val version: Int,
    val outcome: Outcome,
    val reason: String,
    val trace: TraceNode
) {
    fun toJson(): Any? = linkedMapOf(
        "flagKey" to flagKey,
        "version" to version,
        "outcome" to outcome.toJson(),
        "reason" to reason,
        "trace" to trace.toJson()
    )
}

class EvaluationException(message: String) : RuntimeException(message)

/** Read result of a context path: distinguishes missing from explicit null. */
data class FieldRead(val present: Boolean, val value: Any?)

object Contexts {
    /** Supports simple dotted paths over nested maps. */
    fun read(context: Map<String, Any?>, path: String): FieldRead {
        val parts = path.split('.')
        var cur: Any? = context
        for ((idx, part) in parts.withIndex()) {
            if (cur !is Map<*, *>) {
                return if (idx == 0) FieldRead(false, null) else FieldRead(false, null)
            }
            if (!cur.containsKey(part)) return FieldRead(false, null)
            cur = cur[part]
        }
        return FieldRead(true, cur)
    }
}

/**
 * Stateless condition matcher. Trace nodes record exactly which fields were
 * read and why each condition passed or failed.
 */
class ConditionMatcher(private val redactor: Redactor) {

    fun evaluate(condition: Condition, context: Map<String, Any?>): Pair<Boolean, TraceNode> {
        val read = Contexts.read(context, condition.field)
        val (matched, reason) = check(condition, read)
        val detail = LinkedHashMap<String, Any?>()
        detail.putAll(redactor.valueDetail(condition.field, read.value, read.present))
        detail["op"] = condition.op
        if (condition.op !in setOf("present", "absent")) {
            detail["expected"] = if (redactor.isSensitive(condition.field))
                redactor.summarize(condition.field, condition.value)
            else condition.value
            detail["expectedType"] = if (redactor.isSensitive(condition.field))
                redactor.typeName(condition.value) else redactor.typeName(condition.value)
        }
        detail["reason"] = reason
        val node = TraceNode(
            kind = "condition",
            title = describe(condition),
            matched = matched,
            detail = detail
        )
        return matched to node
    }

    private fun describe(c: Condition): String = when (c.op) {
        "present" -> "${c.field} is present"
        "absent" -> "${c.field} is absent"
        "in" -> "${c.field} in ${c.value}"
        else -> "${c.field} ${displayOp(c.op)} ${c.value}"
    }

    private fun displayOp(op: String) = when (op) {
        "eq" -> "="
        "ne" -> "!="
        "gt" -> ">"
        "ge" -> ">="
        "lt" -> "<"
        "le" -> "<="
        else -> op
    }

    private fun check(c: Condition, read: FieldRead): Pair<Boolean, String> {
        val v = read.value
        when (c.op) {
            "present" -> return if (read.present) true to "field present" else false to "field missing"
            "absent" -> return if (!read.present) true to "field missing" else false to "field present"
        }
        if (!read.present) return false to "field missing"
        when (c.op) {
            "eq" -> {
                if (!sameType(v, c.value)) return false to "type mismatch: ${Redactor.Companion.let { Redactor(emptySet(),"").typeName(v) }} != ${Redactor(emptySet(),"").typeName(c.value)}"
                val eq = valueEquals(v, c.value)
                return eq to if (eq) "equal" else "not equal"
            }
            "ne" -> {
                if (!sameType(v, c.value)) return true to "different type -> not equal"
                val eq = valueEquals(v, c.value)
                return !eq to if (!eq) "not equal" else "equal"
            }
            "gt", "ge", "lt", "le" -> {
                if (v !is Number || c.value !is Number)
                    return false to "ordering requires two numbers, got ${Redactor(emptySet(),"").typeName(v)} and ${Redactor(emptySet(),"").typeName(c.value)}"
                val cmp = java.math.BigDecimal(v.toString()).compareTo(java.math.BigDecimal(c.value.toString()))
                val ok = when (c.op) {
                    "gt" -> cmp > 0
                    "ge" -> cmp >= 0
                    "lt" -> cmp < 0
                    else -> cmp <= 0
                }
                return ok to if (ok) "comparison true" else "comparison false"
            }
            "starts_with", "ends_with", "contains" -> {
                if (v !is String || c.value !is String)
                    return false to "string operation on ${Redactor(emptySet(),"").typeName(v)}"
                val ok = when (c.op) {
                    "starts_with" -> v.startsWith(c.value)
                    "ends_with" -> v.endsWith(c.value)
                    else -> v.contains(c.value)
                }
                return ok to if (ok) "matched" else "no match"
            }
            "in" -> {
                if (v !is String) return false to "'in' requires a string field"
                val list = c.value as? List<*> ?: return false to "'in' expects a list"
                val hit = list.any { it is String && it == v }
                return hit to if (hit) "member of list" else "not in list"
            }
            else -> throw EvaluationException("Unknown operator '${c.op}'")
        }
    }

    private fun sameType(a: Any?, b: Any?): Boolean = when {
        a == null && b == null -> true
        a is Number && b is Number -> true
        a is String && b is String -> true
        a is Boolean && b is Boolean -> true
        a is List<*> && b is List<*> -> true
        a is Map<*, *> && b is Map<*, *> -> true
        else -> false
    }

    private fun valueEquals(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number ->
            java.math.BigDecimal(a.toString()).compareTo(java.math.BigDecimal(b.toString())) == 0
        a is List<*> && b is List<*> -> a.size == b.size &&
            a.indices.all { valueEquals(a[it], b[it]) }
        a is Map<*, *> && b is Map<*, *> -> a.size == b.size &&
            a.keys.all { b.containsKey(it) && valueEquals(a[it], b[it]) }
        else -> a == b
    }
}

/**
 * Immutable view of a set of flag versions. A batch evaluation pins one
 * snapshot; publishing a new version during the batch cannot mix old/new rules
 * because the snapshot holds references to immutable [FlagVersion] objects.
 */
class Snapshot(
    val versions: Map<String, FlagVersion>,
    val bucketSalt: String,
    redactor: Redactor
) {
    val matcher = ConditionMatcher(redactor)
    val redactor: Redactor = redactor
}

class Engine {

    /**
     * @param target explicit version to evaluate; defaults to latest.
     */
    fun evaluate(
        snapshot: Snapshot,
        flagKey: String,
        context: Map<String, Any?>,
        targetVersion: Int? = null,
        stack: List<String> = emptyList()
    ): Evaluation {
        val flag = snapshot.versions[flagKey]
            ?: throw EvaluationException("Unknown flag '$flagKey'")
        val fv = if (targetVersion == null) flag
        else snapshot.versions.values.firstOrNull { it.key == flagKey && it.version == targetVersion }
            ?: throw EvaluationException("Flag '$flagKey' has no version $targetVersion")

        val children = ArrayList<TraceNode>()

        // 1) Prerequisites
        for (prereq in fv.prerequisites) {
            if (prereq.flagKey in stack) {
                throw EvaluationException("Prerequisite cycle detected at ${prereq.flagKey}")
            }
            val dep = evaluate(snapshot, prereq.flagKey, context, null, stack + fv.key)
            val ok = dep.outcome == prereq.required
            children += TraceNode(
                kind = "prerequisite",
                title = "prerequisite ${prereq.flagKey} must be ${prereq.required.label}",
                matched = ok,
                detail = linkedMapOf(
                    "flagKey" to prereq.flagKey,
                    "required" to prereq.required.toJson(),
                    "actual" to dep.outcome.toJson(),
                    "version" to dep.version
                ),
                children = listOf(dep.trace)
            )
            if (!ok) {
                val root = TraceNode("flag", "evaluate ${fv.key} v${fv.version}", false,
                    linkedMapOf("default" to fv.defaultValue.toJson()), children)
                return Evaluation(fv.key, fv.version, fv.defaultValue,
                    "prerequisite ${prereq.flagKey} was ${dep.outcome.label}, required ${prereq.required.label}", root)
            }
        }

        // 2) Ordered rules
        for (rule in fv.rules) {
            val (served, ruleNode) = evaluateRule(snapshot, fv, rule, context)
            children += ruleNode
            if (served != null) {
                val root = TraceNode("flag", "evaluate ${fv.key} v${fv.version}", true,
                    linkedMapOf("result" to served.toJson()), children)
                return Evaluation(fv.key, fv.version, served, "rule '${rule.name}' served", root)
            }
        }

        // 3) Default
        children += TraceNode("default", "default value ${fv.defaultValue.label}", true,
            linkedMapOf("outcome" to fv.defaultValue.toJson()))
        val root = TraceNode("flag", "evaluate ${fv.key} v${fv.version}", true,
            linkedMapOf("result" to fv.defaultValue.toJson()), children)
        return Evaluation(fv.key, fv.version, fv.defaultValue, "no rule matched", root)
    }

    private fun evaluateRule(
        snapshot: Snapshot,
        fv: FlagVersion,
        rule: Rule,
        context: Map<String, Any?>
    ): Pair<Outcome?, TraceNode> {
        val condNodes = ArrayList<TraceNode>()
        var allMatched = true
        for (c in rule.conditions) {
            val (ok, node) = snapshot.matcher.evaluate(c, context)
            condNodes += node
            if (!ok) allMatched = false
        }

        if (!allMatched) {
            return null to TraceNode(
                "rule",
                "rule '${rule.name}' (index ${fv.rules.indexOf(rule)}): conditions not met",
                false,
                linkedMapOf("ruleId" to rule.id, "mode" to modeOf(rule)),
                condNodes
            )
        }

        if (rule.isRollout) {
            val slices = rule.rollout!!
            val idRead = Contexts.read(context, fv.stableIdField)
            val condSummary = TraceNode(
                "rule",
                "rule '${rule.name}' (index ${fv.rules.indexOf(rule)}): conditions met",
                null,
                linkedMapOf("ruleId" to rule.id, "mode" to "rollout"),
                condNodes
            )

            if (!idRead.present || idRead.value == null) {
                val node = TraceNode("rule", "rule '${rule.name}': no stable id, cannot bucket", false,
                    linkedMapOf("ruleId" to rule.id, "stableIdField" to fv.stableIdField,
                        "id" to snapshot.redactor.valueDetail(fv.stableIdField, idRead.value, idRead.present)),
                    listOf(condSummary))
                return null to node
            }

            val identity = idRead.value.toString()
            // Bucketing key: reordering rules never changes it because rule id,
            // order-independent salts, key and identity are the only inputs.
            val bucketKey = listOf(
                snapshot.bucketSalt, fv.key, fv.salt, rule.id, identity
            ).joinToString("|")
            val rawHash = Hashing.murmur3_32(bucketKey).toLong() and 0xffffffffL
            val bucket = (rawHash % 10000L).toInt()

            val sliceNodes = ArrayList<TraceNode>()
            var cumulative = 0
            var chosen: Outcome? = null
            for (slice in slices) {
                val lo = cumulative
                val hi = cumulative + slice.weightBps - 1
                val inSlice = slice.weightBps > 0 && bucket in lo..hi
                if (inSlice && chosen == null) chosen = slice.outcome
                sliceNodes += TraceNode(
                    "slice",
                    "${slice.outcome.label}: ${formatBps(slice.weightBps)}",
                    inSlice,
                    linkedMapOf(
                        "outcome" to slice.outcome.toJson(),
                        "weightBps" to slice.weightBps,
                        "rangeLow" to lo,
                        "rangeHigh" to hi
                    )
                )
                cumulative += slice.weightBps
            }
            val rolloutNode = TraceNode(
                "rollout",
                "percentage split (bucket $bucket / 10000)",
                chosen != null,
                linkedMapOf(
                    "stableIdField" to fv.stableIdField,
                    "bucketKeyInputs" to linkedMapOf(
                        "projectSalt" to "present",
                        "flagKey" to fv.key,
                        "flagSalt" to fv.salt,
                        "ruleId" to rule.id,
                        "stableId" to snapshot.redactor.valueDetail(fv.stableIdField, idRead.value, true)
                    ),
                    "rawHash" to "0x" + rawHash.toString(16),
                    "bucket" to bucket,
                    "allocatedBps" to cumulative,
                    "unallocated" to (bucket >= cumulative)
                ),
                sliceNodes
            )
            val node = TraceNode(
                "rule",
                "rule '${rule.name}' (index ${fv.rules.indexOf(rule)})",
                chosen != null,
                linkedMapOf("ruleId" to rule.id, "mode" to "rollout",
                    "served" to (chosen != null),
                    "fallthrough" to (chosen == null)),
                listOf(condSummary, rolloutNode)
            )
            return chosen to node
        }

        val served = rule.outcome!!
        val node = TraceNode(
            "rule",
            "rule '${rule.name}' (index ${fv.rules.indexOf(rule)}): conditions met -> ${served.label}",
            true,
            linkedMapOf("ruleId" to rule.id, "mode" to "fixed", "outcome" to served.toJson()),
            condNodes
        )
        return served to node
    }

    private fun modeOf(rule: Rule) = if (rule.isRollout) "rollout" else "fixed"
    private fun formatBps(bps: Int): String {
        val whole = bps / 100
        val frac = bps % 100
        return if (frac == 0) "${whole}%" else "%d.%02d%%".format(whole, frac)
    }
}
