package tracker

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private data class FieldRead(
    val path: String,
    val state: String,
    val value: JsonValue,
    val sensitive: Boolean
)

private data class MemoKey(val flagKey: String, val contextKey: String)

class FlagEvaluator(private val snapshot: Snapshot) {
    private val memo = hashMapOf<MemoKey, EvaluationResult>()
    private val fieldPaths = mutableSetOf<String>()

    fun evaluate(flagKey: String, context: JsonObject): EvaluationResult {
        fieldPaths.clear()
        memo.clear()
        return evaluateInternal(flagKey, context, contextKey(context), ArrayDeque())
    }

    fun evaluateBatch(flagKeys: List<String>, context: JsonObject): List<EvaluationResult> {
        fieldPaths.clear()
        memo.clear()
        val contextKeyValue = contextKey(context)
        return flagKeys.map { evaluateInternal(it, context, contextKeyValue, ArrayDeque()) }
    }

    private fun evaluateInternal(
        flagKey: String,
        context: JsonObject,
        contextKeyValue: String,
        stack: ArrayDeque<String>
    ): EvaluationResult {
        val key = MemoKey(flagKey, contextKeyValue)
        memo[key]?.let { return it }
        val version = snapshot.versionsByFlagKey[flagKey]
            ?: return missingFlagResult(flagKey)
        if (stack.contains(flagKey)) {
            val cyclePath = stack.toList().dropWhile { it != flagKey } + flagKey
            throw CycleException(cyclePath)
        }
        stack.addLast(flagKey)
        val children = mutableListOf<TraceStep>()
        try {
            val prerequisiteResult = evaluatePrerequisites(version, context, contextKeyValue, stack, children)
            val result = if (prerequisiteResult != null) {
                buildResult(version, prerequisiteResult, "prerequisite", children)
            } else {
                val ruleMatch = evaluateRules(version, context, children)
                when {
                    ruleMatch != null -> buildResult(version, ruleMatch, "rule", children)
                    version.rollout != null -> {
                        val rolloutResult = evaluateRollout(version, version.rollout, context, children)
                        buildResult(version, rolloutResult, if (rolloutResult == version.defaultValue) "default" else "rollout", children)
                    }
                    else -> {
                        children += step("default", "使用默认值", "matched", mapOf(
                            "value" to version.defaultValue,
                            "reason" to JsonString("没有条件或分流命中")
                        ))
                        buildResult(version, version.defaultValue, "default", children)
                    }
                }
            }
            memo[key] = result
            return result
        } finally {
            stack.removeLast()
        }
    }

    private fun evaluatePrerequisites(
        version: FlagVersion,
        context: JsonObject,
        contextKeyValue: String,
        stack: ArrayDeque<String>,
        children: MutableList<TraceStep>
    ): JsonValue? {
        version.prerequisites.forEach { prerequisite ->
            val dependency = evaluateInternal(prerequisite.flagKey, context, contextKeyValue, stack)
            val matched = when (prerequisite.matcher) {
                PrerequisiteMatcher.ON -> dependency.result != JsonNull
                PrerequisiteMatcher.OFF -> dependency.result == JsonNull
                PrerequisiteMatcher.EQUALS -> strictEquals(dependency.result, prerequisite.expected)
            }
            val trace = step(
                type = "prerequisite",
                title = "前置开关 ${prerequisite.flagKey}",
                status = if (matched) "passed" else "failed",
                detail = mapOf(
                    "flagKey" to JsonString(prerequisite.flagKey),
                    "matcher" to JsonString(prerequisite.matcher.name),
                    "expected" to prerequisite.expected,
                    "actual" to dependency.result,
                    "onFailure" to version.defaultValue
                ),
                children = listOf(dependency.trace)
            )
            children += trace
            if (!matched) return version.defaultValue
        }
        return null
    }

    private fun evaluateRules(version: FlagVersion, context: JsonObject, children: MutableList<TraceStep>): JsonValue? {
        version.rules.forEach { rule ->
            val conditionSteps = mutableListOf<TraceStep>()
            val matched = rule.conditions.all { condition ->
                val outcome = evaluateCondition(condition, version, context)
                conditionSteps += outcome.step
                outcome.matched
            }
            children += step(
                type = "rule",
                title = rule.name,
                status = if (matched) "matched" else "failed",
                detail = mapOf(
                    "ruleId" to JsonString(rule.id),
                    "result" to rule.result
                ),
                children = conditionSteps
            )
            if (matched) return rule.result
        }
        return null
    }

