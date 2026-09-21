package ft

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A saved evaluation. It is bound to the flag key and the exact version that
 * produced it: replaying history never silently follows later rule changes.
 *
 * The context is persisted so the exact input is replayable; the trace is
 * stored already redacted (sensitive values never hit disk in raw form inside
 * a trace).
 */
data class EvaluationRecord(
    val id: String,
    val flagKey: String,
    val version: Int,
    val context: Map<String, Any?>,
    val outcome: Outcome,
    val reason: String,
    val trace: TraceNode,
    val evaluatedAt: Long,
    val batchId: String?
) {
    fun toJson(): Any? = linkedMapOf(
        "id" to id,
        "flagKey" to flagKey,
        "version" to version,
        "context" to context,
        "outcome" to outcome.toJson(),
        "reason" to reason,
        "trace" to trace.toJson(),
        "evaluatedAt" to evaluatedAt,
        "batchId" to batchId
    )

    companion object {
        fun fromJson(v: Any?): EvaluationRecord {
            val m = Json.obj(v)
            return EvaluationRecord(
                id = Json.str(m["id"]),
                flagKey = Json.str(m["flagKey"]),
                version = Json.int(m["version"]),
                context = Json.obj(m["context"]),
                outcome = Outcome.fromJson(m["outcome"]),
                reason = Json.strOr(m["reason"], ""),
                trace = TraceNode.fromJson(m["trace"]),
                evaluatedAt = (m["evaluatedAt"] as? Number)?.toLong() ?: 0L,
                batchId = m["batchId"] as? String
            )
        }
    }
}

/** Atomic, single-file JSON persistence. */
class FileStore(private val path: Path) {
    fun load(): Project? {
        if (!Files.exists(path)) return null
        val text = Files.readString(path)
        if (text.isBlank()) return null
        return Project.fromJson(Json.parse(text))
    }

    fun save(project: Project) {
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, Json.stringify(project.toJson(), pretty = true))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
