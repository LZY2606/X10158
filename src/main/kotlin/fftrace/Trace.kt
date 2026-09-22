package fftrace

import kotlinx.serialization.Serializable

@Serializable
data class FieldState(
    val kind: String, // missing | null | value | sensitive
    val display: String
)

@Serializable
data class ConditionTrace(
    val field: String,
    val op: String,
    val expected: List<String>,
    val actual: FieldState,
    val passed: Boolean,
    val note: String = ""
)

@Serializable
data class RolloutTrace(
    val flagKey: String,
    val identityField: String,
    val identity: FieldState,
    val salt: String,
    val hashInput: String,
    val algorithm: String,
    val hash: String,
    val bucket: Int,
    val percentage: Double,
    val inBucket: Boolean
)

@Serializable
data class RuleTrace(
    val index: Int,
    val ruleId: String,
    val name: String,
    val conditions: List<ConditionTrace>,
    val matched: Boolean,
    val rollout: RolloutTrace? = null,
    val served: String? = null
)

@Serializable
data class PrereqTrace(
    val flagKey: String,
    val requiredValue: String,
    val actualValue: String,
    val passed: Boolean,
    val trace: EvaluationTrace? = null
)

@Serializable
data class EvaluationTrace(
    val flagKey: String,
    val version: Int,
    val result: String,
    val reason: String, // rule_match | default | prerequisite_failed | flag_not_found | dependency_cycle
    val prerequisites: List<PrereqTrace> = emptyList(),
    val rules: List<RuleTrace> = emptyList(),
    val steps: List<String> = emptyList()
)
