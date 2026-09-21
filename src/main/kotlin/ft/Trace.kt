package ft

/** One node in the human-explainable evaluation tree. */
data class TraceNode(
    val kind: String,
    val title: String,
    val matched: Boolean? = null,
    val detail: Map<String, Any?> = emptyMap(),
    val children: List<TraceNode> = emptyList()
) {
    fun toJson(): Any? = linkedMapOf<String, Any?>(
        "kind" to kind,
        "title" to title,
        "matched" to matched,
        "detail" to detail,
        "children" to children.map { it.toJson() }
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(v: Any?): TraceNode {
            val m = Json.obj(v)
            return TraceNode(
                kind = Json.str(m["kind"]),
                title = Json.str(m["title"]),
                matched = m["matched"] as? Boolean,
                detail = (m["detail"] as? Map<String, Any?>) ?: emptyMap(),
                children = (m["children"]?.let { Json.arr(it) } ?: emptyList())
                    .map { fromJson(it!!) }
            )
        }
    }
}

/**
 * Renders values for traces. Sensitive fields are replaced by a stable summary:
 * the same input always yields the same summary (so a trace proves equality),
 * but the secret is per-project, so summaries cannot be linked across projects.
 */
class Redactor(
    private val sensitiveFields: Set<String>,
    private val summarySecret: String
) {
    fun isSensitive(field: String) = sensitiveFields.contains(field) ||
        sensitiveFields.any { f -> field == f || field.startsWith("$f.") || field.endsWith(".$f") }

    /** A raw value representation used inside detail maps before storage/render. */
    fun valueDetail(field: String, value: Any?, present: Boolean): Map<String, Any?> {
        if (!isSensitive(field)) {
            return linkedMapOf("field" to field, "present" to present, "value" to value, "sensitive" to false)
        }
        return linkedMapOf(
            "field" to field,
            "present" to present,
            "sensitive" to true,
            "summary" to if (present) summarize(field, value) else null,
            "valueType" to if (present) typeName(value) else "missing"
        )
    }

    fun typeName(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> "boolean"
        is Number -> "number"
        is String -> "string"
        is Map<*, *> -> "object"
        is List<*> -> "array"
        else -> "unknown"
    }

    /**
     * Domain-scoped HMAC. The field name and project secret are part of the
     * keyed message; a different project secret produces unrelated output even
     * for identical values.
     */
    fun summarize(field: String, value: Any?): String {
        val canonical = canonicalize(value)
        val digest = Hashing.hmacSha256Hex(summarySecret, "summary|$field|$canonical")
        return "sha256:" + digest.substring(0, 16)
    }

    companion object {
        fun canonicalize(value: Any?): String = when (value) {
            null -> "null"
            is Boolean -> "bool:" + value
            is Number -> "num:" + BigDecimalUtil.normalize(value)
            is String -> "str:" + value
            is Map<*, *> -> "obj:{" + value.entries.sortedBy { it.key.toString() }
                .joinToString(",") { "${it.key}=${canonicalize(it.value)}" } + "}"
            is List<*> -> "arr:[" + value.joinToString(",") { canonicalize(it) } + "]"
            else -> "raw:" + value.toString()
        }
    }
}

object BigDecimalUtil {
    fun normalize(n: Number): String {
        val bd = (n as? java.math.BigDecimal) ?: java.math.BigDecimal(n.toString())
        return bd.stripTrailingZeros().toPlainString()
    }
}
