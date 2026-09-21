package ft

/** Resolves prerequisites against an immutable snapshot. */
fun interface FlagResolver {
    /** Returns the pinned version definition, or null when the flag is absent. */
    fun resolve(projectId: String, key: String): FlagVersion?
}

data class EvalResult(
    val flagKey: String,
    val version: Int,
    val result: ServeValue,
    val trace: JsonObject
)

/**
 * Evaluates a single flag version against a context and records a complete
 * step-by-step trace: fields read, each condition's pass/fail with reason,
 * rollout bucketing input, and prerequisite dependency outcomes.
 */
class Evaluator(
    private val project: Project,
    private val resolver: FlagResolver,
    private val sharedReads: LinkedHashMap<String, JsonObject>? = null
) {
    private val fieldReads: LinkedHashMap<String, JsonObject> = sharedReads ?: linkedMapOf()
    private var currentStableIdField: String = "user.id"

    fun evaluate(
        flagKey: String,
        definition: FlagVersion,
        context: JsonObject,
        stack: List<String> = emptyList()
    ): EvalResult {
        if (sharedReads == null) fieldReads.clear()
        currentStableIdField = definition.stableIdField
        val steps = jsonArray { }
        val items = steps.items.toMutableList()

        // 1) Prerequisites, in declared order.
        for (prerequisite in definition.prerequisites) {
            val step = evaluatePrerequisite(flagKey, prerequisite, context, stack)
            items.add(step.trace)
            if (!step.passed) {
                val trace = finalTrace(
                    flagKey, definition, context, items, step.served,
                    reason = "prerequisite_failed",
                    failedPrerequisite = prerequisite.flagKey
                )
                return EvalResult(flagKey, definition.version, step.served, trace)
            }
        }

        // 2) Ordered rules; first match wins.
        for (rule in definition.rules) {
            val step = evaluateRule(flagKey, rule, context)
            items.add(step.trace)
            if (step.served != null) {
                val trace = finalTrace(
                    flagKey, definition, context, items, step.served,
                    reason = "rule_matched", matchedRule = rule.id
                )
                return EvalResult(flagKey, definition.version, step.served, trace)
            }
        }

        // 3) Default value.
        items.add(jsonObject {
            "type" to json("default")
            "served" to json(definition.defaultValue.name)
            "note" to json("no rule matched; serving default value")
        })
        val trace = finalTrace(
            flagKey, definition, context, items, definition.defaultValue,
            reason = "default"
        )
        return EvalResult(flagKey, definition.version, definition.defaultValue, trace)
    }

    private fun finalTrace(
        flagKey: String,
        definition: FlagVersion,
        context: JsonObject,
        steps: List<JsonValue>,
        served: ServeValue,
        reason: String,
        matchedRule: String? = null,
        failedPrerequisite: String? = null
    ): JsonObject = jsonObject {
        "flagKey" to json(flagKey)
        "version" to json(definition.version)
        "result" to json(served.name)
        "outcome" to json(reason)
        if (matchedRule != null) "matchedRule" to json(matchedRule)
        if (failedPrerequisite != null) "failedPrerequisite" to json(failedPrerequisite)
        "stableIdField" to json(definition.stableIdField)
        "steps" to JsonArray(steps)
        "fieldReads" to JsonObject(LinkedHashMap(fieldReads))
    }

    private data class PrereqStep(val passed: Boolean, val served: ServeValue, val trace: JsonObject)

    private fun evaluatePrerequisite(
        parentKey: String,
        prerequisite: Prerequisite,
        context: JsonObject,
        stack: List<String>
    ): PrereqStep {
        val cycle = parentKey in stack
        if (cycle) {
            val path = (stack + parentKey + prerequisite.flagKey)
            return PrereqStep(
                false, prerequisite.gateServe, jsonObject {
                    "type" to json("prerequisite")
                    "flagKey" to json(prerequisite.flagKey)
                    "passed" to json(false)
                    "served" to json(prerequisite.gateServe.name)
                    "error" to json("dependency_cycle")
                    "cyclePath" to JsonArray(path.map { json(it) })
                }
            )
        }

        val target = resolver.resolve(project.id, prerequisite.flagKey)
        if (target == null) {
            return PrereqStep(
                false, prerequisite.gateServe, jsonObject {
                    "type" to json("prerequisite")
                    "flagKey" to json(prerequisite.flagKey)
                    "passed" to json(false)
                    "served" to json(prerequisite.gateServe.name)
                    "reason" to json("prerequisite flag not found; gate value applied")
                }
            )
        }

        // Share the field-read ledger so nested prerequisite reads are recorded
        // in the parent trace as well.
        val nested = Evaluator(project, resolver, fieldReads).evaluate(
            prerequisite.flagKey, target, context, stack + parentKey
        )
        val accepted = prerequisite.anyOf.any { it.name == nested.result.name }
        val served = if (accepted) nested.result else prerequisite.gateServe
        return PrereqStep(
            accepted,
            served,
            jsonObject {
                "type" to json("prerequisite")
                "flagKey" to json(prerequisite.flagKey)
                "version" to json(target.version)
                "requiredAnyOf" to JsonArray(prerequisite.anyOf.map { json(it.name) })
                "observed" to json(nested.result.name)
                "passed" to json(accepted)
                if (!accepted) "served" to json(prerequisite.gateServe.name)
                "nestedTrace" to nested.trace
            }
        )
    }

    private data class RuleStep(val served: ServeValue?, val trace: JsonObject)

    private fun evaluateRule(flagKey: String, rule: Rule, context: JsonObject): RuleStep {
        val conditionTraces = mutableListOf<JsonObject>()
        var matched = true
        for (condition in rule.conditions) {
            val outcome = evaluateCondition(condition, context)
            conditionTraces.add(outcome)
            if (!outcome["passed"].asBoolean!!) {
                matched = false
                break
            }
        }

        if (!matched) {
            return RuleStep(
                null,
                jsonObject {
                    "type" to json("rule")
                    "ruleId" to json(rule.id)
                    "ruleName" to json(rule.name)
                    "matched" to json(false)
                    "conditions" to JsonArray(conditionTraces)
                    "note" to json("rule skipped: first failing condition stops evaluation")
                }
            )
        }

        val clauses = rule.rollout
        if (clauses.isNullOrEmpty()) {
            val served = rule.serve
                ?: throw IllegalStateException("rule '${rule.id}' has neither rollout nor serve")
            return RuleStep(
                served,
                jsonObject {
                    "type" to json("rule")
                    "ruleId" to json(rule.id)
                    "ruleName" to json(rule.name)
                    "matched" to json(true)
                    "conditions" to JsonArray(conditionTraces)
                    "served" to json(served.name)
                    "basis" to json("all conditions matched; fixed serve")
                }
            )
        }

        return evaluateRollout(flagKey, rule, clauses, conditionTraces, context)
    }

    private fun evaluateRollout(
        flagKey: String,
        rule: Rule,
        clauses: List<RolloutClause>,
        conditionTraces: List<JsonObject>,
        context: JsonObject
    ): RuleStep {
        val totalWeight = clauses.sumOf { it.weightBp }
        require(totalWeight <= 10000) {
            "rollout weights of rule '${rule.id}' exceed 10000 ($totalWeight)"
        }

        val stableIdField = currentStableIdField
        val idRead = readField(stableIdField, context)
        val stableIdValue = (idRead as? FieldRead.Found)?.value
        val stableIdPresent = stableIdValue is JsonString || stableIdValue is JsonNumber

        if (!stableIdPresent) {
            val served = rule.fallbackServe ?: rule.serve ?: ServeValue.OFF
            val idState = when (idRead) {
                is FieldRead.NullValue -> "null"
                is FieldRead.Missing -> "missing"
                else -> "invalid_type"
            }
            return RuleStep(
                served,
                jsonObject {
                    "type" to json("rule")
                    "ruleId" to json(rule.id)
                    "ruleName" to json(rule.name)
                    "matched" to json(true)
                    "conditions" to JsonArray(conditionTraces)
                    "rollout" to jsonObject {
                        "stableIdField" to json(stableIdField)
                        "stableIdState" to json(idState)
                        "note" to json(
                            "stable identity is absent or not a string/number; " +
                                "rollout cannot bucket deterministically"
                        )
                    }
                    "served" to json(served.name)
                    "basis" to json("rollout_unbucketable")
                }
            )
        }

        val idText = when (stableIdValue) {
            is JsonString -> stableIdValue.value
            is JsonNumber -> stableIdValue.raw
            else -> error("checked above")
        }
        val salt = rule.salt.ifEmpty { rule.id }
        val key = bucketingKey(flagKey, salt, idText)
        val bucket = Murmur3.bucket10k(key)

        var cursor = 0
        var chosenIndex: Int? = null
        val clauseTraces = clauses.mapIndexed { index, clause ->
            val start = cursor
            val end = cursor + clause.weightBp
            if (chosenIndex == null && bucket in start until end) chosenIndex = index
            cursor = end
            jsonObject {
                "index" to json(index)
                "serve" to json(clause.serve.name)
                "weightBp" to json(clause.weightBp)
                "rangeStartBp" to json(start)
                "rangeEndBp" to json(end)
                "containsBucket" to json(bucket in start until end)
            }
        }

        val chosen = chosenIndex
        val served = if (chosen != null) clauses[chosen].serve
        else rule.fallbackServe ?: rule.serve ?: ServeValue.OFF

        val sensitiveId = ContextPaths.isSensitive(
            stableIdField, project.sensitiveFields
        )
        return RuleStep(
            served,
            jsonObject {
                "type" to json("rule")
                "ruleId" to json(rule.id)
                "ruleName" to json(rule.name)
                "matched" to json(true)
                "conditions" to JsonArray(conditionTraces)
                "rollout" to jsonObject {
                    "stableIdField" to json(stableIdField)
                    "salt" to json(salt)
                    "algorithm" to json("murmur3_x86_32_seed0_mod_10000")
                    "hashInput" to json(
                        if (sensitiveId) {
                            "sha256hmac:" + SecretHash.digest(project.digestSalt, key)
                        } else key
                    )
                    "stableIdDigest" to json(
                        if (sensitiveId) SecretHash.digest(project.digestSalt, idText) else null
                    )
                    "bucketBp" to json(bucket)
                    "totalWeightBp" to json(totalWeight)
                    "clauses" to JsonArray(clauseTraces)
                    "servedByRollout" to json(chosen != null)
                }
                "served" to json(served.name)
                "basis" to json(if (chosen != null) "rollout_bucket" else "rollout_outside_ranges")
            }
        )
    }

    /** Reads a field, recording it once in the trace's field-reading ledger. */
    private fun readField(path: String, context: JsonObject): FieldRead {
        if (fieldReads.containsKey(path)) return ContextPaths.read(context, path)
        val read = ContextPaths.read(context, path)
        val sensitive = ContextPaths.isSensitive(path, project.sensitiveFields)
        val entry = jsonObject {
            "state" to json(
                when (read) {
                    is FieldRead.Missing -> "missing"
                    is FieldRead.NullValue -> "null"
                    is FieldRead.Found -> "present"
                }
            )
            "sensitive" to json(sensitive)
            when (read) {
                is FieldRead.Missing ->
                    "note" to json("field absent from context (distinct from null)")
                is FieldRead.NullValue ->
                    "note" to json("field present with explicit JSON null")
                is FieldRead.Found -> {
                    if (sensitive) {
                        val plain = displayScalar(read.value)
                        "type" to json(typeName(read.value))
                        "digest" to json(SecretHash.digest(project.digestSalt, plain))
                        "note" to json(
                            "value redacted; digest proves same input within this project"
                        )
                    } else {
                        "type" to json(typeName(read.value))
                        "value" to read.value
                    }
                }
            }
        }
        fieldReads[path] = entry
        return read
    }

    private fun evaluateCondition(condition: Condition, context: JsonObject): JsonObject {
        val read = ContextPaths.read(context, condition.field)
        readField(condition.field, context)
        val result = applyOperator(condition.operator, read, condition.value)
        return jsonObject {
            "field" to json(condition.field)
            "operator" to json(condition.operator.wire)
            "expected" to condition.value
            "state" to json(
                when (read) {
                    is FieldRead.Missing -> "missing"
                    is FieldRead.NullValue -> "null"
                    is FieldRead.Found -> "present"
                }
            )
            "passed" to json(result.passed)
            "reason" to json(result.reason)
        }
    }

    private data class ConditionResult(val passed: Boolean, val reason: String)

    private fun applyOperator(
        operator: Operator,
        read: FieldRead,
        expected: JsonValue?
    ): ConditionResult {
        return when (operator) {
            Operator.EXISTS -> {
                // Explicit JSON null still counts as the field being present.
                val wantExistence = (expected as? JsonBoolean)?.value ?: true
                val exists = read is FieldRead.Found || read is FieldRead.NullValue
                val state = when (read) {
                    is FieldRead.Found ->
                        if (read.value is JsonNull) "present with null" else "present"
                    is FieldRead.NullValue -> "present with null"
                    else -> "missing"
                }
                ConditionResult(exists == wantExistence, "field is $state")
            }
            else -> {
                if (read is FieldRead.Missing) {
                    return ConditionResult(false, "cannot compare: field missing")
                }
                if (read is FieldRead.NullValue) {
                    val equal = jsonEquals(JsonNull, expected ?: JsonNull)
                    return when (operator) {
                        Operator.EQ -> ConditionResult(
                            equal,
                            if (equal) "both are null"
                            else typeMismatchReason(JsonNull, expected ?: JsonNull, "not equal")
                        )
                        Operator.NE -> ConditionResult(
                            !equal, if (equal) "both are null" else "value is null"
                        )
                        else -> ConditionResult(
                            false,
                            "null cannot be used with ${operator.wire}; " +
                                "use eq null / ne null to test nullity"
                        )
                    }
                }
                val actual = (read as FieldRead.Found).value
                comparePresent(operator, actual, expected)
            }
        }
    }

    private fun comparePresent(
        operator: Operator,
        actual: JsonValue,
        expected: JsonValue?
    ): ConditionResult {
        return when (operator) {
            Operator.EQ -> {
                val equal = jsonEquals(actual, expected ?: JsonNull)
                ConditionResult(
                    equal,
                    if (equal) "values equal (strict types)"
                    else typeMismatchReason(actual, expected ?: JsonNull, "not equal")
                )
            }
            Operator.NE -> {
                val equal = jsonEquals(actual, expected ?: JsonNull)
                ConditionResult(
                    !equal,
                    if (!equal) "values differ" else "values are equal"
                )
            }
            Operator.GT, Operator.GTE, Operator.LT, Operator.LTE ->
                compareNumbers(operator, actual, expected)
            Operator.IN, Operator.NOT_IN -> compareMembership(operator, actual, expected)
            Operator.CONTAINS, Operator.STARTS_WITH, Operator.ENDS_WITH ->
                compareStrings(operator, actual, expected)
            Operator.EXISTS -> error("handled elsewhere")
        }
    }

    private fun compareNumbers(
        operator: Operator,
        actual: JsonValue,
        expected: JsonValue?
    ): ConditionResult {
        val a = actual as? JsonNumber
        val b = expected as? JsonNumber
        if (a == null || b == null) {
            return ConditionResult(
                false,
                "numeric comparison requires numbers on both sides " +
                    "(got ${typeName(actual)} vs ${typeName(expected ?: JsonNull)}); " +
                    "no implicit conversion"
            )
        }
        val cmp = a.double.compareTo(b.double)
        val passed = when (operator) {
            Operator.GT -> cmp > 0
            Operator.GTE -> cmp >= 0
            Operator.LT -> cmp < 0
            Operator.LTE -> cmp <= 0
            else -> false
        }
        return ConditionResult(passed, "numeric compare ${a.raw} ${operator.wire} ${b.raw}: $passed")
    }

    private fun compareMembership(
        operator: Operator,
        actual: JsonValue,
        expected: JsonValue?
    ): ConditionResult {
        val list = expected as? JsonArray
            ?: return ConditionResult(false, "operator ${operator.wire} expects an array")
        val member = list.items.any { jsonEquals(actual, it) }
        val passed = when (operator) {
            Operator.IN -> member
            Operator.NOT_IN -> !member
            else -> false
        }
        return ConditionResult(
            passed,
            if (member) "value found in list (strict types)"
            else "value not found in list (strict types)"
        )
    }

    private fun compareStrings(
        operator: Operator,
        actual: JsonValue,
        expected: JsonValue?
    ): ConditionResult {
        val a = actual as? JsonString
        val b = expected as? JsonString
        if (a == null || b == null) {
            return ConditionResult(
                false,
                "string operator requires strings on both sides " +
                    "(got ${typeName(actual)} vs ${typeName(expected ?: JsonNull)})"
            )
        }
        val passed = when (operator) {
            Operator.CONTAINS -> a.value.contains(b.value)
            Operator.STARTS_WITH -> a.value.startsWith(b.value)
            Operator.ENDS_WITH -> a.value.endsWith(b.value)
            else -> false
        }
        return ConditionResult(passed, "${operator.wire} test: $passed")
    }

    private fun typeMismatchReason(
        actual: JsonValue,
        expected: JsonValue,
        fallback: String
    ): String {
        if (typeName(actual) != typeName(expected)) {
            return "type mismatch: ${typeName(actual)} vs ${typeName(expected)}; " +
                "strings and numbers are never implicitly converted"
        }
        return fallback
    }

    /** Strict equality: no string/number coercion; numbers compared numerically. */
    private fun jsonEquals(a: JsonValue, b: JsonValue): Boolean = when {
        a is JsonString && b is JsonString -> a.value == b.value
        a is JsonBoolean && b is JsonBoolean -> a.value == b.value
        a is JsonNumber && b is JsonNumber -> a.double == b.double
        a is JsonNull && b is JsonNull -> true
        a is JsonArray && b is JsonArray ->
            a.items.size == b.items.size &&
                a.items.zip(b.items).all { (x, y) -> jsonEquals(x, y) }
        a is JsonObject && b is JsonObject ->
            a.members.keys == b.members.keys &&
                a.members.all { (k, v) -> jsonEquals(v, b.members.getValue(k)) }
        else -> false
    }

    private fun typeName(v: JsonValue): String = when (v) {
        is JsonString -> "string"
        is JsonNumber -> "number"
        is JsonBoolean -> "boolean"
        is JsonArray -> "array"
        is JsonObject -> "object"
        JsonNull -> "null"
    }

    private fun displayScalar(v: JsonValue): String = when (v) {
        is JsonString -> v.value
        is JsonNumber -> v.raw
        is JsonBoolean -> v.value.toString()
        JsonNull -> "null"
        is JsonArray, is JsonObject -> v.toJson("")
    }
}
