package ft

/** Encodes/decodes the whole domain model as JSON (storage + export/import). */
object Codec {
    fun bundleToJson(bundle: Bundle): JsonObject = jsonObject {
        "format" to json("feature-flag-tracker-bundle")
        "formatVersion" to json(1)
        "exportedAtMs" to json(bundle.exportedAtMs)
        "projects" to JsonArray(bundle.projects.map { projectToJson(it) })
        "flags" to JsonArray(bundle.flags.map { flagToJson(it) })
        "contexts" to JsonArray(bundle.contexts.map { savedContextToJson(it) })
        "records" to JsonArray(bundle.records.map { recordToJson(it) })
    }

    fun bundleFromJson(value: JsonValue): Bundle {
        val obj = value.asObject ?: throw IllegalArgumentException("bundle must be an object")
        return Bundle(
            exportedAtMs = obj["exportedAtMs"].asNumber?.long ?: System.currentTimeMillis(),
            projects = obj["projects"].asArray?.items?.map { projectFromJson(it) }.orEmpty(),
            flags = obj["flags"].asArray?.items?.map { flagFromJson(it) }.orEmpty(),
            contexts = obj["contexts"].asArray?.items?.map { savedContextFromJson(it) }.orEmpty(),
            records = obj["records"].asArray?.items?.map { recordFromJson(it) }.orEmpty()
        )
    }

    fun projectToJson(p: Project): JsonObject = jsonObject {
        "id" to json(p.id)
        "name" to json(p.name)
        "digestSalt" to json(p.digestSalt)
        "sensitiveFields" to JsonArray(p.sensitiveFields.map { json(it) })
    }

    fun projectFromJson(v: JsonValue): Project {
        val o = v.asObject!!
        return Project(
            id = o["id"].asString!!,
            name = o["name"].asString ?: "",
            digestSalt = o["digestSalt"].asString
                ?: throw IllegalArgumentException("project missing digestSalt"),
            sensitiveFields = o["sensitiveFields"].asArray?.items
                ?.mapNotNull { it.asString }.orEmpty()
        )
    }

    fun serveToJson(s: ServeValue): JsonValue = json(s.name)
    fun serveFromJson(v: JsonValue?): ServeValue =
        ServeValue.parse(v.asString ?: throw IllegalArgumentException("serve must be a string"))

    fun conditionToJson(c: Condition): JsonObject = jsonObject {
        "field" to json(c.field)
        "operator" to json(c.operator.wire)
        if (c.value != null) "value" to c.value
    }

    fun conditionFromJson(v: JsonValue): Condition {
        val o = v.asObject!!
        return Condition(
            field = o["field"].asString!!,
            operator = Operator.fromWire(o["operator"].asString!!),
            value = o["value"]
        )
    }

    fun clauseToJson(c: RolloutClause): JsonObject = jsonObject {
        "serve" to serveToJson(c.serve)
        "weightBp" to json(c.weightBp)
    }

    fun clauseFromJson(v: JsonValue): RolloutClause {
        val o = v.asObject!!
        return RolloutClause(
            serve = serveFromJson(o["serve"]),
            weightBp = o["weightBp"].asNumber?.long?.toInt()
                ?: throw IllegalArgumentException("weightBp required")
        )
    }

    fun ruleToJson(r: Rule): JsonObject = jsonObject {
        "id" to json(r.id)
        "name" to json(r.name)
        "conditions" to JsonArray(r.conditions.map { conditionToJson(it) })
        if (r.serve != null) "serve" to serveToJson(r.serve)
        if (r.rollout != null) "rollout" to JsonArray(r.rollout.map { clauseToJson(it) })
        if (r.fallbackServe != null) "fallbackServe" to serveToJson(r.fallbackServe)
        "salt" to json(r.salt)
    }

    fun ruleFromJson(v: JsonValue): Rule {
        val o = v.asObject!!
        return Rule(
            id = o["id"].asString!!,
            name = o["name"].asString ?: "",
            conditions = o["conditions"].asArray?.items?.map { conditionFromJson(it) }.orEmpty(),
            serve = o["serve"]?.let { serveFromJson(it) },
            rollout = o["rollout"]?.asArray?.items?.map { clauseFromJson(it) },
            fallbackServe = o["fallbackServe"]?.let { serveFromJson(it) },
            salt = o["salt"].asString ?: ""
        )
    }

    fun prereqToJson(p: Prerequisite): JsonObject = jsonObject {
        "flagKey" to json(p.flagKey)
        "anyOf" to JsonArray(p.anyOf.map { serveToJson(it) })
        "gateServe" to serveToJson(p.gateServe)
    }