    private fun evaluateCondition(condition: Condition, version: FlagVersion, context: JsonObject): ConditionOutcome {
        val read = readField(context, condition.field, condition.sensitive || condition.field in version.sensitiveFields)
        val matched = when (condition.operator) {
            CompareOperator.PRESENT -> read.state == "present"
            CompareOperator.EQUALS -> read.state == "present" && strictEquals(read.value, condition.value)
            CompareOperator.NOT_EQUALS -> read.state == "present" && !strictEquals(read.value, condition.value)
            CompareOperator.IN -> read.state == "present" && condition.value is JsonArray &&
                condition.value.values.any { strictEquals(read.value, it) }
            CompareOperator.GREATER_THAN,
            CompareOperator.GREATER_THAN_OR_EQUAL,
            CompareOperator.LESS_THAN,
            CompareOperator.LESS_THAN_OR_EQUAL -> compareNumbers(condition, read.value, read.state)
        }
        val trace = step(
            type = "condition",
            title = "${condition.field} ${label(condition.operator)}",
            status = if (matched) "passed" else "failed",
            detail = buildMap {
                put("field", JsonString(condition.field))
                put("operator", JsonString(condition.operator.name))
                put("state", JsonString(read.state))
                put("actual", valueForTrace(read))
                if (condition.operator != CompareOperator.PRESENT) put("expected", condition.value)
                if (read.state != "present" && condition.operator != CompareOperator.PRESENT) {
                    put("note", JsonString(if (read.state == "missing") "字段缺失" else "字段值为 null，不参与非 PRESENT 条件"))
                }
            }
        )
        return ConditionOutcome(matched, trace)
    }

    private fun evaluateRollout(
        version: FlagVersion,
        rollout: PercentageRollout,
        context: JsonObject,
        children: MutableList<TraceStep>
    ): JsonValue {
        val sensitive = rollout.identityField in version.sensitiveFields
        val read = readField(context, rollout.identityField, sensitive)
        if (read.state != "present" || read.value == JsonNull) {
            children += step("rollout", "百分比分流", "skipped", mapOf(
                "identityField" to JsonString(rollout.identityField),
                "state" to JsonString(read.state),
                "actual" to valueForTrace(read),
                "note" to JsonString("稳定身份缺失或为 null，使用默认值")
            ))
            return version.defaultValue
        }
        val identity = canonicalIdentity(read.value)
        val bucketInput = rolloutInput(version.key, identity, rollout.salt)
        val digest = sha256Hex(bucketInput)
        val bucket = digest.take(16).toLong(16).mod(10000)
        val orderedSlices = rollout.slices.sortedBy { it.id }
        var cumulative = 0
        var selected: PercentageSlice? = null
        orderedSlices.forEach { slice ->
            val start = cumulative
            cumulative += slice.weightBasisPoints
            if (selected == null && bucket in start until cumulative) selected = slice
        }
        val matched = selected != null
        children += step(
            type = "rollout",
            title = "百分比分流",
            status = if (matched) "matched" else "default",
            detail = mapOf(
                "algorithm" to JsonString("SHA-256(flagKey + stableIdentity + salt) first 64 bits mod 10000"),
                "identityField" to JsonString(rollout.identityField),
                "identity" to valueForTrace(read),
                "flagKey" to JsonString(version.key),
                "salt" to JsonString(rollout.salt),
                "bucketInput" to JsonString(bucketInput),
                "sha256" to JsonString(digest),
                "bucket" to JsonNumber(bucket.toString()),
                "basisPointsTotal" to JsonNumber(orderedSlices.sumOf { it.weightBasisPoints }.toString()),
                "matchedSliceId" to JsonString(selected?.id ?: "")
            ),
            children = orderedSlices.map { slice ->
                val start = orderedSlices.takeWhile { it.id < slice.id }.sumOf { it.weightBasisPoints }
                step(
                    "slice",
                    slice.name,
                    if (slice.id == selected?.id) "selected" else "not-selected",
                    mapOf(
                        "sliceId" to JsonString(slice.id),
                        "startInclusive" to JsonNumber(start.toString()),
                        "endExclusive" to JsonNumber((start + slice.weightBasisPoints).toString()),
                        "weightBasisPoints" to JsonNumber(slice.weightBasisPoints.toString()),
                        "result" to slice.result
                    )
                )
            }
        )
        return selected?.result ?: version.defaultValue
    }

    private fun readField(context: JsonObject, path: String, sensitive: Boolean): FieldRead {
        fieldPaths += path
        val value = readPath(context, path)
        return FieldRead(
            path = path,
            state = when (value) {
                JsonMissing -> "missing"
                JsonNull -> "null"
                else -> "present"
            },
            value = value,
            sensitive = sensitive
        )
    }

