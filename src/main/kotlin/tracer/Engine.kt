package tracer

/** MurmurHash3 x86_32 — the stable, public bucketing algorithm. */
object Murmur3 {
    fun hash32(data: String, seed: Int = 0): Int {
        val bytes = data.toByteArray(Charsets.UTF_8)
        var h = seed
        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593
        var i = 0
        while (i + 4 <= bytes.size) {
            var k = (bytes[i].toInt() and 0xff) or
                ((bytes[i + 1].toInt() and 0xff) shl 8) or
                ((bytes[i + 2].toInt() and 0xff) shl 16) or
                ((bytes[i + 3].toInt() and 0xff) shl 24)
            k *= c1
            k = Integer.rotateLeft(k, 15)
            k *= c2
            h = h xor k
            h = Integer.rotateLeft(h, 13)
            h = h * 5 + 0xe6546b64.toInt()
            i += 4
        }
        var k = 0
        val rem = bytes.size - i
        if (rem == 3) k = k xor ((bytes[i + 2].toInt() and 0xff) shl 16)
        if (rem >= 2) k = k xor ((bytes[i + 1].toInt() and 0xff) shl 8)
        if (rem >= 1) {
            k = k xor (bytes[i].toInt() and 0xff)
            k *= c1
            k = Integer.rotateLeft(k, 15)
            k *= c2
            h = h xor k
        }
        h = h xor bytes.size
        h = h xor (h ushr 16)
        h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 13)
        h *= 0xc2b2ae35.toInt()
        h = h xor (h ushr 16)
        return h
    }

    /** Bucket in [0, 100000) — permille-of-percent precision, independent of rule order. */
    fun bucket(flagKey: String, salt: String, stableId: String): Int {
        val h = hash32("$flagKey:$salt:$stableId")
        return ((h.toLong() and 0xffffffffL) % 100000L).toInt()
    }
}

class CycleException(val path: List<String>) :
    RuntimeException("prerequisite cycle detected: ${path.joinToString(" -> ")}")

class EvalException(msg: String) : RuntimeException(msg)

/**
 * Immutable snapshot of flag versions used for one batch of evaluations.
 * Publishing a new version mid-batch cannot leak into results computed from a snapshot.
 */
class FlagSnapshot(private val defs: Map<String, FlagDef>, private val versions: Map<String, Int>) {
    fun def(key: String): FlagDef? = defs[key]
    fun version(key: String): Int? = versions[key]
    val keys: Set<String> get() = defs.keys

    /** Throws CycleException with the offending path if the prerequisite graph has a cycle. */
    fun checkCycles() {
        val visiting = LinkedHashSet<String>()
        val done = mutableSetOf<String>()
        fun dfs(key: String) {
            if (key in done) return
            if (!visiting.add(key)) {
                val path = visiting.toList().dropWhile { it != key } + key
                throw CycleException(path)
            }
            val def = defs[key] ?: return
            for (pre in def.prerequisites) if (defs.containsKey(pre)) dfs(pre)
            visiting.remove(key)
            done.add(key)
        }
        for (k in defs.keys) dfs(k)
    }
}

class Engine(private val sensitiveFields: Set<String>, private val digester: SensitiveDigester) {

    /** Display a context field value in the trace: digest for sensitive fields, raw otherwise. */
    private fun display(field: String, value: JVal): JVal =
        if (field in sensitiveFields)
            objOf("sensitive" to JVal.JBool(true), "digest" to JVal.JStr(digester.digest(field, value)))
        else
            objOf("sensitive" to JVal.JBool(false), "value" to value)

    fun evaluate(snapshot: FlagSnapshot, flagKey: String, context: JVal.JObj): JVal.JObj {
        val (result, trace) = evalInternal(snapshot, flagKey, context, mutableListOf())
        return objOf(
            "flagKey" to JVal.JStr(flagKey),
            "version" to JVal.JNum((snapshot.version(flagKey) ?: 0).toDouble()),
            "result" to result,
            "steps" to JVal.JArr(trace)
        )
    }

    fun resultOf(snapshot: FlagSnapshot, flagKey: String, context: JVal.JObj): JVal =
        evalInternal(snapshot, flagKey, context, mutableListOf()).first

