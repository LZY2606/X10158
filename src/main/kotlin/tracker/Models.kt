package tracker

enum class CompareOperator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    IN,
    PRESENT
}

data class Condition(
    val field: String,
    val operator: CompareOperator,
    val value: JsonValue = JsonNull,
    val sensitive: Boolean = false
)

data class Rule(
    val id: String,
    val name: String,
    val conditions: List<Condition>,
    val result: JsonValue
)

data class PercentageSlice(
    val id: String,
    val name: String,
    val result: JsonValue,
    val weightBasisPoints: Int
)

data class PercentageRollout(
    val identityField: String,
    val salt: String,
    val slices: List<PercentageSlice>
)

enum class PrerequisiteMatcher {
    ON,
    OFF,
    EQUALS
}

data class Prerequisite(
    val flagKey: String,
    val matcher: PrerequisiteMatcher,
    val expected: JsonValue = JsonNull
)

data class FlagVersion(
    val id: String,
    val key: String,
    val name: String,
    val version: Int,
    val createdAt: String,
    val note: String,
    val defaultValue: JsonValue,
    val prerequisites: List<Prerequisite>,
    val rules: List<Rule>,
    val rollout: PercentageRollout?,
    val sensitiveFields: List<String>
)

data class Flag(
    val key: String,
    val name: String,
    val currentVersionId: String,
    val versions: List<FlagVersion>
)

data class SavedContext(
    val id: String,
    val name: String,
    val createdAt: String,
    val context: JsonObject
)

data class TraceStep(
    val type: String,
    val title: String,
    val status: String,
    val detail: JsonObject,
    val children: List<TraceStep>
)

data class EvaluationResult(
    val flagKey: String,
    val versionId: String,
    val result: JsonValue,
    val reason: String,
    val fieldsRead: List<String>,
    val trace: TraceStep
)

data class EvaluationRecord(
    val id: String,
    val createdAt: String,
    val flagKey: String,
    val versionId: String,
    val contextName: String,
    val context: JsonObject,
    val result: EvaluationResult
)

data class Project(
    val id: String,
    val name: String,
    val secretHex: String,
    val createdAt: String,
    val flags: List<Flag>,
    val contexts: List<SavedContext>,
    val records: List<EvaluationRecord>
)

data class Snapshot(
    val projectId: String,
    val secretHex: String,
    val versionsByFlagKey: Map<String, FlagVersion>,
    val snapshotId: String = stableId()
)

class ValidationException(message: String) : IllegalArgumentException(message)
class CycleException(val path: List<String>) : IllegalStateException(cycleMessage(path)) {
    companion object {
        fun cycleMessage(path: List<String>) = "检测到前置开关依赖环：${path.joinToString(" → ")}"
    }
}

fun stableId(): String =
    java.security.SecureRandom().let { random ->
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

fun nowIso(): String = java.time.Instant.now().toString()

fun Project.currentSnapshot(): Snapshot = Snapshot(
    projectId = id,
    secretHex = secretHex,
    versionsByFlagKey = flags.associate { flag ->
        flag.key to (flag.versions.firstOrNull { it.id == flag.currentVersionId } ?: flag.versions.last())
    }
)

fun Project.withFlagVersion(version: FlagVersion): Project {
    val existing = flags.firstOrNull { it.key == version.key }
    val updatedFlag = if (existing == null) {
        Flag(version.key, version.name, version.id, listOf(version))
    } else {
        existing.copy(
            name = version.name,
            currentVersionId = version.id,
            versions = existing.versions + version
        )
    }
    return copy(flags = flags.filter { it.key != version.key } + updatedFlag)
}

fun validateVersion(project: Project, version: FlagVersion) {
    require(version.key.isNotBlank()) { "开关 key 不能为空" }
    require(version.name.isNotBlank()) { "开关名称不能为空" }
    val duplicateRule = version.rules.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
    require(duplicateRule.isEmpty()) { "规则 ID 重复：${duplicateRule.first()}" }
    version.rules.forEach { rule ->
        require(rule.conditions.isNotEmpty()) { "规则 ${rule.name} 至少需要一个条件" }
        rule.conditions.forEach { condition ->
            require(condition.field.isNotBlank()) { "规则 ${rule.name} 存在空字段路径" }
            validateCondition(condition)
        }
    }
    version.rollout?.let { rollout ->
        require(rollout.identityField.isNotBlank()) { "百分比分流必须指定稳定身份字段" }
        require(rollout.salt.isNotBlank()) { "百分比分流必须指定 salt" }
        val duplicateSlice = rollout.slices.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateSlice.isEmpty()) { "分流桶 ID 重复：${duplicateSlice.first()}" }
        val total = rollout.slices.sumOf { it.weightBasisPoints }
        require(total in 0..10000) { "百分比分流总权重必须在 0 到 10000 之间，当前为 $total" }
        rollout.slices.forEach { slice ->
            require(slice.weightBasisPoints in 0..10000) { "分流桶 ${slice.name} 权重越界" }
        }
    }
    val candidateVersions = project.currentSnapshot().versionsByFlagKey + (version.key to version)
    validateDependencyGraph(candidateVersions)
}

private fun validateCondition(condition: Condition) {
    when (condition.operator) {
        CompareOperator.IN -> require(condition.value is JsonArray) { "IN 条件必须提供数组" }
        CompareOperator.GREATER_THAN,
        CompareOperator.GREATER_THAN_OR_EQUAL,
        CompareOperator.LESS_THAN,
        CompareOperator.LESS_THAN_OR_EQUAL -> require(condition.value is JsonNumber) {
            "字段 ${condition.field} 的比较值必须是数字"
        }
        else -> Unit
    }
}

fun validateDependencyGraph(versionsByFlagKey: Map<String, FlagVersion>) {
    versionsByFlagKey.values.forEach { version ->
        version.prerequisites.forEach { prerequisite ->
            require(versionsByFlagKey.containsKey(prerequisite.flagKey)) {
                "开关 ${version.key} 引用了不存在的前置开关 ${prerequisite.flagKey}"
            }
        }
    }
    val visiting = linkedSetOf<String>()
    val visited = hashSetOf<String>()

    fun visit(key: String) {
        if (key in visited) return
        if (key in visiting) {
            val start = visiting.indexOf(key)
            throw CycleException(visiting.toList().drop(start) + key)
        }
        visiting += key
        versionsByFlagKey.getValue(key).prerequisites.forEach { visit(it.flagKey) }
        visiting.remove(key)
        visited += key
    }

    versionsByFlagKey.keys.sorted().forEach(::visit)
}
