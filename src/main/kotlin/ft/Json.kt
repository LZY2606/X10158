package ft

/**
 * Minimal dependency-free JSON parser / serializer.
 *
 * Parsed values use: Map<String, Any?>, List<Any?>, String, Double, Boolean, null.
 */
object Json {
    class ParseException(message: String, val position: Int) : RuntimeException("$message (at $position)")

    fun parse(input: String): Any? {
        val p = Parser(input)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.eof()) throw p.fail("Trailing characters")
        return v
    }

    fun parseObject(input: String): Map<String, Any?> =
        (parse(input) as? Map<String, Any?>) ?: throw IllegalArgumentException("Expected JSON object")

    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, value, if (pretty) 0 else -1)
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: throw IllegalArgumentException("Object expected")
    fun arr(value: Any?): List<Any?> = value as? List<Any?> ?: throw IllegalArgumentException("Array expected")
    fun str(value: Any?): String = value as? String ?: throw IllegalArgumentException("String expected, got ${value?.let { it::class.simpleName }}")
    fun strOr(value: Any?, default: String): String = (value as? String) ?: default
    fun int(value: Any?): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.toInt()
        else -> throw IllegalArgumentException("Int expected")
    }
    fun bool(value: Any?, default: Boolean = false): Boolean = (value as? Boolean) ?: default

    private fun write(sb: StringBuilder, value: Any?, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value.toString())
            is Number -> {
                val d = value.toDouble()
                if (d.isNaN() || d.isInfinite()) throw IllegalArgumentException("Non-finite number")
                if (value is Double && d == d.toLong().toDouble() && d.isFinite() && d in -1e15..1e15)
                    sb.append(d.toLong().toString())
                else
                    sb.append(value.toString())
            }
            is String -> writeString(sb, value)
            is Map<*, *> -> {
                if (value.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                val entries = value.entries.toList()
                entries.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    if (indent >= 0) { sb.append('\n'); sb.append("  ".repeat(indent + 1)) }
                    writeString(sb, e.key.toString())
                    sb.append(if (indent >= 0) ": " else ":")
                    write(sb, e.value, if (indent >= 0) indent + 1 else -1)
                }
                if (indent >= 0) { sb.append('\n'); sb.append("  ".repeat(indent)) }
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = value.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                list.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    if (indent >= 0) { sb.append('\n'); sb.append("  ".repeat(indent + 1)) }
                    write(sb, v, if (indent >= 0) indent + 1 else -1)
                }
                if (indent >= 0) { sb.append('\n'); sb.append("  ".repeat(indent)) }
                sb.append(']')
            }
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
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
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun eof() = i >= s.length
        fun fail(msg: String): ParseException = ParseException(msg, i)
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun readValue(): Any? {
            skipWs()
            if (i >= s.length) throw fail("Unexpected end")
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBool()
                'n' -> readNull()
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw fail("Expected key string")
                val key = readString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw fail("Expected ':'")
                i++
                m[key] = readValue()
                skipWs()
                if (i >= s.length) throw fail("Unterminated object")
                when (s[i]) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return m }
                    else -> throw fail("Expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list.add(readValue())
                skipWs()
                if (i >= s.length) throw fail("Unterminated array")
                when (s[i]) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return list }
                    else -> throw fail("Expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw fail("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw fail("Bad unicode escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw fail("Bad unicode escape")
                                i += 4
                                sb.append(code.toChar())
                            }
                            else -> throw fail("Bad escape '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw fail("Unterminated string")
        }

        private fun readBool(): Boolean {
            if (s.startsWith("true", i)) { i += 4; return true }
            if (s.startsWith("false", i)) { i += 5; return false }
            throw fail("Invalid literal")
        }

        private fun readNull(): Any? {
            if (s.startsWith("null", i)) { i += 4; return null }
            throw fail("Invalid literal")
        }

        private fun readNumber(): Any? {
            val start = i
            if (i < s.length && s[i] == '-') i++
            var isDouble = false
            while (i < s.length) {
                val c = s[i]
                when {
                    c in '0'..'9' -> i++
                    c == '.' || c == 'e' || c == 'E' -> { isDouble = true; i++ }
                    c == '+' || c == '-' -> i++
                    else -> {
                        val text = s.substring(start, i)
                        if (text.isEmpty() || text == "-") throw fail("Bad number")
                        return if (isDouble) text.toDoubleOrNull() ?: throw fail("Bad number")
                        else text.toLongOrNull()?.toDouble() ?: throw fail("Bad number")
                    }
                }
            }
            val text = s.substring(start, i)
            return text.toDoubleOrNull() ?: throw fail("Bad number")
        }
    }
}