    private fun valueForTrace(read: FieldRead): JsonValue {
        if (read.state == "missing") return JsonString("<missing>")
        if (!read.sensitive) return read.value
        return JsonObject(
            mapOf(
                "sensitiveSummary" to JsonString(sensitiveSummary(read.value)),
                "summaryAlgorithm" to JsonString("HMAC-SHA256(projectSecret, SHA-256(canonicalValue))"),
                "sameValueProof" to JsonBoolean(true)
            )
        )
    }

    private fun sensitiveSummary(value: JsonValue): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(snapshot.secretHex.hexToByteArray(), "HmacSHA256"))
        val scopedInput = "feature-flag-sensitive-summary/v1\n" + canonicalIdentity(value)
        val digest = mac.doFinal(scopedInput.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun buildResult(version: FlagVersion, result: JsonValue, reason: String, children: List<TraceStep>) =
        EvaluationResult(
            flagKey = version.key,
            versionId = version.id,
            result = result,
            reason = reason,
            fieldsRead = fieldPaths.distinct(),
            trace = step(
                type = "evaluation",
                title = "${version.name} v${version.version}",
                status = "complete",
                detail = mapOf(
                    "flagKey" to JsonString(version.key),
                    "versionId" to JsonString(version.id),
                    "version" to JsonNumber(version.version.toString()),
                    "snapshotId" to JsonString(snapshot.snapshotId),
                    "result" to result,
                    "reason" to JsonString(reason)
                ),
                children = children
            )
        )

    private fun missingFlagResult(flagKey: String): EvaluationResult {
        val trace = step("evaluation", flagKey, "error", mapOf("error" to JsonString("版本快照中不存在该开关")))
        return EvaluationResult(flagKey, "", JsonNull, "missing", emptyList(), trace)
    }
}

private data class ConditionOutcome(val matched: Boolean, val step: TraceStep)

private fun step(
    type: String,
    title: String,
    status: String,
    detail: Map<String, JsonValue> = emptyMap(),
    children: List<TraceStep> = emptyList()
) = TraceStep(type, title, status, JsonObject(detail), children)

private fun label(operator: CompareOperator): String = when (operator) {
    CompareOperator.EQUALS -> "=="
    CompareOperator.NOT_EQUALS -> "!="
    CompareOperator.GREATER_THAN -> ">"
    CompareOperator.GREATER_THAN_OR_EQUAL -> ">="
    CompareOperator.LESS_THAN -> "<"
    CompareOperator.LESS_THAN_OR_EQUAL -> "<="
    CompareOperator.IN -> "in"
    CompareOperator.PRESENT -> "存在"
}

private fun compareNumbers(condition: Condition, actual: JsonValue, state: String): Boolean {
    if (state != "present" || actual !is JsonNumber || condition.value !is JsonNumber) return false
    val left = actual.number
    val right = condition.value.number
    return when (condition.operator) {
        CompareOperator.GREATER_THAN -> left > right
        CompareOperator.GREATER_THAN_OR_EQUAL -> left >= right
        CompareOperator.LESS_THAN -> left < right
        CompareOperator.LESS_THAN_OR_EQUAL -> left <= right
        else -> false
    }
}

private fun strictEquals(left: JsonValue, right: JsonValue): Boolean {
    if (left::class != right::class) return false
    return when {
        left is JsonString && right is JsonString -> left.value == right.value
        left is JsonBoolean && right is JsonBoolean -> left.value == right.value
        left is JsonNumber && right is JsonNumber -> left.number == right.number
        left is JsonArray && right is JsonArray ->
            left.values.size == right.values.size && left.values.zip(right.values).all { strictEquals(it.first, it.second) }
        left is JsonObject && right is JsonObject ->
            left.entries.keys == right.entries.keys &&
                left.entries.all { strictEquals(it.value, right.entries.getValue(it.key)) }
        else -> true
    }
}

private fun canonicalIdentity(value: JsonValue): String =
    if (value is JsonString) value.value else value.toJsonText()

private fun rolloutInput(flagKey: String, identity: String, salt: String): String =
    "feature-flag-bucket/v1\n" +
        "flagKey=${flagKey.length}:$flagKey\n" +
        "identity=${identity.toByteArray(StandardCharsets.UTF_8).size}:$identity\n" +
        "salt=${salt.length}:$salt"

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun contextKey(context: JsonObject): String = context.toJsonText()

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
