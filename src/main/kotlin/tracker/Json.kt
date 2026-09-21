package tracker

/**
 * Minimal JSON value model. A missing context field is NOT represented here:
 * lookups return JsonReadResult.Missing separately, so null and missing never alias.
 */
sealed class JsonValue {
    abstract fun toJson(): String
}

data object JsonNull : JsonValue() {
    override fun toJson() = "null"
}

data class JsonBool(val value: Boolean) : JsonValue() {
    override fun toJson() = if (value) "true" else "false"
}

data class JsonNum(val raw: String) : JsonValue() {
    init {
        require(NUM_REGEX.matches(raw)) { "not a JSON number: $raw" }
    }

    val isInteger: Boolean get() = !raw.contains('.') && !raw.contains('e') && !raw.contains('E')
    fun toDouble(): Double = raw.toDouble()
    fun toLong(): Long = raw.toLong()

    override fun toJson() = raw

    companion object {
        fun of(value: Long): JsonNum = JsonNum(value.toString())
        fun of(value: Double): JsonNum {
            val s = if (value.isFinite()) value.toString() else error("non-finite number")
            return JsonNum(s)
        }
        private val NUM_REGEX = Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")
    }
}

data class JsonStr(val value: String) : JsonValue() {
    override fun toJson() = JsonWriter.writeString(value)
}

data class JsonArr(val items: List<JsonValue>) : JsonValue() {
    override fun toJson() = items.joinToString(",", "[", "]") { it.toJson() }
}

data class JsonObj(val fields: Map<String, JsonValue>) : JsonValue() {
    constructor(vararg pairs: Pair<String, JsonValue>) : this(pairs.toMap())

    override fun toJson(): String =
        fields.entries.joinToString(",", "{", "}") { (k, v) -> JsonWriter.writeString(k) + ":" + v.toJson() }
}

object JsonWriter {
    fun writeString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    fun pretty(v: JsonValue, indent: Int = 2): String = PrettyBuilder(indent).build(v)

    private class PrettyBuilder(private val indent: Int) {
        fun build(v: JsonValue): String {
            val sb = StringBuilder()
            write(v, 0, sb)
            return sb.toString()
        }

        private fun write(v: JsonValue, depth: Int, sb: StringBuilder) {
            when (v) {
                is JsonObj -> {
                    if (v.fields.isEmpty()) {
                        sb.append("{}")
                        return
                    }
                    sb.append("{\n")
                    var first = true
                    for ((k, field) in v.fields) {
                        if (!first) sb.append(",\n")
                        first = false
                        sb.append(" ".repeat(indent * (depth + 1)))
                        sb.append(writeString(k)).append(": ")
                        write(field, depth + 1, sb)
                    }
                    sb.append("\n").append(" ".repeat(indent * depth)).append("}")
                }
                is JsonArr -> {
                    if (v.items.isEmpty()) {
                        sb.append("[]")
                        return
                    }
                    sb.append("[\n")
                    v.items.forEachIndexed { i, item ->
                        if (i > 0) sb.append(",\n")
                        sb.append(" ".repeat(indent * (depth + 1)))
                        write(item, depth + 1, sb)
                    }
                    sb.append("\n").append(" ".repeat(indent * depth)).append("]")
                }
                else -> sb.append(v.toJson())
            }
        }
    }
}

class JsonParseException(message: String, val position: Int) : RuntimeException("$message at position $position")

object JsonParser {
    fun parse(input: String): JsonValue {
        val p = Parser(input)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        p.expectEnd()
        return v
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun expectEnd() {
            if (pos != s.length) throw JsonParseException("trailing characters", pos)
        }

        fun readValue(): JsonValue {
            skipWs()
            if (pos >= s.length) throw JsonParseException("unexpected end", pos)
            return when (s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonStr(readString())
                't' -> readLiteral("true", JsonBool(true))
                'f' -> readLiteral("false", JsonBool(false))
                'n' -> readLiteral("null", JsonNull)
                else -> if (s[pos] == '-' || s[pos].isDigit()) readNumber()
                else throw JsonParseException("unexpected character '${s[pos]}'", pos)
            }
        }

        private fun readLiteral(literal: String, value: JsonValue): JsonValue {
            if (!s.startsWith(literal, pos)) throw JsonParseException("invalid literal", pos)
            pos += literal.length
            return value
        }

        private fun readNumber(): JsonNum {
            val start = pos
            if (s[pos] == '-') pos++
            readDigits()
            if (pos < s.length && s[pos] == '.') {
                pos++
                readDigits()
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                readDigits()
            }
            return JsonNum(s.substring(start, pos))
        }

        private fun readDigits() {
            val start = pos
            while (pos < s.length && s[pos].isDigit()) pos++
            if (pos == start) throw JsonParseException("expected digits", pos)
        }

        fun readString(): String {
            if (s[pos] != '"') throw JsonParseException("expected string", pos)
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonParseException("unterminated string", pos)
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw JsonParseException("bad escape", pos)
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonParseException("bad unicode escape", pos)
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: throw JsonParseException("bad unicode escape", pos)
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> throw JsonParseException("invalid escape '$e'", pos - 1)
                        }
                    }
                    else -> {
                        if (c.code < 0x20) throw JsonParseException("unescaped control character", pos - 1)
                        sb.append(c)
                    }
                }
            }
        }

        private fun readObject(): JsonObj {
            pos++ // consume {
            val fields = linkedMapOf<String, JsonValue>()
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return JsonObj(fields)
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw JsonParseException("expected field name", pos)
                val key = readString()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw JsonParseException("expected ':'", pos)
                pos++
                val value = readValue()
                fields[key] = value
                skipWs()
                if (pos >= s.length) throw JsonParseException("unterminated object", pos)
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return JsonObj(fields)
                    else -> throw JsonParseException("expected ',' or '}'", pos - 1)
                }
            }
        }

        private fun readArray(): JsonArr {
            pos++ // consume [
            val items = mutableListOf<JsonValue>()
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return JsonArr(items)
            }
            while (true) {
                items.add(readValue())
                skipWs()
                if (pos >= s.length) throw JsonParseException("unterminated array", pos)
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return JsonArr(items)
                    else -> throw JsonParseException("expected ',' or ']'", pos - 1)
                }
            }
        }
    }
}

/** Result of reading a (possibly dotted) field path from a context object. */
sealed class FieldRead {
    data class Present(val value: JsonValue) : FieldRead()
    data object Null : FieldRead()
    data object Missing : FieldRead()
}

object ContextLookup {
    /**
     * Resolve a dotted path such as "user.tier" against a context.
     * Missing keys, or traversal through a non-object, yield Missing.
     */
    fun read(context: JsonValue, path: String): FieldRead {
        val parts = path.split('.').filter { it.isNotEmpty() }
        var current: JsonValue = context
        for (part in parts) {
            val obj = current as? JsonObj ?: return FieldRead.Missing
            val next = obj.fields[part] ?: return FieldRead.Missing
            current = next
        }
        return when (current) {
            is JsonNull -> FieldRead.Null
            else -> FieldRead.Present(current)
        }
    }
}
