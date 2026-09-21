package tracker

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 本地 JSON 文件持久化。所有写操作同步落盘。 */
class Store(private val dir: Path) {
    private val lock = Any()
    private var projects = linkedMapOf<String, Project>()
    private var flags = linkedMapOf<String, Flag>()
    private var contexts = linkedMapOf<String, Ctx>()
    private var records = linkedMapOf<String, EvalRecord>()

    init {
        Files.createDirectories(dir)
        load()
        if (projects.isEmpty()) {
            val p = Project("default", "默认项目", UUID.randomUUID().toString())
            projects[p.id] = p
            persist()
        }
    }

    private fun file(name: String) = dir.resolve("$name.json")

    private fun load() {
        fun read(name: String): JVal? {
            val f = file(name)
            return if (Files.exists(f)) Json.parse(Files.readString(f)) else null
        }
        (read("projects") as? JArr)?.items?.forEach {
            val p = Project.fromJson(it as JObj); projects[p.id] = p
        }
        (read("flags") as? JArr)?.items?.forEach {
            val f = Flag.fromJson(it as JObj); flags[f.key] = f
        }
        (read("contexts") as? JArr)?.items?.forEach {
            val c = Ctx.fromJson(it as JObj); contexts[c.id] = c
        }
        (read("records") as? JArr)?.items?.forEach {
            val r = EvalRecord.fromJson(it as JObj); records[r.id] = r
        }
    }

