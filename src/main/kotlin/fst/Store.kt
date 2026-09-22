package fst

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class Store(private val dir: Path) {
    private val rng = SecureRandom()
    lateinit var project: Project
        private set
    private val file: Path = dir.resolve("project.json")

    init { load() }

    @Synchronized
    private fun load() {
        Files.createDirectories(dir)
        project = if (file.exists()) {
            Project.from(Json.parseObject(file.readText(Charsets.UTF_8)))
        } else {
            val p = Project("default", "默认项目", newSalt())
            saveLocked()
            p
        }
    }

    private fun newSalt(): String {
        val b = ByteArray(16); rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun saveLocked() {
        val tmp = dir.resolve(".project.json.tmp")
        tmp.writeText(Json.write(project.toJson(), indent = true), Charsets.UTF_8)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized fun <T> mutate(block: (Project) -> T): T {
        val r = block(project)
        saveLocked()
        return r
    }

    fun snapshotView(): Map<String, Any?> = synchronized(this) { project.toJson() }

    @Synchronized
    fun createFlag(key: String, name: String, stableIdField: String, sensitiveFields: List<String>): Flag {
        require(key.matches(Regex("[A-Za-z0-9_.\\-]+"))) { "开关 key 只能包含字母数字 _ . -" }
        if (project.flags.any { it.key == key }) throw IllegalArgumentException("开关 key 已存在：$key")
        val flag = Flag(key, name.ifBlank { key }, stableIdField.ifBlank { "id" }, sensitiveFields.distinct(), emptyList())
        project.flags.add(flag)
        saveLocked()
        return flag
    }

    @Synchronized
    fun updateFlagMeta(key: String, name: String?, stableIdField: String?, sensitiveFields: List<String>?) {
        val flag = requireFlag(key)
        val idx = project.flags.indexOf(flag)
        project.flags[idx] = flag.copy(
            name = name ?: flag.name,
            stableIdField = stableIdField?.ifBlank { flag.stableIdField } ?: flag.stableIdField,
            sensitiveFields = sensitiveFields?.distinct() ?: flag.sensitiveFields,
        )
        saveLocked()
    }

    @Synchronized
    fun publishVersion(
        key: String,
        onVariants: List<String>,
        prerequisites: List<Prerequisite>,
        rules: List<Rule>,
        defaultServe: Serve,
        salt: String?,
        note: String,
        versionOverride: Int? = null,
        validatePrereqGraph: Boolean = true,
    ): FlagVersion {
        val flag = requireFlag(key)
        val nextNum = versionOverride ?: (flag.currentVersion + 1)
        require(nextNum > 0) { "版本号必须为正整数" }
        require(flag.versions.none { it.version == nextNum }) { "版本已存在：$nextNum" }
        val saltVal = salt?.ifBlank { null } ?: flag.current()?.salt ?: newSalt()
        val v = FlagVersion(nextNum, saltVal, onVariants.distinct(), prerequisites, rules, defaultServe, note)
        Engine.validateVersion(flag, v)

        // Build a tentative snapshot to validate prerequisites + cycle graph against other flags.
        if (validatePrereqGraph) {
            val tentative = project.flags.map { f ->
                if (f.key == key) f.copy(versions = f.versions + v) else f
            }
            val tmpProject = Project(project.id, project.name, project.digestSalt, tentative.toMutableList())
            val snap = Engine.snapshot(tmpProject)
            // prerequisites may reference versions published earlier or simultaneously
            for (pre in v.prerequisites) {
                if (snap.versions[pre.flagKey] == null)
                    throw RuleValidationException("前置开关 ${pre.flagKey} 尚不存在或没有已发布版本")
            }
            Engine.checkCycles(snap)
        }

        val idx = project.flags.indexOf(flag)
        project.flags[idx] = flag.copy(versions = flag.versions + v)
        saveLocked()
        return v
    }

    /** Reorder rules of the CURRENT version. Produces a NEW version so history stays pinned. */
    @Synchronized
    fun reorderRules(key: String, orderedRuleIds: List<String>): FlagVersion {
        val flag = requireFlag(key)
        val cur = flag.current() ?: throw IllegalStateException("开关还没有版本")
        val byId = cur.rules.associateBy { it.id }
        require(orderedRuleIds.size == cur.rules.size && orderedRuleIds.all { it in byId }) { "规则 id 列表与当前版本不一致" }
        val reordered = orderedRuleIds.map { byId[it]!! }
        return publishVersion(
            key, cur.onVariants, cur.prerequisites, reordered, cur.defaultServe,
            salt = cur.salt, note = "拖动重排规则顺序（沿用同一 salt，分桶条款不变时桶不变）",
        )
    }

    /** Update weights / clauses of a rollout rule in the current version; publishes a new version. */
    @Synchronized
    fun editCurrentVersion(key: String, edit: (FlagVersion) -> FlagVersion): FlagVersion {
        val flag = requireFlag(key)
        val cur = flag.current() ?: throw IllegalStateException("开关还没有版本")
        val nextNum = flag.currentVersion + 1
        val nv = edit(cur).copy(version = nextNum, createdAt = System.currentTimeMillis())
        Engine.validateVersion(flag, nv)
        val tentative = project.flags.map { f -> if (f.key == key) f.copy(versions = f.versions + nv) else f }
        val tmpProject = Project(project.id, project.name, project.digestSalt, tentative.toMutableList())
        Engine.checkCycles(Engine.snapshot(tmpProject))
        val idx = project.flags.indexOf(flag)
        project.flags[idx] = flag.copy(versions = flag.versions + nv)
        saveLocked()
        return nv
    }

    @Synchronized
    fun saveContext(name: String, value: Map<String, Any?>, id: String? = null): SavedContext {
        val cid = id ?: IdGen.next("ctx")
        project.contexts.removeAll { it.id == cid }
        val sc = SavedContext(cid, name, value)
        project.contexts.add(sc)
        saveLocked()
        return sc
    }

    @Synchronized
    fun deleteContext(id: String) {
        project.contexts.removeAll { it.id == id }
        saveLocked()
    }

    @Synchronized
    fun addRecord(r: EvalRecord) { project.records.add(r); saveLocked() }
    @Synchronized
    fun deleteRecord(id: String) { project.records.removeAll { it.id == id }; saveLocked() }
    @Synchronized
    fun clearRecords() { project.records.clear(); saveLocked() }

    fun requireFlag(key: String): Flag =
        project.flags.firstOrNull { it.key == key } ?: throw NoSuchElementException("开关不存在：$key")

    @Synchronized
    fun export(): Map<String, Any?> = Json.obj(
        "format" to "feature-switch-tracker/v1",
        "exportedAt" to System.currentTimeMillis(),
        "project" to project.toJson(),
    )

    @Synchronized
    fun importBundle(bundle: Map<String, Any?>, mode: String, regenerateDigestSalt: Boolean) {
        val pm = (bundle["project"] as? Map<*, *>)?.let { it.entries.associate { e -> e.key.toString() to e.value } }
            ?: throw IllegalArgumentException("导入文件缺少 project")
        val incoming = Project.from(pm)
        if (regenerateDigestSalt) incoming.digestSalt = newSalt()
        val merged = when (mode) {
            "replace" -> incoming
            "merge" -> {
                val flags = LinkedHashMap<String, Flag>()
                project.flags.forEach { flags[it.key] = it }
                incoming.flags.forEach { flags[it.key] = it }
                val ctxs = LinkedHashMap<String, SavedContext>()
                project.contexts.forEach { ctxs[it.id] = it }
                incoming.contexts.forEach { ctxs[it.id] = it }
                Project(project.id, project.name, project.digestSalt,
                    flags.values.toMutableList(), ctxs.values.toMutableList(),
                    (project.records + incoming.records).toMutableList())
            }
            else -> throw IllegalArgumentException("未知导入模式 $mode")
        }
        project.flags.clear(); project.flags.addAll(merged.flags)
        project.contexts.clear(); project.contexts.addAll(merged.contexts)
        project.records.clear(); project.records.addAll(merged.records)
        if (mode == "replace") project.digestSalt = merged.digestSalt
        saveLocked()
    }
}


