package ft

/**
 * Incoming draft for a new flag version. Defaults and values are validated
 * against the flag type; rollout weights are summed and bounded here.
 */
data class VersionDraft(
    val type: FlagType,
    val default: JsonValue,
    val salt: String,
    val stableIdentityField: String,
    val prerequisiteKey: String?,
    val prerequisiteExpected: JsonValue?,
    val sensitiveFields: List<String>,
    val rules: List<Rule>,
)

class Service(private val store: Store) {

    fun snapshot(): Project = store.read { it }

    fun createFlag(key: String, name: String, draft: VersionDraft): Flag {
        store.update { p ->
            if (key in p.flags) throw AppException(409, "flag '$key' already exists")
            validateDraft(draft, p, selfKey = key)
            val fv = buildVersion(1, draft)
            val flag = Flag(key, name.ifBlank { key }, 1, mapOf(1 to fv))
            p.copy(flags = p.flags + (key to flag))
        }
        return store.read { it.flags.getValue(key) }
    }

    /** Publishes a new immutable version; rule order/weights/etc. are frozen at this point. */
    fun publishVersion(key: String, draft: VersionDraft): FlagVersion {
        var published: FlagVersion? = null
        store.update { p ->
            val flag = p.flags[key] ?: throw AppException(404, "flag '$key' not found")
            validateDraft(draft, p, selfKey = key)
            val nextV = (flag.versions.keys.maxOrNull() ?: 0) + 1
            val fv = buildVersion(nextV, draft)
            published = fv
            val updatedFlag = flag.copy(
                currentVersion = nextV,
                versions = flag.versions + (nextV to fv),
            )
            p.copy(flags = p.flags + (key to updatedFlag))
        }
        return published!!
    }

    fun renameFlag(key: String, name: String) {
        store.update { p ->
            val f = p.flags[key] ?: throw AppException(404, "flag '$key' not found")
            p.copy(flags = p.flags + (key to f.copy(name = name)))
        }
    }

    fun saveContext(name: String, data: JObj, id: String? = null): SavedContext {
        val ctxId = id ?: ("ctx_" + Store.shortId())
        val saved = SavedContext(ctxId, name, data, Store.now())
        store.update { p -> p.copy(contexts = p.contexts + (ctxId to saved)) }
        return saved
    }

    fun deleteContext(id: String) {
        store.update { p ->
            if (id !in p.contexts) throw AppException(404, "context '$id' not found")
            p.copy(contexts = p.contexts - id)
        }
    }

    fun evaluate(
        flagKey: String,
        context: JObj,
        version: Int? = null,
        pinnedVersions: Map<String, Int>? = null,
        saveRecord: Boolean = false,
        contextId: String? = null,
        contextName: String? = null,
    ): EvalResult {
        val (result, vnum) = store.read { p ->
            val snap = Snapshot(p, pinnedVersions)
            val r = Evaluator.evaluate(p, flagKey, context, version = version, snapshot = snap)
            val resolved = version ?: snap.currentVersionNumber(flagKey)
                ?: throw AppException(404, "flag '$flagKey' not found in snapshot")
            r to resolved
        }
        if (saveRecord) {
            store.update { p ->
                val rec = EvalRecord(
                    id = "rec_" + Store.shortId(),
                    flagKey = flagKey,
                    version = vnum,
                    contextId = contextId,
                    contextName = contextName,
                    contextSnapshot = context,
                    result = result.value,
                    trace = result.trace,
                    createdAt = Store.now(),
                )
                p.copy(records = (p.records + rec).takeLast(1000))
            }
        }
        return result
    }

