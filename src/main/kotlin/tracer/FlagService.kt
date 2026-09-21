package tracer

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class FlagService(private val dataFile: File, val projectId: String) {
    private val lock = ReentrantReadWriteLock()
    private var store: Store = load()

    private fun load(): Store {
        if (!dataFile.exists()) return Store()
        return try {
            json.decodeFromString(Store.serializer(), dataFile.readText())
        } catch (e: Exception) {
            Store()
        }
    }

    private fun persist() {
        dataFile.parentFile?.mkdirs()
        val tmp = File(dataFile.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(Store.serializer(), store))
        tmp.renameTo(dataFile)
    }

    private fun evaluatorWith(snapshot: Map<String, FlagVersion>, sensitive: Map<String, Set<String>>) =
        Evaluator(projectId, { key -> snapshot[key] }, { key -> sensitive[key] ?: emptySet() })

    private fun snapshotOf(): Pair<Map<String, FlagVersion>, Map<String, Set<String>>> {
        val versions = mutableMapOf<String, FlagVersion>()
        val sensitive = mutableMapOf<String, Set<String>>()
        for ((key, flag) in store.flags) {
            val v = flag.currentVersion
            if (v != null) {
                versions[key] = v
                sensitive[key] = flag.sensitiveFields.toSet()
            }
        }
        return versions to sensitive
    }

    fun listFlags(): List<Flag> = lock.read { store.flags.values.sortedBy { it.key } }

    fun getFlag(key: String): Flag = lock.read {
        store.flags[key] ?: throw ApiException(404, "开关 $key 不存在")
    }

    fun createFlag(key: String, sensitiveFields: List<String>): Flag = lock.write {
        if (!key.matches(Regex("[A-Za-z0-9_.-]+"))) throw ApiException(400, "开关 key 只能包含字母、数字、_ . -")
        if (store.flags.containsKey(key)) throw ApiException(409, "开关 $key 已存在")
        val flag = Flag(key = key, sensitiveFields = sensitiveFields.distinct())
        store.flags[key] = flag
        persist()
        flag
    }

    fun deleteFlag(key: String) = lock.write {
        if (store.flags.remove(key) == null) throw ApiException(404, "开关 $key 不存在")
        persist()
    }

    fun setSensitiveFields(key: String, fields: List<String>): Flag = lock.write {
        val flag = getFlag(key)
        val updated = flag.copy(sensitiveFields = fields.distinct())
        store.flags[key] = updated
        persist()
        updated
    }

    fun publishVersion(key: String, body: JsonObject): FlagVersion = lock.write {
        val flag = getFlag(key)
        if (!body.containsKey("defaultValue")) throw ApiException(400, "缺少 defaultValue")
        val defaultValue = body.getValue("defaultValue")
        val rules = parseRules(body)
        val prerequisites = body.getStringListOrEmpty("prerequisites").distinct()
        for (dep in prerequisites) {
            if (dep == key) throw ApiException(409, "前置开关不能依赖自身: $key -> $key")
            if (store.flags[dep]?.currentVersion == null) {
                throw ApiException(400, "前置开关 $dep 不存在或没有已发布版本")
            }
        }
        val candidatePrereqs = store.flags.mapValues { (k, f) ->
            if (k == key) prerequisites else f.currentVersion?.prerequisites ?: emptyList()
        }
        findCycle(candidatePrereqs)?.let { cycle ->
            throw ApiException(409, "检测到前置开关依赖环: ${cycle.joinToString(" -> ")}")
        }
        val version = FlagVersion(
            version = (flag.versions.maxOfOrNull { it.version } ?: 0) + 1,
            defaultValue = defaultValue,
            rules = rules,
            prerequisites = prerequisites,
            createdAt = System.currentTimeMillis(),
        )
        store.flags[key] = flag.copy(versions = flag.versions + version)
        persist()
        version
    }

    private fun parseRules(body: JsonObject): List<Rule> {
        val rulesEl = body["rules"] ?: return emptyList()
        val arr = rulesEl as? kotlinx.serialization.json.JsonArray
            ?: throw ApiException(400, "rules 必须是数组")
        return arr.mapIndexed { ri, ruleEl ->
            val r = ruleEl as? JsonObject ?: throw ApiException(400, "规则 #${ri + 1} 必须是对象")
            val clausesEl = r["clauses"] as? kotlinx.serialization.json.JsonArray
                ?: throw ApiException(400, "规则 #${ri + 1} 缺少 clauses 数组")
            val clauses = clausesEl.mapIndexed { ci, clauseEl ->
                val c = clauseEl as? JsonObject ?: throw ApiException(400, "条款 #${ci + 1} 必须是对象")
                val value = c["value"] ?: throw ApiException(400, "条款 #${ci + 1} 缺少 value")
                val conditions = ((c["conditions"] as? kotlinx.serialization.json.JsonArray) ?: kotlinx.serialization.json.JsonArray(emptyList()))
                    .mapIndexed { ki, condEl ->
                        val co = condEl as? JsonObject ?: throw ApiException(400, "条件 #${ki + 1} 必须是对象")
                        val field = co.getStringOrNull("field") ?: throw ApiException(400, "条件 #${ki + 1} 缺少 field")
                        val opName = co.getStringOrNull("op") ?: throw ApiException(400, "条件 #${ki + 1} 缺少 op")
                        val op = try { Op.valueOf(opName) } catch (e: Exception) {
                            throw ApiException(400, "未知运算符 $opName，可选: ${Op.entries.joinToString()}")
                        }
                        val condValue = co["value"] ?: throw ApiException(400, "条件 #${ki + 1} 缺少 value")
                        Condition(field, op, condValue)
                    }
                val rolloutEl = c["rollout"] as? JsonObject
                val rollout = if (rolloutEl == null) Rollout() else Rollout(
                    enabled = rolloutEl.getBooleanOrNull("enabled") ?: false,
                    percentage = (rolloutEl.getIntOrNull("percentage") ?: 100).also {
                        if (it < 0 || it > 100) throw ApiException(400, "百分比必须在 0..100 之间")
                    },
                    identityField = rolloutEl.getStringOrNull("identityField") ?: "id",
                    salt = rolloutEl.getStringOrNull("salt") ?: "",
                )
                Clause(
                    id = c.getStringOrNull("id") ?: UUID.randomUUID().toString(),
                    conditions = conditions,
                    rollout = rollout,
                    value = value,
                )
            }
            Rule(id = r.getStringOrNull("id") ?: UUID.randomUUID().toString(), clauses = clauses)
        }
    }

    fun evaluate(key: String, context: JsonObject, record: Boolean): EvalRecord {
        val evalRecord = lock.read {
            val flag = getFlag(key)
            val version = flag.currentVersion ?: throw ApiException(409, "开关 $key 还没有已发布版本")
            val (snapshot, sensitive) = snapshotOf()
            val outcome = evaluatorWith(snapshot, sensitive).evaluate(key, context)
            EvalRecord(
                id = UUID.randomUUID().toString(),
                flagKey = key,
                version = version.version,
                context = context,
                result = outcome.value,
                trace = outcome.trace,
                createdAt = System.currentTimeMillis(),
            )
        }
        if (record) {
            lock.write {
                store.records[evalRecord.id] = evalRecord
                persist()
            }
        }
        return evalRecord
    }

    fun evaluateBatch(flagKeys: List<String>, context: JsonObject): JsonObject = lock.read {
        val (snapshot, sensitive) = snapshotOf()
        val evaluator = evaluatorWith(snapshot, sensitive)
        buildJsonObject {
            put("results", buildJsonObject {
                for (key in flagKeys) {
                    val flag = store.flags[key]
                    val version = flag?.currentVersion
                    if (flag == null || version == null) {
                        put(key, buildJsonObject {
                            put("error", "开关不存在或没有已发布版本")
                        })
                    } else {
                        val outcome = evaluator.evaluate(key, context)
                        put(key, buildJsonObject {
                            put("version", version.version)
                            put("value", outcome.value)
                            put("trace", outcome.trace)
                        })
                    }
                }
            })
        }
    }

    fun compareVersions(key: String, versionA: Int, versionB: Int, contexts: List<JsonObject>): JsonObject = lock.read {
        val flag = getFlag(key)
        val va = flag.versions.firstOrNull { it.version == versionA }
            ?: throw ApiException(404, "版本 $versionA 不存在")
        val vb = flag.versions.firstOrNull { it.version == versionB }
            ?: throw ApiException(404, "版本 $versionB 不存在")
        val sensitive = flag.sensitiveFields.toSet()
        val sensitiveOf = { _: String -> sensitive }
        val evalA = Evaluator(projectId, { k -> if (k == key) va else store.flags[k]?.currentVersion }, sensitiveOf)
        val evalB = Evaluator(projectId, { k -> if (k == key) vb else store.flags[k]?.currentVersion }, sensitiveOf)
        val rows = contexts.map { ctx ->
            val ra = evalA.evaluate(key, ctx)
            val rb = evalB.evaluate(key, ctx)
            buildJsonObject {
                put("context", ctx)
                put("a", buildJsonObject { put("value", ra.value); put("trace", ra.trace) })
                put("b", buildJsonObject { put("value", rb.value); put("trace", rb.trace) })
                put("changed", ra.value != rb.value)
            }
        }
        buildJsonObject {
            put("flagKey", key)
            put("versionA", versionA)
            put("versionB", versionB)
            put("rows", kotlinx.serialization.json.JsonArray(rows))
            put("changedCount", rows.count { (it["changed"] as JsonPrimitive).content == "true" })
        }
    }

    fun saveContext(name: String, context: JsonObject): SavedContext = lock.write {
        val saved = SavedContext(UUID.randomUUID().toString(), name, context, System.currentTimeMillis())
        store.contexts[saved.id] = saved
        persist()
        saved
    }

    fun listContexts(): List<SavedContext> = lock.read { store.contexts.values.sortedBy { it.createdAt } }

    fun getContexts(ids: List<String>): List<SavedContext> = lock.read {
        ids.map { id -> store.contexts[id] ?: throw ApiException(404, "上下文 $id 不存在") }
    }

    fun deleteContext(id: String) = lock.write {
        if (store.contexts.remove(id) == null) throw ApiException(404, "上下文 $id 不存在")
        persist()
    }

    fun listRecords(flagKey: String?): List<EvalRecord> = lock.read {
        store.records.values
            .filter { flagKey == null || it.flagKey == flagKey }
            .sortedByDescending { it.createdAt }
    }

    fun getRecord(id: String): EvalRecord = lock.read {
        store.records[id] ?: throw ApiException(404, "求值记录 $id 不存在")
    }

    fun exportAll(flagKeys: List<String>?, contextIds: List<String>?): ExportBundle = lock.read {
        val flags = store.flags.values
            .filter { flagKeys == null || it.key in flagKeys }
            .sortedBy { it.key }
        val contexts = store.contexts.values
            .filter { contextIds == null || it.id in contextIds }
            .sortedBy { it.createdAt }
        ExportBundle(exportedAt = System.currentTimeMillis(), flags = flags, contexts = contexts)
    }

    fun importBundle(bundle: ExportBundle): JsonObject = lock.write {
        if (bundle.format != "flag-tracer-export") throw ApiException(400, "无法识别的导出格式")
        var importedFlags = 0
        var importedContexts = 0
        for (flag in bundle.flags) {
            val existing = store.flags[flag.key]
            if (existing == null || json.encodeToString(Flag.serializer(), existing) != json.encodeToString(Flag.serializer(), flag)) {
                store.flags[flag.key] = flag
                importedFlags++
            }
        }
        for (ctx in bundle.contexts) {
            if (store.contexts[ctx.id] != ctx) {
                store.contexts[ctx.id] = ctx
                importedContexts++
            }
        }
        persist()
        buildJsonObject {
            put("importedFlags", importedFlags)
            put("importedContexts", importedContexts)
        }
    }

    companion object {
        fun findCycle(graph: Map<String, List<String>>): List<String>? {
            val visiting = mutableSetOf<String>()
            val done = mutableSetOf<String>()
            val stack = mutableListOf<String>()
            fun dfs(node: String): List<String>? {
                if (node in visiting) {
                    return stack.dropWhile { it != node } + node
                }
                if (node in done) return null
                visiting += node
                stack += node
                for (dep in graph[node] ?: emptyList()) {
                    val cycle = dfs(dep)
                    if (cycle != null) return cycle
                }
                stack.removeAt(stack.size - 1)
                visiting -= node
                done += node
                return null
            }
            for (node in graph.keys) {
                val cycle = dfs(node)
                if (cycle != null) return cycle
            }
            return null
        }
    }
}