    private fun persist() {
        fun write(name: String, v: JVal) {
            val tmp = file("$name.tmp")
            Files.writeString(tmp, Json.render(v))
            Files.move(tmp, file(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        write("projects", JArr(projects.values.map { it.toJson() }))
        write("flags", JArr(flags.values.map { it.toJson() }))
        write("contexts", JArr(contexts.values.map { it.toJson() }))
        write("records", JArr(records.values.map { it.toJson() }))
    }

    // ---- 项目 ----
    fun listProjects(): List<Project> = synchronized(lock) { projects.values.toList() }
    fun project(id: String): Project = synchronized(lock) {
        projects[id] ?: throw EvalException("项目不存在: $id")
    }
    fun createProject(name: String): Project = synchronized(lock) {
        val p = Project(UUID.randomUUID().toString().substring(0, 8), name, UUID.randomUUID().toString())
        projects[p.id] = p
        persist()
        p
    }

    // ---- 开关 ----
    fun listFlags(): List<Flag> = synchronized(lock) { flags.values.toList() }
    fun flag(key: String): Flag = synchronized(lock) {
        flags[key] ?: throw EvalException("开关不存在: $key")
    }
    fun createFlag(key: String): Flag = synchronized(lock) {
        require(key.matches(Regex("[a-zA-Z0-9_.-]+"))) { "非法开关 key" }
        if (flags.containsKey(key)) throw EvalException("开关已存在: $key")
        val f = Flag(
            key,
            UUID.randomUUID().toString().substring(0, 8),
            mutableListOf(FlagVersion(1, JBool(false), emptyList(), emptyList()))
        )
        flags[key] = f
        persist()
        f
    }

    /** 发布新版本。前置依赖会先做环检测，发现环则拒绝并给出路径。 */
    fun publishVersion(key: String, defaultValue: JVal, rules: List<Rule>, prerequisites: List<Prereq>): FlagVersion =
        synchronized(lock) {
            val f = flag(key)
            validateRollouts(rules)
            checkCycles(key, prerequisites)
            val v = FlagVersion(f.versions.size + 1, defaultValue, rules, prerequisites)
            f.versions.add(v)
            persist()
            v
        }

    private fun validateRollouts(rules: List<Rule>) {
        for (r in rules) {
            val s = r.serve
            if (s is Serve.Rollout) {
                val sum = s.variations.sumOf { it.weight }
                require(sum == Bucket.BUCKETS) { "规则「${r.name}」的分流权重之和必须为 100，当前为 $sum" }
                require(s.variations.isNotEmpty()) { "规则「${r.name}」的分流至少需要一个变体" }
            }
        }
    }

    /** 环检测：从 key 出发沿前置边 DFS，若回到 key 则抛出带路径的异常。 */
    private fun checkCycles(key: String, newPrereqs: List<Prereq>) {
        fun edgesOf(k: String): List<String> =
            if (k == key) newPrereqs.map { it.flagKey }
            else flags[k]?.current?.prerequisites?.map { it.flagKey } ?: emptyList()

        fun dfs(node: String, path: List<String>, visited: Set<String>) {
            for (next in edgesOf(node)) {
                if (next == key) throw CycleException(path + key)
                if (next !in visited) dfs(next, path + next, visited + next)
            }
        }
        dfs(key, listOf(key), setOf(key))
    }

    // ---- 上下文 ----
    fun listContexts(): List<Ctx> = synchronized(lock) { contexts.values.toList() }
    fun context(id: String): Ctx = synchronized(lock) {
        contexts[id] ?: throw EvalException("上下文不存在: $id")
    }
    fun saveContext(id: String?, name: String, attrs: JObj, sensitive: Set<String>): Ctx = synchronized(lock) {
        val cid = id ?: UUID.randomUUID().toString().substring(0, 8)
        val c = Ctx(cid, name, attrs, sensitive)
        contexts[cid] = c
        persist()
        c
    }
    fun deleteContext(id: String) = synchronized(lock) { contexts.remove(id); persist() }

    // ---- 求值 ----
    /** 捕获当前版本快照：批量求值期间发布新版本不影响本次结果。 */
    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(flags.mapValues { it.value.current })
    }

    fun evaluator(projectId: String, snapshot: Snapshot): Evaluator =
        Evaluator(snapshot, project(projectId))

    fun evaluate(projectId: String, flagKey: String, version: Int?, attrs: JObj, sensitive: Set<String>): JObj {
        val snap = snapshot()
        val effective = if (version != null) {
            val fv = flag(flagKey).version(version) ?: throw EvalException("开关 $flagKey 没有版本 $version")
            snap.with(flagKey, fv)
        } else snap
        return evaluator(projectId, effective).evaluate(flagKey, attrs, sensitive)
    }

    // ---- 记录 ----
    fun saveRecord(flagKey: String, trace: JObj, ctx: Ctx): EvalRecord = synchronized(lock) {
        val r = EvalRecord(
            UUID.randomUUID().toString().substring(0, 8),
            flagKey,
            (trace["version"] as JNum).dec.toInt(),
            ctx.id,
            ctx.attrs,
            ctx.sensitive,
            trace["result"]!!,
            trace
        )
        records[r.id] = r
        persist()
        r
    }
    fun listRecords(flagKey: String?): List<EvalRecord> = synchronized(lock) {
        records.values.filter { flagKey == null || it.flagKey == flagKey }.sortedBy { it.createdAt }
    }
    fun record(id: String): EvalRecord = synchronized(lock) {
        records[id] ?: throw EvalException("记录不存在: $id")
    }

    /** 重放：用记录绑定的版本与当时的上下文重新求值。 */
    fun replay(projectId: String, recordId: String): JObj {
        val r = record(recordId)
        val fv = flag(r.flagKey).version(r.version)
            ?: throw EvalException("版本 ${r.version} 已不存在")
        val snap = snapshot().with(r.flagKey, fv)
        return evaluator(projectId, snap).evaluate(r.flagKey, r.contextAttrs, r.sensitive)
    }

    // ---- 导出 / 导入 ----
    fun export(): JObj = synchronized(lock) {
        jObjOf(
            "format" to JStr("flag-tracker-export/v1"),
            "projects" to JArr(projects.values.map { it.toJson() }),
            "flags" to JArr(flags.values.map { it.toJson() }),
            "contexts" to JArr(contexts.values.map { it.toJson() })
        )
    }

    fun import(data: JObj) = synchronized(lock) {
        val o = data as JObj
        require((o["format"] as? JStr)?.v == "flag-tracker-export/v1") { "无法识别的导出格式" }
        (o["projects"] as JArr).items.forEach {
            val p = Project.fromJson(it as JObj); projects[p.id] = p
        }
        (o["flags"] as JArr).items.forEach {
            val f = Flag.fromJson(it as JObj); flags[f.key] = f
        }
        (o["contexts"] as JArr).items.forEach {
            val c = Ctx.fromJson(it as JObj); contexts[c.id] = c
        }
        persist()
    }
}
