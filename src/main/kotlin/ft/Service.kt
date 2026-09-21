package ft

import java.util.UUID

/** Export/import bundle. bucketSalt travels with the data so bucketing is
 *  identical after import; summarySecret is deliberately regenerated. */
data class Bundle(
    val name: String,
    val bucketSalt: String,
    val sensitiveFields: List<String>,
    val flags: List<Flag>,
    val savedContexts: List<SavedContext>
) {
    fun toJson(): Any? = linkedMapOf(
        "format" to "feature-flag-tracker-bundle/1",
        "name" to name,
        "bucketSalt" to bucketSalt,
        "sensitiveFields" to sensitiveFields,
        "flags" to flags.map { it.toJson() },
        "savedContexts" to savedContexts.map { it.toJson() }
    )

    companion object {
        fun fromJson(v: Any?): Bundle {
            val m = Json.obj(v)
            val format = Json.strOr(m["format"], "feature-flag-tracker-bundle/1")
            require(format == "feature-flag-tracker-bundle/1") { "Unsupported bundle format '$format'" }
            return Bundle(
                name = Json.strOr(m["name"], "imported"),
                bucketSalt = Json.strOr(m["bucketSalt"], "bucket"),
                sensitiveFields = (m["sensitiveFields"]?.let { Json.arr(it) } ?: emptyList())
                    .map { Json.str(it) },
                flags = Json.arr(m["flags"]).map { Flag.fromJson(it!!) },
                savedContexts = (m["savedContexts"]?.let { Json.arr(it) } ?: emptyList())
                    .map { SavedContext.fromJson(it!!) }
            )
        }
    }
}

class Service(private val store: FileStore) {
    private val engine = Engine()
    var project: Project
        private set

    init {
        project = store.load() ?: Project(
            name = "default",
            bucketSalt = "proj-" + Hashing.randomSecret().substring(0, 10),
            summarySecret = Hashing.randomSecret(),
            sensitiveFields = emptyList(),
            flags = emptyMap(),
            savedContexts = emptyList(),
            records = emptyList()
        ).also { store.save(it) }
    }

    @Synchronized fun snapshot(versions: Map<String, FlagVersion>? = null): Snapshot {
        val map = versions ?: project.flags.values.mapNotNull { it.current }.associateBy { it.key }
        return Snapshot(map, project.bucketSalt, Redactor(project.sensitiveFields.toSet(), project.summarySecret))
    }

    @Synchronized fun setProjectSettings(name: String?, sensitiveFields: List<String>?) {
        project = project.copy(
            name = name ?: project.name,
            sensitiveFields = sensitiveFields ?: project.sensitiveFields
        )
        persist()
    }

    @Synchronized fun listFlags(): List<Flag> = project.flags.values.sortedBy { it.key }

    @Synchronized fun getFlag(key: String): Flag =
        project.flags[key] ?: throw EvaluationException("Unknown flag '$key'")

    @Synchronized fun createFlag(key: String, draft: Draft): Flag {
        require(key.matches(Regex("[A-Za-z0-9_.-]+"))) { "Flag key may only contain letters, digits, '_', '.' and '-'" }
        require(!project.flags.containsKey(key)) { "Flag '$key' already exists" }
        require(draft.salt.isNotBlank()) { "Salt required" }
        val flag = Flag(key, draft, emptyList(), now())
        project = project.copy(flags = project.flags + (key to flag))
        persist()
        return flag
    }

    @Synchronized fun updateDraft(key: String, draft: Draft): Flag {
        val existing = getFlag(key)
        require(draft.salt.isNotBlank()) { "Salt required" }
        project = project.copy(flags = project.flags + (key to existing.copy(draft = draft)))
        persist()
        return getFlag(key)
    }

