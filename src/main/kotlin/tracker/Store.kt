package tracker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.UUID

class CycleException(val path: List<String>) : Exception("前置依赖存在环: " + path.joinToString(" -> "))

class Store(private val file: Path) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()

    private var data: StoreData

    init {
        data = if (Files.exists(file)) {
            json.decodeFromString(StoreData.serializer(), Files.readString(file))
        } else {
            StoreData(
                project = ProjectSettings(
                    projectId = UUID.randomUUID().toString(),
                    secret = randomSecret(),
                )
            )
        }
        persist()
    }

    private fun randomSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun persist() {
        synchronized(lock) {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, json.encodeToString(StoreData.serializer(), data))
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun state(): StoreData = synchronized(lock) { data }

    fun project(): ProjectSettings = synchronized(lock) { data.project }

    fun updateSensitiveFields(fields: Set<String>): ProjectSettings = synchronized(lock) {
        data = data.copy(project = data.project.copy(sensitiveFields = fields))
        persist()
        data.project
    }

    fun createFlag(key: String, defaultValue: JsonElement, salt: String?): Flag = synchronized(lock) {
        require(!data.flags.containsKey(key)) { "开关 $key 已存在" }
        val flag = Flag(
            key = key,
            draft = FlagDoc(key = key, salt = salt?.ifEmpty { null } ?: key, defaultValue = defaultValue),
        )
        data = data.copy(flags = data.flags + (key to flag))
        persist()
        flag
    }

    fun getFlag(key: String): Flag? = synchronized(lock) { data.flags[key] }

    fun updateDraft(key: String, doc: FlagDoc): Flag = synchronized(lock) {
        val flag = data.flags[key] ?: throw NoSuchElementException("开关 $key 不存在")
        val drafts = data.flags.mapValues { if (it.key == key) doc else it.value.draft }
        val cycle = findCycle(drafts, key)
        if (cycle != null) throw CycleException(cycle)
        val updated = flag.copy(draft = doc)
        data = data.copy(flags = data.flags + (key to updated))
        persist()
        updated
    }

    fun publish(key: String): FlagVersion = synchronized(lock) {
        val flag = data.flags[key] ?: throw NoSuchElementException("开关 $key 不存在")
        val drafts = data.flags.mapValues { it.value.draft }
        val cycle = findCycle(drafts, key)
        if (cycle != null) throw CycleException(cycle)
        val version = FlagVersion(
            versionId = "v" + (flag.versions.size + 1),
            version = flag.versions.size + 1,
            createdAt = System.currentTimeMillis(),
            doc = flag.draft,
        )
        data = data.copy(flags = data.flags + (key to flag.copy(versions = flag.versions + version)))
        persist()
        version
    }

    fun findVersion(key: String, versionId: String): FlagVersion? = synchronized(lock) {
        data.flags[key]?.versions?.firstOrNull { it.versionId == versionId }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(data.flags.mapNotNull { (k, f) -> f.versions.lastOrNull()?.let { k to it } }.toMap())
    }

    fun snapshotOf(pairs: Map<String, String>): Snapshot = synchronized(lock) {
        Snapshot(pairs.mapNotNull { (k, vid) -> findVersion(k, vid)?.let { k to it } }.toMap())
    }

    fun evaluator(snapshot: Snapshot): Evaluator = Evaluator(snapshot, project())

    fun recordEvaluation(flagKey: String, versionId: String, context: JsonObject, trace: TraceNode): EvaluationRecord = synchronized(lock) {
        val record = EvaluationRecord(
            id = UUID.randomUUID().toString(),
            flagKey = flagKey,
            versionId = versionId,
            context = context,
            result = trace.result,
            trace = trace,
            createdAt = System.currentTimeMillis(),
        )
        data = data.copy(evaluations = data.evaluations + record)
        persist()
        record
    }

    fun getEvaluation(id: String): EvaluationRecord? = synchronized(lock) {
        data.evaluations.firstOrNull { it.id == id }
    }

    fun replay(id: String): TraceNode? {
        val record = getEvaluation(id) ?: return null
        val version = findVersion(record.flagKey, record.versionId) ?: return null
        return evaluator(Snapshot(mapOf(record.flagKey to version).plus(
            synchronized(lock) {
                data.flags.mapNotNull { (k, f) ->
                    if (k == record.flagKey) null else f.versions.lastOrNull()?.let { k to it }
                }.toMap()
            }
        ))).evaluate(record.flagKey, record.context)
    }

    fun saveContext(name: String, attributes: JsonObject): SavedContext = synchronized(lock) {
        val ctx = SavedContext(UUID.randomUUID().toString(), name, attributes)
        data = data.copy(contexts = data.contexts + ctx)
        persist()
        ctx
    }

    fun deleteContext(id: String): Boolean = synchronized(lock) {
        val before = data.contexts.size
        data = data.copy(contexts = data.contexts.filterNot { it.id == id })
        persist()
        data.contexts.size != before
    }

    fun export(): ExportBundle = synchronized(lock) {
        ExportBundle(
            project = data.project,
            flags = data.flags.values.toList(),
            contexts = data.contexts,
        )
    }

    fun import(bundle: ExportBundle) = synchronized(lock) {
        data = data.copy(
            project = bundle.project,
            flags = data.flags + bundle.flags.associateBy { it.key },
            contexts = (data.contexts + bundle.contexts).distinctBy { it.id },
        )
        persist()
    }

    companion object {
        fun findCycle(drafts: Map<String, FlagDoc>, start: String): List<String>? {
            val path = mutableListOf<String>()
            fun dfs(key: String): List<String>? {
                val idx = path.indexOf(key)
                if (idx >= 0) return path.drop(idx) + key
                val doc = drafts[key] ?: return null
                path.add(key)
                for (p in doc.prerequisites) {
                    val r = dfs(p.flagKey)
                    if (r != null) return r
                }
                path.removeAt(path.size - 1)
                return null
            }
            return dfs(start)
        }
    }
}
