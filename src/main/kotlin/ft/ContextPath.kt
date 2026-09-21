package ft

/** Outcome of reading a dotted context path. */
sealed interface FieldRead {
    val path: String

    /** Path was absent from the context (distinct from JSON null). */
    data class Missing(override val path: String) : FieldRead
    /** Path existed and carried an explicit JSON null. */
    data class NullValue(override val path: String) : FieldRead
    data class Found(override val path: String, val value: JsonValue) : FieldRead
}

object ContextPaths {
    /** Splits "a.b.0.c" into segments; backslash escapes dots in keys. */
    fun split(path: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < path.length) {
            val c = path[i]
            when {
                c == '\\' && i + 1 < path.length -> {
                    current.append(path[i + 1])
                    i += 2
                }
                c == '.' -> {
                    segments.add(current.toString())
                    current.setLength(0)
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        segments.add(current.toString())
        return segments
    }

    /** Reads [path] from [context], distinguishing missing values from null. */
    fun read(context: JsonObject, path: String): FieldRead {
        val segments = split(path)
        var node: JsonValue = context
        for ((index, segment) in segments.withIndex()) {
            val atLast = index == segments.lastIndex
            when (node) {
                is JsonObject -> {
                    if (!node.members.containsKey(segment)) {
                        return FieldRead.Missing(path)
                    }
                    val next = node.members.getValue(segment)
                    if (atLast) return found(path, next)
                    node = next
                }
                is JsonArray -> {
                    val arrayIndex = segment.toIntOrNull()
                        ?: return FieldRead.Missing(path)
                    if (arrayIndex !in node.items.indices) {
                        return FieldRead.Missing(path)
                    }
                    val next = node.items[arrayIndex]
                    if (atLast) return found(path, next)
                    node = next
                }
                else -> return FieldRead.Missing(path)
            }
        }
        return FieldRead.Missing(path)
    }

    private fun found(path: String, value: JsonValue): FieldRead =
        if (value is JsonNull) FieldRead.NullValue(path)
        else FieldRead.Found(path, value)

    /**
     * Sensitive-field matching: declared paths may end in "*" as a wildcard
     * over a single object level, e.g. "user.pii.*" matches "user.pii.email".
     */
    fun isSensitive(path: String, declared: List<String>): Boolean {
        val target = split(path)
        return declared.any { pattern ->
            val parts = split(pattern)
            when {
                parts == target -> true
                parts.lastOrNull() == "*" ->
                    target.size == parts.size &&
                        target.dropLast(1) == parts.dropLast(1)
                else -> false
            }
        }
    }
}