    fun prereqFromJson(v: JsonValue): Prerequisite {
        val o = v.asObject!!
        return Prerequisite(
            flagKey = o["flagKey"].asString!!,
            anyOf = o["anyOf"].asArray?.items?.map { serveFromJson(it) }.orEmpty(),
            gateServe = o["gateServe"]?.let { serveFromJson(it) } ?: ServeValue.OFF
        )
    }

    fun versionToJson(v: FlagVersion): JsonObject = jsonObject {
        "version" to json(v.version)
        "rules" to JsonArray(v.rules.map { ruleToJson(it) })
        "prerequisites" to JsonArray(v.prerequisites.map { prereqToJson(it) })
        "defaultValue" to serveToJson(v.defaultValue)
        "stableIdField" to json(v.stableIdField)
        "note" to json(v.note)
        "createdAtMs" to json(v.createdAtMs)
    }

    fun versionFromJson(v: JsonValue): FlagVersion {
        val o = v.asObject!!
        return FlagVersion(
            version = o["version"].asNumber?.long?.toInt() ?: 1,
            rules = o["rules"].asArray?.items?.map { ruleFromJson(it) }.orEmpty(),
            prerequisites = o["prerequisites"].asArray?.items
                ?.map { prereqFromJson(it) }.orEmpty(),
            defaultValue = serveFromJson(o["defaultValue"]),
            stableIdField = o["stableIdField"].asString ?: "user.id",
            note = o["note"].asString ?: "",
            createdAtMs = o["createdAtMs"].asNumber?.long ?: 0L
        )
    }

    fun flagToJson(f: Flag): JsonObject = jsonObject {
        "projectId" to json(f.projectId)
        "key" to json(f.key)
        "name" to json(f.name)
        "description" to json(f.description)
        "versions" to JsonArray(f.versions.map { versionToJson(it) })
        "draftRules" to JsonArray(f.draftRules.map { ruleToJson(it) })
        "draftPrerequisites" to JsonArray(f.draftPrerequisites.map { prereqToJson(it) })
        "draftDefaultValue" to serveToJson(f.draftDefaultValue)
        "draftStableIdField" to json(f.draftStableIdField)
    }

    fun flagFromJson(v: JsonValue): Flag {
        val o = v.asObject!!
        return Flag(
            projectId = o["projectId"].asString!!,
            key = o["key"].asString!!,
            name = o["name"].asString ?: "",
            description = o["description"].asString ?: "",
            versions = o["versions"].asArray?.items?.map { versionFromJson(it) }.orEmpty(),
            draftRules = o["draftRules"].asArray?.items?.map { ruleFromJson(it) }.orEmpty(),
            draftPrerequisites = o["draftPrerequisites"].asArray?.items
                ?.map { prereqFromJson(it) }.orEmpty(),
            draftDefaultValue = o["draftDefaultValue"]?.let { serveFromJson(it) } ?: ServeValue.OFF,
            draftStableIdField = o["draftStableIdField"].asString ?: "user.id"
        )
    }

    fun savedContextToJson(c: SavedContext): JsonObject = jsonObject {
        "id" to json(c.id)
        "name" to json(c.name)
        "context" to c.context
        "createdAtMs" to json(c.createdAtMs)
    }

    fun savedContextFromJson(v: JsonValue): SavedContext {
        val o = v.asObject!!
        return SavedContext(
            id = o["id"].asString!!,
            name = o["name"].asString ?: "",
            context = o["context"].asObject ?: JsonObject(emptyMap()),
            createdAtMs = o["createdAtMs"].asNumber?.long ?: 0L
        )
    }

    fun recordToJson(r: EvalRecord): JsonObject = jsonObject {
        "id" to json(r.id)
        "projectId" to json(r.projectId)
        "flagKey" to json(r.flagKey)
        "version" to json(r.version)
        "contextName" to json(r.contextName)
        "context" to r.context
        "result" to json(r.result.name)
        "trace" to r.trace
        "createdAtMs" to json(r.createdAtMs)
    }

    fun recordFromJson(v: JsonValue): EvalRecord {
        val o = v.asObject!!
        return EvalRecord(
            id = o["id"].asString!!,
            projectId = o["projectId"].asString!!,
            flagKey = o["flagKey"].asString!!,
            version = o["version"].asNumber?.long?.toInt() ?: 1,
            contextName = o["contextName"].asString ?: "",
            context = o["context"].asObject ?: JsonObject(emptyMap()),
            result = ServeValue.parse(o["result"].asString ?: "off"),
            trace = o["trace"].asObject ?: JsonObject(emptyMap()),
            createdAtMs = o["createdAtMs"].asNumber?.long ?: 0L
        )
    }
}