    /**
     * A batch resolves ONE version snapshot up-front and evaluates every
     * requested flag (and its prerequisite chain) against it. A publish that
     * lands during the batch cannot mix versions into the result.
     */
    fun evaluateBatch(
        flagKeys: List<String>,
        context: JObj,
        pinnedVersions: Map<String, Int>? = null,
        saveRecord: Boolean = false,
        contextId: String? = null,
        contextName: String? = null,
    ): BatchOutcome {
        val outcome = store.read { p ->
            val versions = snapshotVersions(p, pinnedVersions)
            val snap = Snapshot(p, versions)
            val results = flagKeys.map { key ->
                key to Evaluator.evaluate(p, key, context, version = snap.currentVersionNumber(key), snapshot = snap)
            }
            BatchOutcome(versions, results, context)
        }
        if (saveRecord) {
            store.update { p ->
                val records = outcome.results.map { (key, result) ->
                    EvalRecord(
                        id = "rec_" + Store.shortId(),
                        flagKey = key,
                        version = outcome.snapshotVersions.getValue(key),
                        contextId = contextId,
                        contextName = contextName,
                        contextSnapshot = context,
                        result = result.value,
                        trace = result.trace,
                        createdAt = Store.now(),
                    )
                }
                p.copy(records = (p.records + records).takeLast(1000))
            }
        }
        return outcome
    }

    data class BatchOutcome(
        val snapshotVersions: Map<String, Int>,
        val results: List<Pair<String, EvalResult>>,
        val context: JObj,
    )

    private fun snapshotVersions(p: Project, pinned: Map<String, Int>?): Map<String, Int> =
        p.flags.mapValues { (key, flag) -> pinned?.get(key) ?: flag.currentVersion }

    fun records(flagKey: String? = null): List<EvalRecord> = store.read { p ->
        (if (flagKey != null) p.records.filter { it.flagKey == flagKey } else p.records).asReversed()
    }

    /** Historical replay always uses the version baked into the record. */
    fun replayRecord(recordId: String): Pair<EvalRecord, EvalResult> = store.read { p ->
        val rec = p.records.firstOrNull { it.id == recordId }
            ?: throw AppException(404, "record '$recordId' not found")
        val fresh = Evaluator.evaluate(p, rec.flagKey, rec.contextSnapshot, version = rec.version)
        rec to fresh
    }

    fun export(): String = store.read { Json.write(Codecs.encodeProject(it)) }

    /**
     * Import flags/contexts from an export. Because salt, rules and versions
     * are copied verbatim, buckets and trace order remain identical.
     *
     * MERGE keeps this project's identity; a foreign file gets a fresh domain
     * secret so its sensitive summary tags cannot be correlated with local
     * ones. REPLACE is a restore: the file's project id, secret and records
     * are taken verbatim.
     */
    fun import(json: String, mode: ImportMode): Project = store.update { current ->
        val incomingRaw = Codecs.decodeProject(Json.parse(json))
        when (mode) {
            ImportMode.MERGE -> {
                val foreign = incomingRaw.id != current.id
                val incoming = if (foreign) {
                    incomingRaw.copy(
                        id = current.id,
                        domainSecret = Store.newSecret(),
                        records = emptyList(),
                    )
                } else incomingRaw
                current.copy(
                    flags = current.flags + incoming.flags,
                    contexts = current.contexts + incoming.contexts,
                    records = if (foreign) current.records else (current.records + incoming.records).takeLast(1000),
                )
            }
            ImportMode.REPLACE -> incomingRaw
        }
    }


    enum class ImportMode { MERGE, REPLACE }

    private fun buildVersion(num: Int, d: VersionDraft): FlagVersion = FlagVersion(
        version = num,
        type = d.type,
        default = d.default,
        salt = d.salt,
        stableIdentityField = d.stableIdentityField,
        prerequisiteKey = d.prerequisiteKey,
        prerequisiteExpected = d.prerequisiteExpected,
        sensitiveFields = d.sensitiveFields,
        rules = d.rules,
        createdAt = Store.now(),
    )

