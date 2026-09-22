package tracker

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class TrackerStore(private val storageFile: Path) {
    private var projects: List<Project> = emptyList()

    init {
        load()
        if (projects.isEmpty()) {
            projects = listOf(seedProject())
            save()
        }
    }

    @Synchronized
    fun listProjects(): List<Project> = projects

    @Synchronized
    fun createProject(name: String): Project {
        val project = Project(
            id = stableId(),
            name = name.ifBlank { "未命名项目" },
            secretHex = stableId(),
            createdAt = nowIso(),
            flags = emptyList(),
            contexts = emptyList(),
            records = emptyList()
        )
        projects = projects + project
        save()
        return project
    }

    @Synchronized
    fun publishFlag(projectId: String, draft: JsonObject): FlagVersion {
        val project = projectById(projectId)
        val key = draft.string("key")
        val existing = project.flags.firstOrNull { it.key == key }
        val version = FlagVersion(
            id = stableId(),
            key = key,
            name = draft.string("name"),
            version = (existing?.versions?.maxOfOrNull { it.version } ?: 0) + 1,
            createdAt = nowIso(),
            note = draft.stringOrNull("note") ?: "",
            defaultValue = draft.valueOrNull("defaultValue") ?: JsonNull,
            prerequisites = (draft.valueOrNull("prerequisites") as? JsonArray)?.values
                ?.map { prerequisiteFromJson(it as JsonObject) } ?: emptyList(),
            rules = (draft.valueOrNull("rules") as? JsonArray)?.values
                ?.map { ruleFromJson(it as JsonObject) } ?: emptyList(),
            rollout = (draft.valueOrNull("rollout") as? JsonObject)?.let(::rolloutFromJson),
            sensitiveFields = (draft.valueOrNull("sensitiveFields") as? JsonArray)?.values
                ?.mapNotNull { (it as? JsonString)?.value } ?: emptyList()
        )
        validateVersion(project, version)
        replaceProject(project.withFlagVersion(version))
        return version
    }

    @Synchronized
    fun saveContext(projectId: String, name: String, context: JsonObject): SavedContext {
        val project = projectById(projectId)
        val saved = SavedContext(stableId(), name.ifBlank { "未命名上下文" }, nowIso(), context)
        replaceProject(project.copy(contexts = project.contexts + saved))
        return saved
    }

    @Synchronized
    fun deleteContext(projectId: String, contextId: String) {
        val project = projectById(projectId)
        replaceProject(project.copy(contexts = project.contexts.filterNot { it.id == contextId }))
    }

    @Synchronized
    fun evaluate(projectId: String, flagKey: String, context: JsonObject, contextName: String, saveRecord: Boolean): EvaluationRecord {
        val project = projectById(projectId)
        val result = FlagEvaluator(project.currentSnapshot()).evaluate(flagKey, context)
        val record = EvaluationRecord(
            id = stableId(),
            createdAt = nowIso(),
            flagKey = flagKey,
            versionId = result.versionId,
            contextName = contextName,
            context = context,
            result = result
        )
        if (saveRecord) replaceProject(project.copy(records = project.records + record))
        return record
    }

    @Synchronized
    fun evaluateBatch(projectId: String, context: JsonObject, flagKeys: List<String>?): JsonObject {
        val project = projectById(projectId)
        val snapshot = project.currentSnapshot()
        val keys = flagKeys?.takeIf { it.isNotEmpty() } ?: snapshot.versionsByFlagKey.keys.sorted()
        val results = FlagEvaluator(snapshot).evaluateBatch(keys, context)
        return JsonObject(mapOf(
            "snapshotId" to JsonString(snapshot.snapshotId),
            "results" to JsonArray(results.map(::evaluationToJson))
        ))
    }

    @Synchronized
    fun compareVersions(projectId: String, flagKey: String, versionAId: String, versionBId: String, contextIds: List<String>): JsonObject {
        val project = projectById(projectId)
        val flag = project.flags.firstOrNull { it.key == flagKey }
            ?: throw ValidationException("开关不存在：$flagKey")
        val versionA = flag.versions.firstOrNull { it.id == versionAId }
            ?: throw ValidationException("版本 A 不存在：$versionAId")
        val versionB = flag.versions.firstOrNull { it.id == versionBId }
            ?: throw ValidationException("版本 B 不存在：$versionBId")
        val contexts = project.contexts.filter { it.id in contextIds.toSet() }
        val snapshotA = project.currentSnapshot().copy(
            versionsByFlagKey = project.currentSnapshot().versionsByFlagKey + (flagKey to versionA)
        )
        val snapshotB = project.currentSnapshot().copy(
            versionsByFlagKey = project.currentSnapshot().versionsByFlagKey + (flagKey to versionB)
        )
        val rows = contexts.map { saved ->
            JsonObject(mapOf(
                "contextId" to JsonString(saved.id),
                "contextName" to JsonString(saved.name),
                "a" to evaluationToJson(FlagEvaluator(snapshotA).evaluate(flagKey, saved.context)),
                "b" to evaluationToJson(FlagEvaluator(snapshotB).evaluate(flagKey, saved.context))
            ))
        }
        return JsonObject(mapOf(
            "flagKey" to JsonString(flagKey),
            "versionA" to versionToJson(versionA),
            "versionB" to versionToJson(versionB),
            "rows" to JsonArray(rows)
        ))
    }

    @Synchronized
    fun exportProject(projectId: String): JsonObject {
        val project = projectById(projectId)
        return JsonObject(mapOf(
            "format" to JsonString("feature-flag-tracker-export/v1"),
            "exportedAt" to JsonString(nowIso()),
            "project" to projectToJson(project)
        ))
    }

    @Synchronized
    fun importProject(payload: JsonObject): Project {
        val projectJson = (payload.valueOrNull("project") as? JsonObject) ?: payload
        val imported = projectFromJson(projectJson)
        val project = imported.copy(
            id = stableId(),
            name = imported.name + "（导入）",
            secretHex = stableId(),
            createdAt = nowIso()
        )
        projects = projects + project
        save()
        return project
    }

    @Synchronized
    fun projectById(projectId: String): Project =
        projects.firstOrNull { it.id == projectId } ?: throw ValidationException("项目不存在：$projectId")

    @Synchronized
    fun snapshotFor(projectId: String): Snapshot = projectById(projectId).currentSnapshot()

    private fun replaceProject(updated: Project) {
        projects = projects.map { if (it.id == updated.id) updated else it }
        save()
    }

    private fun load() {
        if (!Files.exists(storageFile)) return
        val root = parseJson(Files.readString(storageFile)) as JsonObject
        projects = root.array("projects").map { projectFromJson(it as JsonObject) }
    }

    private fun save() {
        Files.createDirectories(storageFile.parent)
        val root = JsonObject(mapOf(
            "format" to JsonString("feature-flag-tracker-store/v1"),
            "projects" to JsonArray(projects.map(::projectToJson))
        ))
        val temp = storageFile.resolveSibling(storageFile.fileName.toString() + ".tmp")
        Files.writeString(temp, root.toJsonText(pretty = true))
        try {
            Files.move(temp, storageFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, storageFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun seedProject(): Project {
        val project = Project(
            id = stableId(),
            name = "演示项目",
            secretHex = stableId(),
            createdAt = nowIso(),
            flags = emptyList(),
            contexts = emptyList(),
            records = emptyList()
        )
        val checkout = FlagVersion(
            id = stableId(),
            key = "checkout-redesign",
            name = "新版结账流程",
            version = 1,
            createdAt = nowIso(),
            note = "演示：规则优先，随后百分比分流",
            defaultValue = JsonString("off"),
            prerequisites = emptyList(),
            rules = listOf(
                Rule(
                    id = "staff",
                    name = "内部员工开启",
                    conditions = listOf(Condition("user.email", CompareOperator.EQUALS, JsonString("admin@example.com"), sensitive = true)),
                    result = JsonString("on")
                ),
                Rule(
                    id = "beta-plan",
                    name = "Beta 套餐变体",
                    conditions = listOf(Condition("account.plan", CompareOperator.IN, JsonArray(listOf(JsonString("beta"), JsonString("enterprise"))))),
                    result = JsonString("variant-beta")
                )
            ),
            rollout = PercentageRollout(
                identityField = "user.id",
                salt = "checkout-v1",
                slices = listOf(
                    PercentageSlice("control", "对照组", JsonString("off"), 5000),
                    PercentageSlice("treatment", "实验组", JsonString("variant-a"), 5000)
                )
            ),
            sensitiveFields = listOf("user.email")
        )
        val context = SavedContext(
            id = stableId(),
            name = "示例用户",
            createdAt = nowIso(),
            context = JsonObject(mapOf(
                "user" to JsonObject(mapOf(
                    "id" to JsonString("user-123"),
                    "email" to JsonString("admin@example.com")
                )),
                "account" to JsonObject(mapOf("plan" to JsonString("pro")))
            ))
        )
        return project.withFlagVersion(checkout).copy(contexts = listOf(context))
    }
}
