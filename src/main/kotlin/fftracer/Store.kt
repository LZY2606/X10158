package fftracer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

@Serializable
data class StoreData(
    val projects: MutableList<Project> = mutableListOf(),
    val flags: MutableList<Flag> = mutableListOf(),
    val contexts: MutableList<EvalContext> = mutableListOf(),
    val records: MutableList<EvalRecord> = mutableListOf(),
)

class CycleException(val path: List<String>) : Exception("prerequisite cycle: " + path.joinToString(" -> "))

class Store(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private var data: StoreData =
        if (file.exists()) json.decodeFromString(file.readText()) else StoreData()

    @Synchronized
    private fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(StoreData.serializer(), data))
        tmp.renameTo(file)
    }

    @Synchronized fun listProjects(): List<Project> = data.projects.toList()
    @Synchronized fun getProject(id: String): Project? = data.projects.firstOrNull { it.id == id }

    @Synchronized
    fun createProject(name: String): Project {
        val p = Project(id = "p" + UUID.randomUUID().toString().take(8), name = name,
            secret = UUID.randomUUID().toString())
        data.projects.add(p); persist(); return p
    }

    @Synchronized fun listFlags(projectId: String): List<Flag> =
        data.flags.filter { it.projectId == projectId }

    @Synchronized fun getFlag(projectId: String, key: String): Flag? =
        data.flags.firstOrNull { it.projectId == projectId && it.key == key }

    @Synchronized
    fun createFlag(projectId: String, key: String, defaultValue: JsonElement = JsonPrimitive(false)): Flag {
        require(getFlag(projectId, key) == null) { "flag '$key' already exists" }
        val flag = Flag(
            key = key, projectId = projectId,
            salt = UUID.randomUUID().toString().take(12),
            currentVersion = 1,
            versions = listOf(FlagVersion(1, defaultValue, createdAt = System.currentTimeMillis())),
        )
        data.flags.add(flag); persist(); return flag
    }

    /**
     * Publishes a new immutable version of [key] by applying [mutate] to a copy of the head.
     * Prerequisite cycles are rejected with the full cycle path.
     */
    @Synchronized
    fun publishVersion(projectId: String, key: String, mutate: (FlagVersion) -> FlagVersion): Flag {
        val flag = getFlag(projectId, key) ?: throw NoSuchElementException("flag '$key' not found")
        val next = mutate(flag.head).copy(version = flag.currentVersion + 1,
            createdAt = System.currentTimeMillis())
        checkNoCycle(projectId, key, next.prerequisites)
        val updated = flag.copy(
            currentVersion = next.version,
            versions = flag.versions + next,
        )
        data.flags.removeAll { it.projectId == projectId && it.key == key }
        data.flags.add(updated); persist(); return updated
    }

    private fun checkNoCycle(projectId: String, key: String, prereqs: List<Prerequisite>) {
        val graph: Map<String, List<String>> = data.flags
            .filter { it.projectId == projectId }
            .associate { f ->
                f.key to (if (f.key == key) prereqs else f.head.prerequisites).map { it.flagKey }
            }
        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        fun dfs(node: String, path: List<String>) {
            if (node in path) throw CycleException(path + node)
            if (node in done) return
            if (!visiting.add(node)) return
            for (dep in graph[node].orEmpty()) dfs(dep, path + node)
            visiting.remove(node); done.add(node)
        }
        dfs(key, emptyList())
    }

    /** Fixed point-in-time view: batch evaluations never mix versions. */
    @Synchronized
    fun snapshot(projectId: String): Map<String, Flag> =
        listFlags(projectId).associateBy { it.key }

    @Synchronized fun saveContext(ctx: EvalContext): EvalContext {
        data.contexts.removeAll { it.projectId == ctx.projectId && it.id == ctx.id }
        data.contexts.add(ctx); persist(); return ctx
    }
    @Synchronized fun getContext(projectId: String, id: String): EvalContext? =
        data.contexts.firstOrNull { it.projectId == projectId && it.id == id }
    @Synchronized fun listContexts(projectId: String): List<EvalContext> =
        data.contexts.filter { it.projectId == projectId }

    @Synchronized fun saveRecord(rec: EvalRecord): EvalRecord { data.records.add(rec); persist(); return rec }
    @Synchronized fun getRecord(projectId: String, id: String): EvalRecord? =
        data.records.firstOrNull { it.projectId == projectId && it.id == id }
    @Synchronized fun listRecords(projectId: String, flagKey: String? = null): List<EvalRecord> =
        data.records.filter { it.projectId == projectId && (flagKey == null || it.flagKey == flagKey) }

    @Synchronized fun export(projectId: String): StoreData = StoreData(
        projects = data.projects.filter { it.id == projectId }.toMutableList(),
        flags = data.flags.filter { it.projectId == projectId }.toMutableList(),
        contexts = data.contexts.filter { it.projectId == projectId }.toMutableList(),
        records = mutableListOf(),
    )

    @Synchronized
    fun import(bundle: StoreData) {
        for (p in bundle.projects) {
            if (data.projects.none { it.id == p.id }) data.projects.add(p)
        }
        for (f in bundle.flags) {
            data.flags.removeAll { it.projectId == f.projectId && it.key == f.key }
            data.flags.add(f)
        }
        for (c in bundle.contexts) {
            data.contexts.removeAll { it.projectId == c.projectId && it.id == c.id }
            data.contexts.add(c)
        }
        persist()
    }
}
