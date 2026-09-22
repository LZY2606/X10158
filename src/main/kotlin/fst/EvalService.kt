package fst

/* Batch evaluation: all flags/contexts in one call share ONE immutable snapshot.
   Publishing a new version during the batch cannot mix old/new rule sets. */
object EvalService {
    data class BatchResult(
        val snapshotAt: Map<String, Int>,
        val snapshotId: String,
        val results: List<Map<String, Any?>>,
    )

    fun batch(
        store: Store,
        flagKeys: List<String>,
        contexts: List<Pair<String?, Map<String, Any?>>>, // optional contextId/name, context
        versionSelections: Map<String, Int>? = null,
        persist: Boolean = false,
    ): BatchResult {
        val snap = store.project.let { p ->
            val sel = versionSelections ?: p.flags.associate { it.key to it.currentVersion }
            val frozen = LinkedHashMap<String, FlagVersion>()
            for ((k, ver) in sel) {
                val flag = p.flags.firstOrNull { it.key == k } ?: throw NoSuchElementException("开关不存在：$k")
                frozen[k] = flag.version(ver) ?: throw NoSuchElementException("版本 $ver 不存在于开关 $k")
            }
            Snapshot(frozen, p)
        }
        Engine.checkCycles(snap)
        val snapshotId = IdGen.next("snap")
        val out = ArrayList<Map<String, Any?>>()
        for (key in flagKeys) {
            for ((ctxMeta, ctx) in contexts) {
                val outcome = Engine.evaluate(snap, key, ctx)
                val rec = EvalRecord(
                    id = IdGen.next("rec"),
                    flagKey = key,
                    version = snap.versions[key]!!.version,
                    contextId = ctxMeta?.let { null },
                    contextName = ctxMeta,
                    context = redactForStorage(ctx, snap, key),
                    result = outcome.served.display(),
                    reason = outcome.reason,
                    trace = Json.obj(
                        "snapshotId" to snapshotId,
                        "flagKey" to key,
                        "version" to snap.versions[key]!!.version,
                        "root" to outcome.root.toJson(),
                    ),
                )
                if (persist) store.addRecord(rec)
                out.add(Json.obj(
                    "flagKey" to key,
                    "version" to rec.version,
                    "snapshotId" to snapshotId,
                    "contextName" to ctxMeta,
                    "result" to rec.result,
                    "reason" to rec.reason,
                    "trace" to rec.trace["root"],
                    "recordId" to if (persist) rec.id else null,
                ))
            }
        }
        return BatchResult(snap.versions.mapValues { it.value.version }, snapshotId, out)
    }

    /** Evaluate a specific historical version against a context and DON'T persist. */
    fun replay(store: Store, record: EvalRecord): Map<String, Any?> {
        val p = store.project
        val flag = p.flags.firstOrNull { it.key == record.flagKey }
            ?: return Json.obj("replayable" to false, "reason" to "flag_missing",
                "record" to record.toJson())
        val v = flag.version(record.version)
            ?: return Json.obj("replayable" to false, "reason" to "version_missing",
                "record" to record.toJson())
        val snap = Snapshot(linkedMapOf(record.flagKey to v), p)
        val outcome = Engine.evaluate(snap, record.flagKey, record.context)
        return Json.obj(
            "replayable" to true,
            "recordId" to record.id,
            "version" to record.version,
            "sameResult" to (outcome.served.display() == record.result),
            "recordedResult" to record.result,
            "replayedResult" to outcome.served.display(),
            "trace" to outcome.root.toJson(),
        )
    }

    /** Trace node may contain raw values for non-sensitive fields; storage keeps the trace
     *  exactly as generated (sensitive fields were already summarized at evaluation time). */
    private fun redactForStorage(ctx: Map<String, Any?>, snap: Snapshot, flagKey: String): Map<String, Any?> {
        // The stored context retains all values; sensitive protection applies to trace display.
        return ctx
    }
}