    private fun evalInternal(
        snapshot: FlagSnapshot,
        flagKey: String,
        context: JVal.JObj,
        stack: MutableList<String>
    ): Pair<JVal, MutableList<JVal>> {
        if (flagKey in stack) {
            val path = stack.dropWhile { it != flagKey } + flagKey
            throw CycleException(path)
        }
        val def = snapshot.def(flagKey) ?: throw EvalException("flag not found: $flagKey")
        val steps = mutableListOf<JVal>()
        stack.add(flagKey)
        try {
            // 1. prerequisites
            for (pre in def.prerequisites) {
                val preDef = snapshot.def(pre)
                if (preDef == null) {
                    steps.add(objOf(
                        "type" to JVal.JStr("prerequisite"),
                        "flag" to JVal.JStr(pre),
                        "status" to JVal.JStr("missing"),
                        "met" to JVal.JBool(false)
                    ))
                    steps.add(objOf(
                        "type" to JVal.JStr("final"),
                        "reason" to JVal.JStr("prerequisite '$pre' not found; falling back to default"),
                        "value" to def.defaultValue
                    ))
                    return def.defaultValue to steps
                }
                val (preResult, preSteps) = evalInternal(snapshot, pre, context, stack)
                val on = preResult == JVal.JBool(true)
                steps.add(objOf(
                    "type" to JVal.JStr("prerequisite"),
                    "flag" to JVal.JStr(pre),
                    "version" to JVal.JNum((snapshot.version(pre) ?: 0).toDouble()),
                    "status" to JVal.JStr(if (on) "on" else "off"),
                    "met" to JVal.JBool(on),
                    "result" to preResult,
                    "steps" to JVal.JArr(preSteps)
                ))
                if (!on) {
                    steps.add(objOf(
                        "type" to JVal.JStr("final"),
                        "reason" to JVal.JStr("prerequisite '$pre' evaluated to off; falling back to default"),
                        "value" to def.defaultValue
                    ))
                    return def.defaultValue to steps
                }
            }

            // 2. rules in order
            def.rules.forEachIndexed { idx, rule ->
                val condSteps = mutableListOf<JVal>()
                var matched = true
                for (cond in rule.conditions) {
                    val r = evalCondition(cond, context)
                    condSteps.add(r.first)
                    if (!r.second) { matched = false; break }
                }
                steps.add(objOf(
                    "type" to JVal.JStr("rule"),
                    "index" to JVal.JNum(idx.toDouble()),
                    "name" to JVal.JStr(rule.name),
                    "matched" to JVal.JBool(matched),
                    "conditions" to JVal.JArr(condSteps)
                ))
                if (matched) {
                    val (value, outcomeSteps) = applyOutcome(def, rule.outcome, context)
                    steps.addAll(outcomeSteps)
                    steps.add(objOf(
                        "type" to JVal.JStr("final"),
                        "reason" to JVal.JStr("rule ${idx + 1} '${rule.name}' matched"),
                        "value" to value
                    ))
                    return value to steps
                }
            }

            // 3. flag-level percentage rollout
            if (def.rollout.isNotEmpty()) {
                val (value, rolloutSteps) = applySplit(def, def.rollout, context, "rollout")
                steps.addAll(rolloutSteps)
                if (value != null) {
                    steps.add(objOf(
                        "type" to JVal.JStr("final"),
                        "reason" to JVal.JStr("no rule matched; percentage rollout applied"),
                        "value" to value
                    ))
                    return value to steps
                }
            }

            // 4. default
            steps.add(objOf(
                "type" to JVal.JStr("final"),
                "reason" to JVal.JStr("no rule matched; default value"),
                "value" to def.defaultValue
            ))
            return def.defaultValue to steps
        } finally {
            stack.removeAt(stack.size - 1)
        }
    }

    /** Returns condition trace step and whether it passed. Missing and null are distinct. */
    private fun evalCondition(cond: Condition, context: JVal.JObj): Pair<JVal, Boolean> {
        val present = cond.field in context.fields
        val actual = context.fields[cond.field]
        val readStep = objOf(
            "type" to JVal.JStr("context-read"),
            "field" to JVal.JStr(cond.field),
            "status" to JVal.JStr(if (!present) "missing" else if (actual is JVal.JNull) "null" else "present"),
            "actual" to (if (present) display(cond.field, actual!!) else JVal.JNull)
        )
        val (pass, reason) = when {
            !present -> false to "field missing"
            actual is JVal.JNull -> false to "field is null"
            else -> compare(cond.op, actual!!, cond.value)
        }
        return objOf(
            "type" to JVal.JStr("condition"),
            "read" to readStep,
            "op" to JVal.JStr(cond.op.id),
            "expected" to display(cond.field, cond.value),
            "pass" to JVal.JBool(pass),
            "reason" to JVal.JStr(reason)
        ) to pass
    }

    /** Strict comparison: strings and numbers never implicitly convert. */
    private fun compare(op: Op, actual: JVal, expected: JVal): Pair<Boolean, String> {
        if (op == Op.IN) {
            val items = (expected as? JVal.JArr)?.items
                ?: return false to "expected value for 'in' must be an array"
            val hit = items.any { strictEquals(actual, it) }
            return hit to if (hit) "found in list" else "not found in list"
        }
        return when {
            actual is JVal.JNum && expected is JVal.JNum -> compareOrdered(op, actual.v, expected.v)
            actual is JVal.JStr && expected is JVal.JStr -> when (op) {
                Op.CONTAINS -> (expected.v in actual.v) to
                    if (expected.v in actual.v) "string contains substring" else "substring absent"
                else -> compareOrdered(op, actual.v, expected.v)
            }
            actual is JVal.JBool && expected is JVal.JBool -> when (op) {
                Op.EQ -> (actual.v == expected.v) to "boolean equality"
                Op.NEQ -> (actual.v != expected.v) to "boolean inequality"
                else -> false to "operator ${op.id} not supported for booleans"
            }
            actual::class != expected::class ->
                false to "type mismatch: ${typeName(actual)} vs ${typeName(expected)} (no implicit conversion)"
            else -> when (op) {
                Op.EQ -> (actual == expected) to "equality"
                Op.NEQ -> (actual != expected) to "inequality"
                else -> false to "operator ${op.id} not supported for ${typeName(actual)}"
            }
        }
    }

