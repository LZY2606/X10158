package fftrace

import kotlinx.serialization.json.Json
import java.io.File

class Store(private val file: File?) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private var data: StoreData = load()

    private fun load(): StoreData {
        if (file != null && file.exists()) {
            return runCatching { json.decodeFromString<StoreData>(file.readText()) }
                .getOrElse { StoreData() }
        }
        return StoreData()
    }

    @Synchronized
    private fun persist() {
        if (file != null) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(data))
            tmp.renameTo(file)
        }
    }

    @Synchronized
    fun state(): StoreData = data

    @Synchronized
    fun createFlag(key: String, description: String): Pair<Flag?, String?> {
        if (key.isBlank()) return null to "开关 key 不能为空"
        if (data.flags.containsKey(key)) return null to "开关 $key 已存在"
        val flag = Flag(key = key, description = description)
        data = data.copy(flags = data.flags + (key to flag))
        persist()
        return flag to null
    }

    @Synchronized
    fun deleteFlag(key: String) {
        data = data.copy(flags = data.flags - key)
        persist()
    }

    private fun configsWith(flags: Map<String, Flag>, key: String, config: FlagConfig): Map<String, FlagConfig> =
        flags.mapValues { it.value.draft } + (key to config)

    fun findCycle(configs: Map<String, FlagConfig>, fromKey: String): List<String>? {
        val stack = mutableListOf<String>()
        fun dfs(k: String): List<String>? {
            val idx = stack.indexOf(k)
            if (idx >= 0) return stack.drop(idx) + k
            val config = configs[k] ?: return null
            stack.add(k)
            for (p in config.prerequisites) {
                val r = dfs(p.flagKey)
                if (r != null) return r
            }
            stack.removeAt(stack.size - 1)
            return null
        }
        return dfs(fromKey)
    }

    @Synchronized
    fun saveDraft(key: String, config: FlagConfig): String? {
        val flag = data.flags[key] ?: return "开关 $key 不存在"
        val cycle = findCycle(configsWith(data.flags, key, config), key)
        if (cycle != null) return "检测到前置依赖环：${cycle.joinToString(" -> ")}"
        data = data.copy(flags = data.flags + (key to flag.copy(draft = config)))
        persist()
        return null
    }

    @Synchronized
    fun publish(key: String): Pair<FlagVersion?, String?> {
        val flag = data.flags[key] ?: return null to "开关 $key 不存在"
        val cycle = findCycle(configsWith(data.flags, key, flag.draft), key)
        if (cycle != null) return null to "检测到前置依赖环：${cycle.joinToString(" -> ")}"
        val version = FlagVersion(
            version = (flag.latestVersion?.version ?: 0) + 1,
            config = flag.draft,
            createdAt = System.currentTimeMillis()
        )
        data = data.copy(flags = data.flags + (key to flag.copy(versions = flag.versions + version)))
        persist()
        return version to null
    }

    @Synchronized
    fun saveContext(ctx: SavedContext): SavedContext {
        val withId = if (ctx.id.isBlank()) ctx.copy(id = "ctx-" + System.nanoTime()) else ctx
        data = data.copy(contexts = data.contexts + (withId.id to withId))
        persist()
        return withId
    }

    @Synchronized
    fun deleteContext(id: String) {
        data = data.copy(contexts = data.contexts - id)
        persist()
    }

    @Synchronized
    fun setSensitiveFields(fields: List<String>) {
        data = data.copy(sensitiveFields = fields.filter { it.isNotBlank() }.distinct())
        persist()
    }

    @Synchronized
    fun record(
        flagKey: String,
        version: Int,
        contextId: String?,
        contextName: String,
        context: Map<String, kotlinx.serialization.json.JsonElement>,
        trace: EvaluationTrace
    ): EvaluationRecord {
        val rec = EvaluationRecord(
            id = "ev-" + data.nextEvalId,
            flagKey = flagKey,
            version = version,
            contextId = contextId,
            contextName = contextName,
            context = context,
            result = trace.result,
            trace = trace,
            createdAt = System.currentTimeMillis()
        )
        data = data.copy(
            evaluations = data.evaluations + rec,
            nextEvalId = data.nextEvalId + 1
        )
        persist()
        return rec
    }

    @Synchronized
    fun getEvaluation(id: String): EvaluationRecord? = data.evaluations.find { it.id == id }

    @Synchronized
    fun snapshot(pins: Map<String, Int> = emptyMap()): Snapshot {
        val versions = data.flags.values.mapNotNull { flag ->
            val pinned = pins[flag.key]?.let { v -> flag.versions.find { it.version == v } }
            val chosen = pinned ?: flag.latestVersion
            if (chosen != null) {
                flag.key to FlagVersionedConfig(flag.key, chosen.version, chosen.config)
            } else if (flag.draft != FlagConfig()) {
                flag.key to FlagVersionedConfig(flag.key, 0, flag.draft)
            } else {
                null
            }
        }.toMap()
        return Snapshot(versions, data.sensitiveFields.toSet(), data.projectSecret)
    }

    @Synchronized
    fun export(): ExportData = ExportData(
        sensitiveFields = data.sensitiveFields,
        flags = data.flags.values.toList(),
        contexts = data.contexts.values.toList()
    )

    @Synchronized
    fun import(imp: ExportData): String? {
        for (flag in imp.flags) {
            val cycle = findCycle(
                (data.flags + flag.key.let { mapOf(flag.key to flag) }).mapValues { it.value.draft },
                flag.key
            )
            if (cycle != null) return "导入被拒绝，检测到前置依赖环：${cycle.joinToString(" -> ")}"
        }
        val newFlags = data.flags + imp.flags.associateBy { it.key }
        val allCycle = newFlags.keys.firstNotNullOfOrNull { key ->
            findCycle(newFlags.mapValues { it.value.draft }, key)?.let { key to it }
        }
        if (allCycle != null) {
            return "导入被拒绝，检测到前置依赖环：${allCycle.second.joinToString(" -> ")}"
        }
        data = data.copy(
            flags = newFlags,
            contexts = data.contexts + imp.contexts.associateBy { it.id },
            sensitiveFields = (data.sensitiveFields + imp.sensitiveFields).distinct()
        )
        persist()
        return null
    }
}
