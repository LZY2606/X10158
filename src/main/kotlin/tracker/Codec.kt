package tracker

fun JsonObject.string(key: String): String =
    (entries[key] as? JsonString)?.value ?: throw ValidationException("缺少字符串字段：$key")

fun JsonObject.stringOrNull(key: String): String? = (entries[key] as? JsonString)?.value

fun JsonObject.int(key: String): Int =
    (entries[key] as? JsonNumber)?.number?.toInt() ?: throw ValidationException("缺少数字字段：$key")

fun JsonObject.bool(key: String): Boolean =
    (entries[key] as? JsonBoolean)?.value ?: throw ValidationException("缺少布尔字段：$key")

fun JsonObject.array(key: String): List<JsonValue> =
    (entries[key] as? JsonArray)?.values ?: throw ValidationException("缺少数组字段：$key")

fun JsonObject.obj(key: String): JsonObject =
    entries[key] as? JsonObject ?: throw ValidationException("缺少对象字段：$key")

fun JsonObject.valueOrNull(key: String): JsonValue? = entries[key]

fun conditionToJson(condition: Condition) = JsonObject(mapOf(
    "field" to JsonString(condition.field),
    "operator" to JsonString(condition.operator.name),
    "value" to condition.value,
    "sensitive" to JsonBoolean(condition.sensitive)
))

fun conditionFromJson(json: JsonObject) = Condition(
    field = json.string("field"),
    operator = CompareOperator.valueOf(json.string("operator")),
    value = json.valueOrNull("value") ?: JsonNull,
    sensitive = (json.valueOrNull("sensitive") as? JsonBoolean)?.value ?: false
)

fun ruleToJson(rule: Rule) = JsonObject(mapOf(
    "id" to JsonString(rule.id),
    "name" to JsonString(rule.name),
    "conditions" to JsonArray(rule.conditions.map(::conditionToJson)),
    "result" to rule.result
))

fun ruleFromJson(json: JsonObject) = Rule(
    id = json.string("id"),
    name = json.string("name"),
    conditions = json.array("conditions").map { conditionFromJson(it as JsonObject) },
    result = json.valueOrNull("result") ?: JsonNull
)

fun sliceToJson(slice: PercentageSlice) = JsonObject(mapOf(
    "id" to JsonString(slice.id),
    "name" to JsonString(slice.name),
    "result" to slice.result,
    "weightBasisPoints" to JsonNumber(slice.weightBasisPoints.toString())
))

fun sliceFromJson(json: JsonObject) = PercentageSlice(
    id = json.string("id"),
    name = json.string("name"),
    result = json.valueOrNull("result") ?: JsonNull,
    weightBasisPoints = json.int("weightBasisPoints")
)

fun rolloutToJson(rollout: PercentageRollout) = JsonObject(mapOf(
    "identityField" to JsonString(rollout.identityField),
    "salt" to JsonString(rollout.salt),
    "slices" to JsonArray(rollout.slices.map(::sliceToJson))
))

fun rolloutFromJson(json: JsonObject) = PercentageRollout(
    identityField = json.string("identityField"),
    salt = json.string("salt"),
    slices = json.array("slices").map { sliceFromJson(it as JsonObject) }
)

fun prerequisiteToJson(prerequisite: Prerequisite) = JsonObject(mapOf(
    "flagKey" to JsonString(prerequisite.flagKey),
    "matcher" to JsonString(prerequisite.matcher.name),
    "expected" to prerequisite.expected
))

fun prerequisiteFromJson(json: JsonObject) = Prerequisite(
    flagKey = json.string("flagKey"),
    matcher = PrerequisiteMatcher.valueOf(json.string("matcher")),
    expected = json.valueOrNull("expected") ?: JsonNull
)

fun versionToJson(version: FlagVersion) = JsonObject(mapOf(
    "id" to JsonString(version.id),
    "key" to JsonString(version.key),
    "name" to JsonString(version.name),
    "version" to JsonNumber(version.version.toString()),
    "createdAt" to JsonString(version.createdAt),
    "note" to JsonString(version.note),
    "defaultValue" to version.defaultValue,
    "prerequisites" to JsonArray(version.prerequisites.map(::prerequisiteToJson)),
    "rules" to JsonArray(version.rules.map(::ruleToJson)),
    "rollout" to (version.rollout?.let(::rolloutToJson) ?: JsonNull),
    "sensitiveFields" to JsonArray(version.sensitiveFields.map(::JsonString))
))

