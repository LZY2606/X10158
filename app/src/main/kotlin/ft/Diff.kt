package ft

object Diff {
    fun compare(p: Project, flagKey: String, a: Int, b: Int, contextIds: List<String>): JsonValue {
        val flag = p.flags[flagKey] ?: throw AppException(404, "flag '$flagKey' not found")
        val va = flag.versions[a] ?: throw AppException(404, "version $a not found")
        val vb = flag.versions[b] ?: throw AppException(404, "version $b not found")

        val changes = mutableListOf<JsonValue>()
        fun add(field: String, from: JsonValue?, to: JsonValue?) {
            if (!jsonSafeEqual(from, to)) {
                changes.add(Json.obj(
                    "field" to Json.s(field),
                    "from" to (from ?: JNull),
                    "to" to (to ?: JNull),
                ))
            }
        }
        add("default", va.default, vb.default)
        add("salt", Json.s(va.salt), Json.s(vb.salt))
        add("stableIdentityField", Json.s(va.stableIdentityField), Json.s(vb.stableIdentityField))
        add("prerequisiteKey", va.prerequisiteKey?.let(Json::s), vb.prerequisiteKey?.let(Json::s))
        add("prerequisiteExpected", va.prerequisiteExpected, vb.prerequisiteExpected)
        add("sensitiveFields", JArr(va.sensitiveFields.map(Json::s)), JArr(vb.sensitiveFields.map(Json::s)))
        add("rules", JArr(va.rules.map(Codecs::encodeRule)), JArr(vb.rules.map(Codecs::encodeRule)))

        val rows = contextIds.map { ctxId ->
            val ctx = p.contexts[ctxId] ?: throw AppException(404, "context '$ctxId' not found")
            val ra = Evaluator.evaluate(p, flagKey, ctx.data, version = a)
            val rb = Evaluator.evaluate(p, flagKey, ctx.data, version = b)
            Json.obj(
                "contextId" to Json.s(ctx.id),
                "contextName" to Json.s(ctx.name),
                "valueA" to ra.value,
                "valueB" to rb.value,
                "changed" to Json.b(!Json.equal(ra.value, rb.value)),
                "traceA" to Codecs.encodeTrace(ra.trace),
                "traceB" to Codecs.encodeTrace(rb.trace),
            )
        }

        return Json.obj(
            "flagKey" to Json.s(flagKey),
            "versionA" to Json.n(a),
            "versionB" to Json.n(b),
            "changes" to JArr(changes),
            "contextRows" to JArr(rows),
        )
    }

    private fun jsonSafeEqual(a: JsonValue?, b: JsonValue?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> canonicalString(a) == canonicalString(b)
    }

    private fun canonicalString(v: JsonValue): String = Hashing.canonical(v)
}
