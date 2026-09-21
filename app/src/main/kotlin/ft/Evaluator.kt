package ft

/** Result of evaluating one flag. */
data class EvalResult(val value: JsonValue, val trace: TraceNode)

/**
 * A version snapshot used for one batch request: flagKey -> pinned version or
 * current at snapshot time. A shared memo means each prerequisite flag is
 * evaluated once per batch; traces still embed the dependency's own subtree.
 */
class Snapshot(
    val project: Project,
    val pinned: Map<String, Int>? = null,
    val memo: MutableMap<String, EvalResult> = mutableMapOf(),
) {
    fun currentVersionNumber(flagKey: String): Int? =
        project.flags[flagKey]?.let { pinned?.get(it.key) ?: it.currentVersion }
    fun current(flagKey: String): FlagVersion? {
        val v = currentVersionNumber(flagKey) ?: return null
        return project.flags[flagKey]?.versions?.get(v)
    }
}

object Evaluator {

    fun evaluate(
        project: Project,
        flagKey: String,
        context: JObj,
        version: Int? = null,
        snapshot: Snapshot? = null,
    ): EvalResult {
        val snap = snapshot ?: Snapshot(project)
        val vnum = version ?: snap.currentVersionNumber(flagKey)
        val memoKey = flagKey + "#" + vnum
        return snap.memo.getOrPut(memoKey) {
            evalInternal(project, flagKey, context, version, snap, chain = emptyList())
        }
    }

    /** Internal evaluation that participates in prerequisite-cycle tracking. */
    private fun evaluateChained(
        project: Project,
        flagKey: String,
        context: JObj,
        version: Int,
        snap: Snapshot,
        chain: List<String>,
    ): EvalResult {
        val memoKey = "$flagKey#$version"
        snap.memo[memoKey]?.let { return it }
        return evalInternal(project, flagKey, context, version, snap, chain).also {
            snap.memo[memoKey] = it
        }
    }

    /**
     * @param chain flag keys currently being evaluated (outermost first).
     *   Used to reject prerequisite cycles with a concrete path.
     */
    private fun evalInternal(
        project: Project,
        flagKey: String,
        context: JObj,
        version: Int?,
        snap: Snapshot,
        chain: List<String>,
    ): EvalResult {
        val flag = project.flags[flagKey] ?: throw AppException(404, "flag '$flagKey' not found")
        val vnum = version ?: snap.currentVersionNumber(flagKey)
            ?: throw AppException(404, "flag '$flagKey' not found in snapshot")
        val fv = flag.versions[vnum]
            ?: throw AppException(404, "flag '$flagKey' version $vnum not found")

        val reads = mutableListOf<String>()
        val children = mutableListOf<TraceNode>()

        // 1) prerequisite
        val prereqNode = evaluatePrerequisite(project, flagKey, fv, context, snap, reads, chain)
        children.add(prereqNode)
        val prereqPassed = (prereqNode.detail["passed"] as? JBool)?.value == true
        if (!prereqPassed) {
            return buildTrace(flagKey, vnum, fv.default, "prerequisite_blocked", reads, children)
        }

        // 2) rules in declared order
        var sawUncoveredRollout = false
        for (rule in fv.rules) {
            val ruleNode = evaluateRule(project.domainSecret, flagKey, fv, rule, context, reads)
            children.add(ruleNode)
            when (ruleNode.outcome) {
                "matched_value" ->
                    return buildTrace(flagKey, vnum, rule.value!!, "value", reads, children)
                "matched_rollout" -> {
                    val rollNode = ruleNode.children.firstOrNull { it.step == "rollout" }
                    val matched = (rollNode?.detail?.get("rolloutMatched") as? JBool)?.value == true
                    val chosen = rollNode?.detail?.get("chosen")
                    if (matched && chosen != null) {
                        val value = when (fv.type) {
                            FlagType.BOOLEAN -> chosen.let { (it as JStr).value }
                                .let { Json.b(it == "on" || it == "true") }
                            FlagType.STRING -> chosen
                        }
                        return buildTrace(flagKey, vnum, value, "value", reads, children)
                    }
                    sawUncoveredRollout = true // bucket in uncovered tail; keep going
                }
                else -> { /* condition_failed -> next rule */ }
            }
        }

        // 3) default
        return buildTrace(
            flagKey, vnum, fv.default,
            if (sawUncoveredRollout) "default_after_rollout_gap" else "default",
            reads, children,
        )
    }

