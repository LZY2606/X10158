package ft

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * File-backed store. One JSON file under a local data directory, written
 * atomically (temp file + move). All batch evaluation takes an immutable
 * snapshot, so a publish landing mid-batch can never mix versions.
 */
class Store(private val dataDir: Path) {
    private val rng = SecureRandom()
    private val lock = ReentrantReadWriteLock()

    private var projects = linkedMapOf<String, Project>()
    private var flags = linkedMapOf<ProjectFlagKey, Flag>()
    private var contexts = linkedMapOf<String, SavedContext>()
    private var records = linkedMapOf<String, EvalRecord>()

    private data class ProjectFlagKey(val projectId: String, val flagKey: String)

    private val file: Path = dataDir.resolve("feature-flags.json")

    data class Snapshot(
        val projects: Map<String, Project>,
        val flags: Map<Pair<String, String>, FlagVersion>,
        val contexts: Map<String, SavedContext>
    )

    init {
        Files.createDirectories(dataDir)
        load()
    }

    private fun load() {
        if (!Files.exists(file)) return
        val text = Files.readString(file)
        if (text.isBlank()) return
        val bundle = Codec.bundleFromJson(parseJson(text))
        projects = LinkedHashMap(bundle.projects.associateBy { it.id })
        flags = LinkedHashMap(bundle.flags.associateBy {
            ProjectFlagKey(it.projectId, it.key)
        })
        contexts = LinkedHashMap(bundle.contexts.associateBy { it.id })
        records = LinkedHashMap(bundle.records.associateBy { it.id })
    }