    /** Validate the draft against the full graph (self-heal candidates =
     *  latest published versions with this flag replaced). */
    @Synchronized fun validatePublish(key: String): ValidationResult {
        val flag = getFlag(key)
        val nextVersion = (flag.current?.version ?: 0) + 1
        val candidate = flag.draft.let {
            FlagVersion(key, it.name, it.description, nextVersion, it.salt, it.stableIdField,
                it.prerequisites, it.rules, it.defaultValue, now())
        }
        val single = Validator.validateSingle(candidate)
        val candidates: Map<String, FlagVersion> =
            project.flags.mapNotNull { (k, f) ->
                val chosen = if (k == key) candidate else f.current
                chosen?.let { k to it }
            }.toMap()

        val unknown = candidate.prerequisites.mapNotNull { p ->
            if (p.flagKey == key || candidates.containsKey(p.flagKey)) null
            else "Unknown prerequisite flag '${p.flagKey}'"
        }
        val cycle = Validator.findCycle(candidates)
        val errors = single + unknown + (cycle?.let { listOf("Prerequisite cycle: ${it.joinToString(" -> ")}") } ?: emptyList())
        return ValidationResult(errors.isEmpty(), errors, cycle)
    }

    @Synchronized fun publish(key: String): Pair<Flag, FlagVersion> {
        val validation = validatePublish(key)
        require(validation.valid) { validation.errors.joinToString("; ") }
        val flag = getFlag(key)
        val nextVersion = (flag.current?.version ?: 0) + 1
        val d = flag.draft
        val fv = FlagVersion(key, d.name, d.description, nextVersion, d.salt, d.stableIdField,
            d.prerequisites, d.rules, d.defaultValue, now())
        val updated = flag.copy(versions = flag.versions + fv)
        project = project.copy(flags = project.flags + (key to updated))
        persist()
        return updated to fv
    }

    @Synchronized fun saveContext(name: String, context: Map<String, Any?>): SavedContext {
        val sc = SavedContext("ctx-" + shortId(), name.ifBlank { "context" }, context)
        project = project.copy(savedContexts = project.savedContexts + sc)
        persist()
        return sc
    }

    @Synchronized fun deleteContext(id: String) {
        project = project.copy(savedContexts = project.savedContexts.filterNot { it.id == id })
        persist()
    }

    @Synchronized fun listContexts(): List<SavedContext> = project.savedContexts

    @Synchronized fun listRecords(): List<EvaluationRecord> =
        project.records.sortedByDescending { it.evaluatedAt }

    /** Evaluate one flag and persist a version-bound record. */
    @Synchronized fun evaluate(flagKey: String, context: Map<String, Any?>, targetVersion: Int? = null): Pair<Evaluation, EvaluationRecord> {
        val snap = snapshot()
        val eval = engine.evaluate(snap, flagKey, context, targetVersion)
        val record = EvaluationRecord(
            id = "rec-" + shortId(),
            flagKey = eval.flagKey,
            version = eval.version,
            context = context,
            outcome = eval.outcome,
            reason = eval.reason,
            trace = eval.trace,
            evaluatedAt = now(),
            batchId = null
        )
        project = project.copy(records = (project.records + record).takeLast(500))
        persist()
        return eval to record
    }

    /**
     * Batch evaluation. A single [Snapshot] is built once before any flag runs,
     * so publishing during the batch cannot mix versions.
     */
    @Synchronized fun evaluateBatch(
        flagKeys: List<String>,
        contexts: List<Map<String, Any?>>,
        targetVersions: Map<String, Int> = emptyMap()
    ): BatchResult {
        val snap = snapshot()
        val batchId = "batch-" + shortId()
        val rows = ArrayList<BatchRow>()
        val records = ArrayList<EvaluationRecord>()
        for ((ci, ctx) in contexts.withIndex()) {
            for (fk in flagKeys) {
                val eval = engine.evaluate(snap, fk, ctx, targetVersions[fk])
                rows += BatchRow(ci, fk, eval)
                records += EvaluationRecord(
                    id = "rec-" + shortId(),
                    flagKey = fk, version = eval.version, context = ctx,
                    outcome = eval.outcome, reason = eval.reason, trace = eval.trace,
                    evaluatedAt = now(), batchId = batchId
                )
            }
        }
        project = project.copy(records = (project.records + records).takeLast(500))
        persist()
        return BatchResult(batchId, snap.versions.mapValues { it.value.version }, rows)
    }

    /** Re-run a historical record's stored context against its pinned version. */
    @Synchronized fun replay(recordId: String): Pair<EvaluationRecord, Evaluation> {
        val record = project.records.firstOrNull { it.id == recordId }
            ?: throw EvaluationException("Unknown record '$recordId'")
        val snap = snapshot()
        val eval = engine.evaluate(snap, record.flagKey, record.context, record.version)
        return record to eval
    }