    private fun buildTrace(
        flagKey: String, vnum: Int, value: JsonValue, outcome: String,
        reads: MutableList<String>, children: List<TraceNode>,
    ): EvalResult {
        val node = TraceNode(
            step = "evaluate",
            outcome = outcome,
            detail = Json.obj(
                "flagKey" to Json.s(flagKey),
                "version" to Json.n(vnum),
                "result" to value,
                "reads" to JArr(reads.distinct().map { Json.s(it) }),
            ),
            reads = reads.distinct(),
            children = children,
        )
        return EvalResult(value, node)
    }

    private fun evaluatePrerequisite(
        project: Project,
        flagKey: String,
        fv: FlagVersion,
        context: JObj,
        snap: Snapshot,
        reads: MutableList<String>,
        chain: List<String>,
    ): TraceNode {
        val prereqKey = fv.prerequisiteKey
            ?: return TraceNode("prerequisite", "none",
                Json.obj(
                    "reason" to Json.s("no prerequisite declared"),
                    "passed" to Json.b(true),
                ),
                READS_NONE, emptyList())

        // Cycle rejection happens before using any dependency value.
        if (prereqKey in chain) {
            val path = (chain + prereqKey).let {
                // rotate so the repeated node frames the cycle clearly
                val start = it.indexOf(prereqKey)
                it.subList(start, it.size) + prereqKey
            }
            throw AppException(422, "prerequisite cycle detected: " + path.joinToString(" -> "))
        }

        val prereqVersion = snap.current(prereqKey)
            ?: return TraceNode(
                "prerequisite", "missing_dependency",
                Json.obj(
                    "requiredFlag" to Json.s(prereqKey),
                    "reason" to Json.s("dependency flag has no version in this snapshot"),
                ),
                READS_NONE, emptyList(),
            )

        val dep = evaluateChained(project, prereqKey, context, prereqVersion.version, snap, chain + flagKey)
        val expected = fv.prerequisiteExpected
        val passed = expected != null && Json.equal(dep.value, expected)
        val detail = Json.obj(
            "requiredFlag" to Json.s(prereqKey),
            "requiredVersion" to Json.n(prereqVersion.version),
            "actual" to dep.value,
            "expected" to (expected ?: JNull),
            "passed" to Json.b(passed),
        )
        return TraceNode("prerequisite", if (passed) "passed" else "failed", detail, READS_NONE, listOf(dep.trace))
    }
}

private val READS_NONE: List<String> = emptyList()

/** Field read result: a missing key and an explicit null are distinct. */
private data class FieldRead(val present: Boolean, val value: JsonValue)

private fun readField(context: JObj, field: String, reads: MutableList<String>): FieldRead {
    reads.add(field)
    if (!field.contains('.')) {
        val v = context.map[field] ?: return FieldRead(present = false, JNull)
        return FieldRead(present = true, v)
    }
    var cur: JsonValue = context
    for (part in field.split('.')) {
        if (cur !is JObj || cur.map[part] == null) return FieldRead(present = false, JNull)
        cur = cur.map[part]!!
    }
    return FieldRead(present = true, cur)
}

private fun evaluateRule(
    domainSecret: String,
    flagKey: String,
    fv: FlagVersion,
    rule: Rule,
    context: JObj,
    reads: MutableList<String>,
): TraceNode {
    val condNodes = mutableListOf<TraceNode>()
    var allPassed = true
    for (c in rule.conditions) {
        val node = evaluateCondition(domainSecret, fv, c, context, reads)
        condNodes.add(node)
        if (node.outcome != "passed") allPassed = false
    }
    if (!allPassed) {
        return TraceNode(
            step = "rule",
            outcome = "condition_failed",
            detail = Json.obj(
                "ruleId" to Json.s(rule.id),
                "reason" to Json.s("one or more conditions did not pass"),
            ),
            reads = READS_NONE,
            children = condNodes,
        )
    }

    if (rule.rollout != null) {
        val rollNode = evaluateRollout(domainSecret, flagKey, fv, rule, context, reads)
        return TraceNode(
            step = "rule",
            outcome = "matched_rollout",
            detail = Json.obj("ruleId" to Json.s(rule.id)),
            reads = READS_NONE,
            children = condNodes + rollNode,
        )
    }
    val value = rule.value
        ?: throw AppException(500, "rule ${rule.id} has neither value nor rollout")
    return TraceNode(
        step = "rule",
        outcome = "matched_value",
        detail = Json.obj("ruleId" to Json.s(rule.id), "value" to value),
        reads = READS_NONE,
        children = condNodes,
    )
}

