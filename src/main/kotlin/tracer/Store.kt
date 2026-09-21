package tracer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

class Store(private val file: Path) {

    class State(
        var projectSecret: String = SensitiveDigester.newSecret(),
        var sensitiveFields: LinkedHashSet<String> = linkedSetOf(),
        val flags: LinkedHashMap<String, Flag> = LinkedHashMap(),
        val contexts: LinkedHashMap<String, SavedContext> = LinkedHashMap(),
        val evaluations: LinkedHashMap<String, SavedEvaluation> = LinkedHashMap()
    )

    @Volatile
    private var state: State = load()

    private fun load(): State {
        if (!Files.exists(file)) return State()
        val root = Json.parse(Files.readString(file)) as JVal.JObj
        val s = State()
        s.projectSecret = root["projectSecret"].strOrNull() ?: s.projectSecret
        root["sensitiveFields"].arrOrNull()?.mapNotNull { it.strOrNull() }?.forEach { s.sensitiveFields.add(it) }
        root["flags"].arrOrNull()?.forEach {
            val f = Flag.fromJson(it as JVal.JObj)
            s.flags[f.key] = f
        }
        root["contexts"].arrOrNull()?.forEach {
            val c = SavedContext.fromJson(it as JVal.JObj)
            s.contexts[c.id] = c
        }
        root["evaluations"].arrOrNull()?.forEach {
            val e = SavedEvaluation.fromJson(it as JVal.JObj)
            s.evaluations[e.id] = e
        }
        return s
    }

    @Synchronized
    private fun persist() {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, Json.render(stateToJson(state), pretty = true))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun stateToJson(s: State) = objOf(
        "projectSecret" to JVal.JStr(s.projectSecret),
        "sensitiveFields" to JVal.JArr(s.sensitiveFields.map { JVal.JStr(it) }),
        "flags" to JVal.JArr(s.flags.values.map { it.toJson() }),
        "contexts" to JVal.JArr(s.contexts.values.map { it.toJson() }),
        "evaluations" to JVal.JArr(s.evaluations.values.map { it.toJson() })
    )

    @Synchronized
    fun <T> read(block: (State) -> T): T = block(state)

    @Synchronized
    fun <T> write(block: (State) -> T): T {
        val r = block(state)
        persist()
        return r
    }

    fun engine(): Engine = read { Engine(it.sensitiveFields, SensitiveDigester(it.projectSecret)) }

    /** Snapshot of the latest published version of every flag — one batch sees one version set. */
    fun snapshot(): FlagSnapshot = read { s ->
        val defs = LinkedHashMap<String, FlagDef>()
        val versions = LinkedHashMap<String, Int>()
        for ((k, f) in s.flags) {
            val v = f.versions.lastOrNull() ?: continue
            defs[k] = v.def
            versions[k] = v.version
        }
        FlagSnapshot(defs, versions)
    }

    fun snapshotOf(flagKey: String, version: Int?): FlagSnapshot = read { s ->
        val defs = LinkedHashMap<String, FlagDef>()
        val versions = LinkedHashMap<String, Int>()
        val target = s.flags[flagKey] ?: throw EvalException("flag not found: $flagKey")
        val tv = (if (version == null) target.versions.lastOrNull()
            else target.versions.firstOrNull { it.version == version })
            ?: throw EvalException("flag '$flagKey' has no version ${version ?: "(latest)"}")
        // include latest published versions of all other flags for prerequisite resolution
        for ((k, f) in s.flags) {
            val v = if (k == flagKey) tv else f.versions.lastOrNull() ?: continue
            defs[k] = v.def
            versions[k] = v.version
        }
        FlagSnapshot(defs, versions)
    }

    fun publish(flagKey: String): FlagVersion = write { s ->
        val f = s.flags[flagKey] ?: throw EvalException("flag not found: $flagKey")
        // reject cycles in the draft graph before publishing
        val defs = s.flags.mapValues { (k, v) -> if (k == flagKey) f.draft else (v.versions.lastOrNull()?.def ?: v.draft) }
        FlagSnapshot(defs, defs.mapValues { 0 }).checkCycles()
        val v = FlagVersion(f.latestVersion + 1, f.draft, System.currentTimeMillis())
        s.flags[flagKey] = f.copy(versions = f.versions + v)
        v
    }

    fun exportJson(): JVal.JObj = read { s ->
        objOf(
            "format" to JVal.JStr("feature-flag-tracer/1"),
            "sensitiveFields" to JVal.JArr(s.sensitiveFields.map { JVal.JStr(it) }),
            "flags" to JVal.JArr(s.flags.values.map { it.toJson() }),
            "contexts" to JVal.JArr(s.contexts.values.map { it.toJson() })
        )
    }

    /** Import flags + contexts. Salts are imported verbatim so buckets and trace order are preserved. */
    fun importJson(j: JVal.JObj): Pair<Int, Int> = write { s ->
        var nf = 0
        var nc = 0
        j["sensitiveFields"].arrOrNull()?.mapNotNull { it.strOrNull() }?.forEach { s.sensitiveFields.add(it) }
        j["flags"].arrOrNull()?.forEach {
            val f = Flag.fromJson(it as JVal.JObj)
            s.flags[f.key] = f
            nf++
        }
        j["contexts"].arrOrNull()?.forEach {
            val c = SavedContext.fromJson(it as JVal.JObj)
            s.contexts[c.id] = c
            nc++
        }
        nf to nc
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().substring(0, 8)
    }
}