    /** Compare two versions of one flag across a set of contexts. */
    @Synchronized fun compare(
        flagKey: String,
        versionA: Int,
        versionB: Int,
        contextIds: List<String>
    ): ComparisonResult {
        val contexts = project.savedContexts.filter { it.id in contextIds }
        val snap = snapshot()
        val rows = contexts.map { sc ->
            val a = engine.evaluate(snap, flagKey, sc.context, versionA)
            val b = engine.evaluate(snap, flagKey, sc.context, versionB)
            ComparisonRow(sc, a, b, a.outcome != b.outcome)
        }
        return ComparisonResult(flagKey, versionA, versionB, rows)
    }

    @Synchronized fun exportBundle(flagKeys: List<String>? = null, includeContexts: Boolean = true): Bundle {
        val flags = project.flags.values
            .filter { flagKeys == null || it.key in flagKeys }
            .sortedBy { it.key }
        return Bundle(
            name = project.name,
            bucketSalt = project.bucketSalt,
            sensitiveFields = project.sensitiveFields,
            flags = flags,
            savedContexts = if (includeContexts) project.savedContexts else emptyList()
        )
    }

    /**
     * Import a bundle. The bucket salt is preserved (buckets and trace order
     * stay identical); the summary secret is regenerated so sensitive
     * summaries cannot be correlated with the source project.
     */
    @Synchronized fun importBundle(bundle: Bundle, replace: Boolean = false) {
        // Validate each version and the whole prerequisite graph up front.
        for (flag in bundle.flags) {
            for (fv in flag.versions) {
                val errs = Validator.validateSingle(fv)
                require(errs.isEmpty()) { "Flag ${flag.key} v${fv.version}: ${errs.joinToString("; ")}" }
            }
        }
        val latest = bundle.flags.mapNotNull { it.current }.associateBy { it.key }
        Validator.findCycle(latest)?.let {
            throw IllegalArgumentException("Imported prerequisite cycle: ${it.joinToString(" -> ")}")
        }
        val mergedFlags = if (replace) bundle.flags.associateBy { it.key }.toMutableMap()
        else (project.flags + bundle.flags.associateBy { it.key }).toMutableMap()
        project = project.copy(
            name = bundle.name,
            bucketSalt = bundle.bucketSalt,
            sensitiveFields = bundle.sensitiveFields,
            flags = mergedFlags,
            savedContexts = if (replace) bundle.savedContexts
            else (project.savedContexts + bundle.savedContexts).distinctBy { it.id }
        )
        persist()
    }

    private fun persist() = store.save(project)
    private fun now() = System.currentTimeMillis()
    private fun shortId() = UUID.randomUUID().toString().replace("-", "").substring(0, 10)
}

data class BatchRow(val contextIndex: Int, val flagKey: String, val evaluation: Evaluation) {
    fun toJson(): Any? = linkedMapOf(
        "contextIndex" to contextIndex, "flagKey" to flagKey, "evaluation" to evaluation.toJson()
    )
}

data class BatchResult(
    val batchId: String,
    val snapshotVersions: Map<String, Int>,
    val rows: List<BatchRow>
) {
    fun toJson(): Any? = linkedMapOf(
        "batchId" to batchId,
        "snapshotVersions" to snapshotVersions,
        "rows" to rows.map { it.toJson() }
    )
}

data class ComparisonRow(
    val savedContext: SavedContext,
    val a: Evaluation,
    val b: Evaluation,
    val changed: Boolean
) {
    fun toJson(): Any? = linkedMapOf(
        "savedContext" to savedContext.toJson(),
        "changed" to changed,
        "a" to a.toJson(),
        "b" to b.toJson()
    )
}

data class ComparisonResult(
    val flagKey: String,
    val versionA: Int,
    val versionB: Int,
    val rows: List<ComparisonRow>
) {
    fun toJson(): Any? = linkedMapOf(
        "flagKey" to flagKey,
        "versionA" to versionA,
        "versionB" to versionB,
        "rows" to rows.map { it.toJson() }
    )
}