fun versionFromJson(json: JsonObject) = FlagVersion(
    id = json.string("id"),
    key = json.string("key"),
    name = json.string("name"),
    version = json.int("version"),
    createdAt = json.string("createdAt"),
    note = json.stringOrNull("note") ?: "",
    defaultValue = json.valueOrNull("defaultValue") ?: JsonNull,
    prerequisites = (json.valueOrNull("prerequisites") as? JsonArray)?.values
        ?.map { prerequisiteFromJson(it as JsonObject) } ?: emptyList(),
    rules = (json.valueOrNull("rules") as? JsonArray)?.values
        ?.map { ruleFromJson(it as JsonObject) } ?: emptyList(),
    rollout = (json.valueOrNull("rollout") as? JsonObject)?.let(::rolloutFromJson),
    sensitiveFields = (json.valueOrNull("sensitiveFields") as? JsonArray)?.values
        ?.mapNotNull { (it as? JsonString)?.value } ?: emptyList()
)

fun flagToJson(flag: Flag) = JsonObject(mapOf(
    "key" to JsonString(flag.key),
    "name" to JsonString(flag.name),
    "currentVersionId" to JsonString(flag.currentVersionId),
    "versions" to JsonArray(flag.versions.map(::versionToJson))
))

fun flagFromJson(json: JsonObject) = Flag(
    key = json.string("key"),
    name = json.string("name"),
    currentVersionId = json.string("currentVersionId"),
    versions = json.array("versions").map { versionFromJson(it as JsonObject) }
)

fun contextToJson(context: SavedContext) = JsonObject(mapOf(
    "id" to JsonString(context.id),
    "name" to JsonString(context.name),
    "createdAt" to JsonString(context.createdAt),
    "context" to context.context
))

fun contextFromJson(json: JsonObject) = SavedContext(
    id = json.string("id"),
    name = json.string("name"),
    createdAt = json.string("createdAt"),
    context = json.obj("context")
)

fun traceToJson(trace: TraceStep): JsonObject = JsonObject(mapOf(
    "type" to JsonString(trace.type),
    "title" to JsonString(trace.title),
    "status" to JsonString(trace.status),
    "detail" to trace.detail,
    "children" to JsonArray(trace.children.map(::traceToJson))
))

fun traceFromJson(json: JsonObject): TraceStep = TraceStep(
    type = json.string("type"),
    title = json.string("title"),
    status = json.string("status"),
    detail = json.obj("detail"),
    children = (json.valueOrNull("children") as? JsonArray)?.values
        ?.map { traceFromJson(it as JsonObject) } ?: emptyList()
)

fun evaluationToJson(result: EvaluationResult) = JsonObject(mapOf(
    "flagKey" to JsonString(result.flagKey),
    "versionId" to JsonString(result.versionId),
    "result" to result.result,
    "reason" to JsonString(result.reason),
    "fieldsRead" to JsonArray(result.fieldsRead.map(::JsonString)),
    "trace" to traceToJson(result.trace)
))

fun evaluationFromJson(json: JsonObject) = EvaluationResult(
    flagKey = json.string("flagKey"),
    versionId = json.string("versionId"),
    result = json.valueOrNull("result") ?: JsonNull,
    reason = json.string("reason"),
    fieldsRead = (json.valueOrNull("fieldsRead") as? JsonArray)?.values
        ?.mapNotNull { (it as? JsonString)?.value } ?: emptyList(),
    trace = traceFromJson(json.obj("trace"))
)

fun recordToJson(record: EvaluationRecord) = JsonObject(mapOf(
    "id" to JsonString(record.id),
    "createdAt" to JsonString(record.createdAt),
    "flagKey" to JsonString(record.flagKey),
    "versionId" to JsonString(record.versionId),
    "contextName" to JsonString(record.contextName),
    "context" to record.context,
    "result" to evaluationToJson(record.result)
))

fun recordFromJson(json: JsonObject) = EvaluationRecord(
    id = json.string("id"),
    createdAt = json.string("createdAt"),
    flagKey = json.string("flagKey"),
    versionId = json.string("versionId"),
    contextName = json.string("contextName"),
    context = json.obj("context"),
    result = evaluationFromJson(json.obj("result"))
)

fun projectToJson(project: Project) = JsonObject(mapOf(
    "id" to JsonString(project.id),
    "name" to JsonString(project.name),
    "secretHex" to JsonString(project.secretHex),
    "createdAt" to JsonString(project.createdAt),
    "flags" to JsonArray(project.flags.map(::flagToJson)),
    "contexts" to JsonArray(project.contexts.map(::contextToJson)),
    "records" to JsonArray(project.records.map(::recordToJson))
))

fun projectFromJson(json: JsonObject) = Project(
    id = json.string("id"),
    name = json.string("name"),
    secretHex = json.string("secretHex"),
    createdAt = json.string("createdAt"),
    flags = (json.valueOrNull("flags") as? JsonArray)?.values
        ?.map { flagFromJson(it as JsonObject) } ?: emptyList(),
    contexts = (json.valueOrNull("contexts") as? JsonArray)?.values
        ?.map { contextFromJson(it as JsonObject) } ?: emptyList(),
    records = (json.valueOrNull("records") as? JsonArray)?.values
        ?.map { recordFromJson(it as JsonObject) } ?: emptyList()
)