    private fun strictEquals(a: JVal, b: JVal): Boolean =
        a::class == b::class && a == b

    private fun compareOrdered(op: Op, a: Double, b: Double): Pair<Boolean, String> {
        val pass = when (op) {
            Op.EQ -> a == b; Op.NEQ -> a != b; Op.LT -> a < b
            Op.LTE -> a <= b; Op.GT -> a > b; Op.GTE -> a >= b
            else -> return false to "operator ${op.id} not supported for numbers"
        }
        return pass to "numeric ${op.id} comparison"
    }

    private fun compareOrdered(op: Op, a: String, b: String): Pair<Boolean, String> {
        val c = a.compareTo(b)
        val pass = when (op) {
            Op.EQ -> c == 0; Op.NEQ -> c != 0; Op.LT -> c < 0
            Op.LTE -> c <= 0; Op.GT -> c > 0; Op.GTE -> c >= 0
            else -> return false to "operator ${op.id} not supported for strings"
        }
        return pass to "string ${op.id} comparison"
    }

    private fun typeName(v: JVal): String = when (v) {
        is JVal.JNull -> "null"
        is JVal.JBool -> "boolean"
        is JVal.JNum -> "number"
        is JVal.JStr -> "string"
        is JVal.JArr -> "array"
        is JVal.JObj -> "object"
    }

    private fun applyOutcome(def: FlagDef, outcome: RuleOutcome, context: JVal.JObj): Pair<JVal, List<JVal>> {
        outcome.value?.let { return it to emptyList() }
        val (value, steps) = applySplit(def, outcome.split ?: emptyList(), context, "rule-split")
        return (value ?: def.defaultValue) to steps
    }

    /**
     * Percentage split. Bucket derives only from (flagKey, salt, stableId), so reordering
     * rules never changes the bucket as long as the split clause itself is unchanged.
     * Returns null value when the stable identity is unavailable (caller falls back).
     */
    private fun applySplit(
        def: FlagDef, variants: List<Variant>, context: JVal.JObj, kind: String
    ): Pair<JVal?, List<JVal>> {
        val idPresent = def.stableIdField in context.fields
        val idVal = context.fields[def.stableIdField]
        val readStep = objOf(
            "type" to JVal.JStr("context-read"),
            "field" to JVal.JStr(def.stableIdField),
            "status" to JVal.JStr(if (!idPresent) "missing" else if (idVal is JVal.JNull) "null" else "present"),
            "actual" to (if (idPresent) display(def.stableIdField, idVal!!) else JVal.JNull)
        )
        if (!idPresent || idVal is JVal.JNull) {
            return null to listOf(objOf(
                "type" to JVal.JStr("bucket"),
                "kind" to JVal.JStr(kind),
                "read" to readStep,
                "status" to JVal.JStr("skipped"),
                "reason" to JVal.JStr("stable identity '${def.stableIdField}' unavailable; cannot bucket")
            ))
        }
        val stableId = when (idVal) {
            is JVal.JStr -> idVal.v
            is JVal.JNum -> Json.render(idVal)
            else -> Json.render(idVal!!)
        }
        val bucket = Murmur3.bucket(def.key, def.salt, stableId)
        val bucketPct = bucket / 1000.0
        var cumulative = 0.0
        var chosen: Variant? = null
        val ranges = variants.map { v ->
            val from = cumulative
            cumulative += v.weight
            val hit = chosen == null && bucketPct >= from && bucketPct < cumulative
            if (hit) chosen = v
            objOf(
                "variant" to JVal.JStr(v.name),
                "from" to JVal.JNum(from),
                "to" to JVal.JNum(cumulative),
                "hit" to JVal.JBool(hit)
            )
        }
        val step = objOf(
            "type" to JVal.JStr("bucket"),
            "kind" to JVal.JStr(kind),
            "read" to readStep,
            "status" to JVal.JStr(if (chosen != null) "assigned" else "unassigned"),
            "algorithm" to JVal.JStr("murmur3_32(flagKey:salt:stableId) mod 100000 / 1000"),
            "input" to objOf(
                "flagKey" to JVal.JStr(def.key),
                "salt" to JVal.JStr(def.salt),
                "stableId" to JVal.JStr(stableId)
            ),
            "bucket" to JVal.JNum(bucketPct),
            "ranges" to JVal.JArr(ranges)
        )
        return chosen?.value to listOf(step)
    }
}