    private fun persist() {
        val bundle = Bundle(
            exportedAtMs = System.currentTimeMillis(),
            projects = projects.values.toList(),
            flags = flags.values.toList(),
            contexts = contexts.values.toList(),
            records = records.values.toList()
        )
        val text = Codec.bundleToJson(bundle).toJson()
        val tmp = dataDir.resolve("feature-flags.json.tmp")
        Files.writeString(tmp, text)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun newId(prefix: String): String =
        prefix + "-" + java.lang.Long.toHexString(rng.nextLong() and 0x7fffffffffffffffL)

    // ----- projects -----

    fun listProjects(): List<Project> = lock.read { projects.values.toList() }

    fun getProject(id: String): Project = lock.read {
        projects[id] ?: throw NotFound("project '$id'")
    }

    fun createProject(name: String, sensitiveFields: List<String>): Project = lock.write {
        val project = Project(
            id = newId("prj"),
            name = name.ifBlank { "未命名项目" },
            digestSalt = randomSalt(),
            sensitiveFields = sensitiveFields
        )
        projects[project.id] = project
        persist()
        project
    }

    fun updateProject(id: String, name: String, sensitiveFields: List<String>): Project = lock.write {
        val existing = projects[id] ?: throw NotFound("project '$id'")
        val updated = existing.copy(name = name, sensitiveFields = sensitiveFields)
        projects[id] = updated
        persist()
        updated
    }

    // ----- flags / versions -----

    fun listFlags(projectId: String): List<Flag> = lock.read {
        flags.values.filter { it.projectId == projectId }
    }

    fun getFlag(projectId: String, key: String): Flag = lock.read {
        flags[ProjectFlagKey(projectId, key)] ?: throw NotFound("flag '$key'")
    }

    fun saveDraft(
        projectId: String,
        key: String,
        name: String,
        description: String,
        rules: List<Rule>,
        prerequisites: List<Prerequisite>,
        defaultValue: ServeValue,
        stableIdField: String,
        publish: Boolean,
        note: String
    ): Flag = lock.write {
        require(projectId in projects) { "unknown project" }
        validateRules(key, rules)
        assertProjectAcyclic(projectId, key, prerequisites)

        val existing = flags[ProjectFlagKey(projectId, key)]
        val flag = (existing ?: Flag(
            projectId = projectId,
            key = key,
            name = name,
            description = description,
            versions = emptyList()
        )).copy(
            name = name,
            description = description,
            draftRules = rules,
            draftPrerequisites = prerequisites,
            draftDefaultValue = defaultValue,
            draftStableIdField = stableIdField
        )
        val published = if (publish) publishVersion(flag, note) else flag
        flags[ProjectFlagKey(projectId, key)] = published
        persist()
        published
    }

    fun publishExistingDraft(projectId: String, key: String, note: String): Flag = lock.write {
        val flag = flags[ProjectFlagKey(projectId, key)]
            ?: throw NotFound("flag '$key'")
        validateRules(key, flag.draftRules)
        assertProjectAcyclic(projectId, key, flag.draftPrerequisites)
        val updated = publishVersion(flag, note)
        flags[ProjectFlagKey(projectId, key)] = updated
        persist()
        updated
    }

    private fun publishVersion(flag: Flag, note: String): Flag {
        val nextVersion = (flag.versions.maxOfOrNull { it.version } ?: 0) + 1
        val snapshot = FlagVersion(
            version = nextVersion,
            rules = flag.draftRules.map { deepCopyRule(it) },
            prerequisites = flag.draftPrerequisites.map { it.copy() },
            defaultValue = flag.draftDefaultValue,
            stableIdField = flag.draftStableIdField,
            note = note,
            createdAtMs = System.currentTimeMillis()
        )
        return flag.copy(versions = flag.versions + snapshot)
    }

    private fun deepCopyRule(rule: Rule): Rule =
        Codec.ruleFromJson(Codec.ruleToJson(rule))

    private fun validateRules(key: String, rules: List<Rule>) {
        require(rules.isNotEmpty()) { "flag '$key' needs at least one rule or remove the draft" }
        val ids = mutableSetOf<String>()
        for (rule in rules) {
            require(rule.id.isNotBlank()) { "rule id must not be blank" }
            require(ids.add(rule.id)) { "duplicate rule id '${rule.id}'" }
            val total = rule.rollout?.sumOf { it.weightBp } ?: 0
            require(total <= 10000) {
                "rollout weights for rule '${rule.id}' exceed 100%"
            }
            if (!rule.hasRollout) {
                requireNotNull(rule.serve) {
                    "rule '${rule.id}' must define serve or rollout"
                }
            }
        }
    }

    private fun assertProjectAcyclic(
        projectId: String,
        changedKey: String,
        changedPrereqs: List<Prerequisite>
    ) {
        val edges = flags.values
            .filter { it.projectId == projectId }
            .associate { f -> f.key to f.latestDraftOrVersion().prerequisites.map { it.flagKey } }
            .toMutableMap()
        edges[changedKey] = changedPrereqs.map { it.flagKey }
        DependencyGraph.assertAcyclic(edges)
    }

    private fun Flag.latestDraftOrVersion(): FlagVersion =
        versions.lastOrNull() ?: FlagVersion(
            version = 0,
            rules = draftRules,
            prerequisites = draftPrerequisites,
            defaultValue = draftDefaultValue,
            stableIdField = draftStableIdField,
            createdAtMs = 0L
        )

    // ----- evaluation -----

    /** Snapshot of one project's flags, each at its latest published version. */
    fun snapshot(projectId: String): Snapshot = lock.read {
        val latest = HashMap<Pair<String, String>, FlagVersion>()
        flags.values.filter { it.projectId == projectId }.forEach { f ->
            val v = f.versions.maxByOrNull { it.version }
                ?: throw BadRequest("flag '${f.key}' has no published version")
            latest[projectId to f.key] = v
        }
        Snapshot(HashMap(projects), latest, HashMap(contexts))
    }

    fun snapshotVersion(projectId: String, version: Int?): Snapshot = lock.read {
        if (version == null) return@read snapshot(projectId)
        val picked = HashMap<Pair<String, String>, FlagVersion>()
        flags.values.filter { it.projectId == projectId }.forEach { f ->
            val target = f.versions.firstOrNull { it.version == version }
                ?: throw BadRequest(
                    "flag '${f.key}' does not have version $version; " +
                        "available: ${f.versions.joinToString(",") { it.version.toString() }}"
                )
            picked[projectId to f.key] = target
        }
        Snapshot(HashMap(projects), picked, HashMap(contexts))
    }

    /**
     * Evaluates many (flag, context) pairs against one immutable snapshot.
     * Publishing a new version after this call begins cannot affect results.
     */
    fun evaluateBatch(
        projectId: String,
        requests: List<BatchRequest>,
        pinnedVersion: Int? = null,
        save: Boolean = false
    ): List<EvalRecord> {
        val project = getProject(projectId)
        val snap = if (pinnedVersion == null) snapshot(projectId)
        else snapshotVersion(projectId, pinnedVersion)
        val resolver = FlagResolver { pid, key -> snap.flags[pid to key] }
        val evaluator = Evaluator(project, resolver)
        return requests.map { request ->
            val definition = snap.flags[projectId to request.flagKey]
                ?: throw NotFound("flag '${request.flagKey}' has no published version")
            val outcome = evaluator.evaluate(request.flagKey, definition, request.context)
            EvalRecord(
                id = newId("rec"),
                projectId = projectId,
                flagKey = request.flagKey,
                version = definition.version,
                contextName = request.contextName,
                context = request.context,
                result = outcome.result,
                trace = outcome.trace,
                createdAtMs = System.currentTimeMillis()
            )
        }.also { produced ->
            if (save) lock.write {
                produced.forEach { records[it.id] = it }
                persist()
            }
        }
    }

    // ----- saved contexts -----

    fun listContexts(): List<SavedContext> = lock.read { contexts.values.toList() }

    fun saveContext(name: String, context: JsonObject): SavedContext = lock.write {
        val saved = SavedContext(newId("ctx"), name, context, System.currentTimeMillis())
        contexts[saved.id] = saved
        persist()
        saved
    }

    fun deleteContext(id: String) = lock.write {
        contexts.remove(id) ?: throw NotFound("context '$id'")
        persist()
    }

    // ----- records / replay -----

    fun listRecords(projectId: String? = null): List<EvalRecord> = lock.read {
        records.values.filter { projectId == null || it.projectId == projectId }
    }

    fun getRecord(id: String): EvalRecord = lock.read {
        records[id] ?: throw NotFound("record '$id'")
    }

    /**
     * Replays a historical record against the exact version pinned in it.
     * The stored trace is returned alongside; later rule edits cannot change
     * either the outcome or the recorded step order.
     */
    fun replayRecord(id: String): Pair<EvalRecord, EvalResult> = lock.read {
        val record = records[id] ?: throw NotFound("record '$id'")
        val project = projects[record.projectId]
            ?: throw NotFound("project for record vanished")
        val flag = flags[ProjectFlagKey(record.projectId, record.flagKey)]
            ?: throw NotFound("flag '${record.flagKey}' vanished")
        val definition = flag.version(record.version)
        val pinnedVersions = mutableMapOf<String, Int>()
        collectPinnedVersions(record.trace, pinnedVersions)
        val resolver = FlagResolver { pid, key ->
            val target = flags[ProjectFlagKey(pid, key)] ?: return@FlagResolver null
            val pinned = pinnedVersions[key]
            if (pinned != null) target.versions.firstOrNull { it.version == pinned }
            else target.versions.maxByOrNull { it.version }
        }
        val evaluator = Evaluator(project, resolver)
        val fresh = evaluator.evaluate(record.flagKey, definition, record.context)
        record to fresh
    }

    private fun collectPinnedVersions(trace: JsonObject, out: MutableMap<String, Int>) {
        for (step in trace["steps"].asArray?.items.orEmpty()) {
            val stepObj = step.asObject ?: continue
            if (stepObj["type"].asString == "prerequisite") {
                val key = stepObj["flagKey"].asString
                val version = stepObj["version"].asNumber?.long?.toInt()
                if (key != null && version != null) out[key] = version
                stepObj["nestedTrace"].asObject?.let { collectPinnedVersions(it, out) }
            }
        }
    }

    // ----- export / import -----

    fun exportBundle(): Bundle = lock.read {
        Bundle(
            exportedAtMs = System.currentTimeMillis(),
            projects = projects.values.toList(),
            flags = flags.values.toList(),
            contexts = contexts.values.toList(),
            records = records.values.toList()
        )
    }

    /** Imports a bundle; ids and per-project salts are retained for stable buckets. */
    fun importBundle(bundle: Bundle, replace: Boolean) = lock.write {
        if (replace) {
            projects.clear()
            flags.clear()
            contexts.clear()
            records.clear()
        }
        bundle.projects.forEach { projects[it.id] = it }
        bundle.flags.forEach { flags[ProjectFlagKey(it.projectId, it.key)] = it }
        bundle.contexts.forEach { contexts[it.id] = it }
        bundle.records.forEach { records[it.id] = it }
        persist()
    }

    private fun randomSalt(): String {
        val bytes = ByteArray(24)
        rng.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

data class BatchRequest(
    val flagKey: String,
    val contextName: String,
    val context: JsonObject
)

open class ApiException(val status: Int, message: String) : RuntimeException(message)
class NotFound(message: String) : ApiException(404, message)
class BadRequest(message: String) : ApiException(400, message)