    private fun validateDraft(d: VersionDraft, p: Project, selfKey: String) {
        require(d.salt.isNotBlank()) { "salt must not be blank (keeps rollout stable across rule edits)" }
        require(d.stableIdentityField.isNotBlank()) { "stableIdentityField is required" }
        val ruleIds = mutableSetOf<String>()
        d.rules.forEach { rule ->
            require(rule.id.isNotBlank()) { "rule id must not be blank" }
            require(ruleIds.add(rule.id)) { "duplicate rule id '${rule.id}'" }
            val hasValue = rule.value != null
            val hasRollout = rule.rollout != null
            require(hasValue xor hasRollout) {
                "rule '${rule.id}' must set exactly one of value or rollout"
            }
            rule.conditions.forEach { validateCondition(it) }
            if (hasValue) validateValueForType(d.type, rule.value!!, "rule '${rule.id}'")
            if (hasRollout) {
                require(rule.rollout!!.isNotEmpty()) { "rule '${rule.id}' rollout is empty" }
                var sum = 0
                rule.rollout.forEach { slice ->
                    require(slice.weightBp in 1..Hashing.BP_TOTAL) {
                        "rollout slice '${slice.variant}' weight must be in 1..${Hashing.BP_TOTAL}"
                    }
                    sum += slice.weightBp
                }
                require(sum <= Hashing.BP_TOTAL) {
                    "rollout weights for rule '${rule.id}' total $sum > ${Hashing.BP_TOTAL}"
                }
                rule.rollout.forEach {
                    validateValueForType(d.type, scalarVariant(d.type, it.variant), "slice '${it.variant}'")
                }
            }
        }
        validateValueForType(d.type, d.default, "default")
        if (d.prerequisiteKey != null) {
            if (d.prerequisiteKey == selfKey) {
                throw AppException(422, "prerequisite cycle detected: $selfKey -> $selfKey")
            }
            val dep = p.flags[d.prerequisiteKey]
                ?: throw AppException(400, "prerequisite flag '${d.prerequisiteKey}' does not exist")
            val depType = dep.versions.getValue(dep.currentVersion).type
            require(d.prerequisiteExpected != null) { "prerequisiteExpected is required" }
            validateValueForType(depType, d.prerequisiteExpected, "prerequisiteExpected")
        }
        detectCycle(p, d, selfKey)
    }

    private fun scalarVariant(type: FlagType, variant: String): JsonValue = when (type) {
        FlagType.BOOLEAN -> when (variant) {
            "on", "true" -> Json.b(true)
            "off", "false" -> Json.b(false)
            else -> throw AppException(400, "boolean rollout variants must be on/off, got '$variant'")
        }
        FlagType.STRING -> Json.s(variant)
    }

    private fun validateValueForType(type: FlagType, v: JsonValue, where: String) {
        when (type) {
            FlagType.BOOLEAN -> require(v is JBool) { "$where must be a boolean for a boolean flag" }
            FlagType.STRING -> require(v is JStr) { "$where must be a string for a string flag" }
        }
    }

    private fun validateCondition(c: Condition) {
        require(c.field.isNotBlank()) { "condition field must not be blank" }
        when (c.op) {
            Operator.IN, Operator.NOT_IN -> require(c.argument is JArr) {
                "operator ${c.op.wire} needs an array argument"
            }
            else -> {}
        }
    }

    /** Whole-graph prerequisite cycle check, treating the draft as [selfKey]'s version. */
    private fun detectCycle(p: Project, draft: VersionDraft, selfKey: String) {
        fun depsOf(key: String): String? {
            if (key == selfKey) return draft.prerequisiteKey
            val f = p.flags[key] ?: return null
            return f.versions[f.currentVersion]?.prerequisiteKey
        }

        val state = mutableMapOf<String, Int>() // 0=visiting,1=done
        fun dfs(start: String) {
            val stack = ArrayDeque<Pair<String, List<String>>>()
            state[start] = 0
            stack.addLast(start to listOf(start))
            while (stack.isNotEmpty()) {
                val (node, path) = stack.last()
                val dep = depsOf(node)
                if (dep == null) {
                    state[node] = 1
                    stack.removeLast()
                    continue
                }
                when (state[dep]) {
                    0 -> {
                        val cycle = path.drop(path.indexOf(dep).coerceAtLeast(0)) + dep
                        throw AppException(422, "prerequisite cycle detected: " + cycle.joinToString(" -> "))
                    }
                    1 -> {
                        state[node] = 1
                        stack.removeLast()
                    }
                    else -> {
                        state[dep] = 0
                        stack.addLast(dep to (path + dep))
                    }
                }
            }
        }
        dfs(selfKey)
    }
}
