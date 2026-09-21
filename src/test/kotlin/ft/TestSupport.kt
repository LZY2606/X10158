package ft

import java.nio.file.Files

fun tempStore(): Store {
    val dir = Files.createTempDirectory("ft-test")
    return Store(dir)
}

fun Store.testProject(
    name: String = "测试项目",
    sensitive: List<String> = emptyList()
): Project = createProject(name, sensitive)

fun json(text: String): JsonObject = parseJson(text) as JsonObject

fun Store.publishSimple(
    projectId: String,
    key: String,
    rules: List<Rule>,
    prerequisites: List<Prerequisite> = emptyList(),
    default: ServeValue = ServeValue.OFF,
    stableIdField: String = "user.id",
    note: String = "v"
): Flag = saveDraft(
    projectId = projectId,
    key = key,
    name = key,
    description = "",
    rules = rules,
    prerequisites = prerequisites,
    defaultValue = default,
    stableIdField = stableIdField,
    publish = true,
    note = note
)

fun Store.evalOnce(
    projectId: String,
    key: String,
    context: JsonObject,
    version: Int? = null
): EvalRecord = evaluateBatch(
    projectId,
    listOf(BatchRequest(key, "ctx", context)),
    version,
    save = false
).single()