private fun evaluateRollout(
    domainSecret: String,
    flagKey: String,
    fv: FlagVersion,
    rule: Rule,
    context: JObj,
    reads: MutableList<String>,
): TraceNode {
    val slices = rule.rollout ?: error("rollout missing")
    val identityRead = readField(context, fv.stableIdentityField, reads)
    val children = mutableListOf<TraceNode>()
    if (!identityRead.present || identityRead.value is JNull) {
        return TraceNode(
            step = "rollout",
            outcome = "no_identity",
            detail = Json.obj(
                "stableIdentityField" to Json.s(fv.stableIdentityField),
                "present" to Json.b(identityRead.present),
                "reason" to Json.s(
                    if (identityRead.present) "identity field is null" else "identity field is missing",
                ),
            ),
            reads = READS_NONE,
            children = emptyList(),
        )
    }
    val identity = scalarString(identityRead.value)
    if (identity == null) {
        return TraceNode(
            step = "rollout",
            outcome = "no_identity",
            detail = Json.obj(
                "stableIdentityField" to Json.s(fv.stableIdentityField),
                "reason" to Json.s("identity must be a string or number"),
            ),
            reads = READS_NONE,
            children = emptyList(),
        )
    }
    val input = Hashing.bucketInput(flagKey, fv.salt, identity)
    val bucket = Hashing.bucket(flagKey, fv.salt, identity)
    var start = 0
    var chosen: String? = null
    for (slice in slices) {
        val end = start + slice.weightBp
        val inside = bucket in start until end
        val sliceNode = TraceNode(
            step = "slice",
            outcome = if (inside) "hit" else "miss",
            detail = Json.obj(
                "variant" to Json.s(slice.variant),
                "startBp" to Json.n(start),
                "endBp" to Json.n(end),
            ),
            reads = READS_NONE,
            children = emptyList(),
        )
        children.add(sliceNode)
        if (inside && chosen == null) chosen = slice.variant
        start = end
    }
    val matched = chosen != null
    val pairs = mutableListOf(
        "algorithm" to Json.s("sha256-first8hex-mod-10000"),
        "bucketInput" to Json.s(input),
        "stableIdentityField" to Json.s(fv.stableIdentityField),
        "bucketBp" to Json.n(bucket),
        "rolloutMatched" to Json.b(matched),
    )
    if (chosen != null) pairs.add("chosen" to Json.s(chosen))
    return TraceNode(
        step = "rollout",
        outcome = if (matched) "hit" else "uncovered_tail",
        detail = Json.obj(*pairs.toTypedArray()),
        reads = READS_NONE,
        children = children,
    )
}

/** Numbers are stringified canonically for the bucket identity; booleans/null/objects are not identities. */
private fun scalarString(v: JsonValue): String? = when (v) {
    is JStr -> v.value
    is JNum -> if (v.isIntegral) v.num.toLong().toString() else Json.trimDouble(v.num)
    else -> null
}

private fun evaluateCondition(
    domainSecret: String,
    fv: FlagVersion,
    c: Condition,
    context: JObj,
    reads: MutableList<String>,
): TraceNode {
    val read = readField(context, c.field, reads)
    val sensitive = c.field in fv.sensitiveFields

    fun valueDetail(v: JsonValue): Pair<String, JsonValue> =
        if (sensitive) {
            "valueSummary" to Json.obj(
                "kind" to Json.s("sensitive"),
                "tag" to Json.s(Hashing.summaryTag(domainSecret, c.field, v)),
            )
        } else {
            "value" to v
        }

    fun node(outcome: String, extra: List<Pair<String, JsonValue?>>): TraceNode {
        val pairs = mutableListOf<Pair<String, JsonValue?>>(
            "field" to Json.s(c.field),
            "op" to Json.s(c.op.wire),
            "present" to Json.b(read.present),
            "sensitive" to Json.b(sensitive),
        )
        if (read.present) pairs.add(valueDetail(read.value))
        pairs.addAll(extra)
        return TraceNode("condition", outcome, Json.obj(*pairs.toTypedArray()), READS_NONE, emptyList())
    }

    fun fail(reason: String, extra: List<Pair<String, JsonValue?>> = emptyList()) =
        node("failed", listOf("reason" to Json.s(reason)) + extra)

    return when (c.op) {
        Operator.EXISTS -> {
            val exists = read.present && read.value !is JNull
            node(if (exists) "passed" else "failed", listOf(
                "reason" to Json.s(
                    when {
                        !read.present -> "field missing"
                        read.value is JNull -> "field is null"
                        else -> "field present"
                    }
                ),
            ))
        }

        Operator.EQ -> {
            if (!read.present) fail("field missing", listOf("expected" to c.argument))
            else if (!Json.equal(read.value, c.argument)) {
                val mismatch = typeMismatch(read.value, c.argument)
                fail(if (mismatch) "type mismatch: values are not compared across types" else "values differ",
                    listOf("expected" to c.argument))
            } else node("passed", listOf("expected" to c.argument))
        }

        Operator.NEQ -> {
            if (!read.present) fail("field missing", listOf("expected" to c.argument))
            else if (Json.equal(read.value, c.argument))
                fail("values equal", listOf("expected" to c.argument))
            else node("passed", listOf("expected" to c.argument))
        }

        Operator.CONTAINS -> {
            val v = read.value
            val arg = c.argument
            when {
                !read.present -> fail("field missing")
                v is JStr && arg is JStr ->
                    node(if (v.value.contains(arg.value)) "passed" else "failed",
                        listOf(
                            "argument" to arg,
                            "reason" to Json.s(if (v.value.contains(arg.value)) "substring found" else "substring not found"),
                        ))
                v is JArr -> {
                    if (!v.items.any { Json.equal(it, arg) })
                        fail("array does not contain element", listOf("argument" to arg))
                    else node("passed", listOf("argument" to arg))
                }
                else -> fail("type mismatch: contains needs string/string or array/element")
            }
        }

        Operator.IN, Operator.NOT_IN -> {
            val arg = c.argument
            if (arg !is JArr) return fail("operator argument must be an array")
            if (!read.present) return fail("field missing")
            val member = arg.items.any { Json.equal(it, read.value) }
            val sameTypeExists = arg.items.any { sameComparableType(it, read.value) }
            val outcome = when (c.op) {
                Operator.IN -> member
                else -> !member
            }
            when {
                outcome -> node("passed", listOf("argument" to arg))
                !sameTypeExists -> fail("type mismatch: no array element shares the field's type", listOf("argument" to arg))
                else -> fail("value not in set", listOf("argument" to arg))
            }
        }

        Operator.GT, Operator.GTE, Operator.LT, Operator.LTE -> {
            val arg = c.argument
            if (!read.present) return fail("field missing")
            val v = read.value
            val cmp = compareStrict(v, arg)
                ?: return fail("type mismatch: ordering needs both numbers or both strings")
            val ok = when (c.op) {
                Operator.GT -> cmp > 0
                Operator.GTE -> cmp >= 0
                Operator.LT -> cmp < 0
                Operator.LTE -> cmp <= 0
                else -> false
            }
            node(if (ok) "passed" else "failed", listOf("argument" to arg))
        }
    }
}

/** A type mismatch worth naming explicitly (e.g. string vs number) rather than a plain inequality. */
private fun typeMismatch(a: JsonValue, b: JsonValue): Boolean = when {
    a is JStr && b is JNum -> true
    a is JNum && b is JStr -> true
    a is JBool != (b is JBool) && (a is JStr || a is JNum || b is JStr || b is JNum) -> true
    else -> false
}

private fun sameComparableType(a: JsonValue, b: JsonValue): Boolean =
    (a is JNum && b is JNum) || (a is JStr && b is JStr) ||
        (a is JBool && b is JBool) || (a is JArr && b is JArr) ||
        (a is JObj && b is JObj) || (a === JNull && b === JNull)

/** Numbers compare numerically, strings lexicographically; anything else returns null. */
private fun compareStrict(a: JsonValue, b: JsonValue): Int? = when {
    a is JNum && b is JNum -> a.num.compareTo(b.num)
    a is JStr && b is JStr -> a.value.compareTo(b.value)
    else -> null
}
