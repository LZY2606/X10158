package ft

/**
 * Minimal JSON model. Missing object keys are NOT represented here at all;
 * an explicit JSON null is [JNull]. Evaluator code treats map-key absence and
 * JNull as different cases.
 */
sealed class JsonValue {
    abstract fun withCanonicalNumbers(): JsonValue
}

data class JObj(val entries: List<Pair<String, JsonValue>>) : JsonValue() {
    val map: Map<String, JsonValue> get() = entries.toMap()
    operator fun get(key: String): JsonValue? = map[key]
    override fun withCanonicalNumbers(): JsonValue =
        JObj(entries.map { it.first to it.second.withCanonicalNumbers() })
}

data class JArr(val items: List<JsonValue>) : JsonValue() {
    override fun withCanonicalNumbers(): JsonValue =
        JArr(items.map { it.withCanonicalNumbers() })
}

data class JStr(val value: String) : JsonValue() {
    override fun withCanonicalNumbers(): JsonValue = this
}

data class JNum(val raw: String) : JsonValue() {
    val num: Double = raw.toDouble()
    val isIntegral: Boolean get() = !raw.contains('.') && !raw.contains('e', true)
    fun display(): String = if (isIntegral) num.toLong().toString() else raw
    override fun withCanonicalNumbers(): JsonValue = this
}

data class JBool(val value: Boolean) : JsonValue() {
    override fun withCanonicalNumbers(): JsonValue = this
}

data object JNull : JsonValue() {
    override fun withCanonicalNumbers(): JsonValue = this
}

object Json {
    fun parse(input: String): JsonValue {
        val p = Parser(input)
        val v = p.readValue()
        p.skipWs()
        require(p.eof()) { "trailing characters at ${p.pos}" }
        return v
    }

    fun parseOrNull(input: String): JsonValue? = try {
        parse(input)
    } catch (_: Exception) {
        null
    }

    fun write(v: JsonValue, indent: Boolean = true): String {
        val sb = StringBuilder()
        writeTo(sb, v, 0, indent)
        return sb.toString()
    }

    fun obj(vararg pairs: Pair<String, JsonValue?>): JObj =
        JObj(pairs.mapNotNull { it.second?.let { v -> it.first to v } })

    fun arr(items: List<JsonValue>): JArr = JArr(items)
    fun s(v: String): JStr = JStr(v)
    fun n(v: Number): JNum = JNum(if (v is Double || v is Float) trimDouble(v.toDouble()) else v.toString())
    fun b(v: Boolean): JBool = JBool(v)
    fun trimDouble(d: Double): String =
        if (d.isFinite() && d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun writeTo(sb: StringBuilder, v: JsonValue, depth: Int, indent: Boolean) {
        when (v) {
            is JObj -> {
                if (v.entries.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                v.entries.forEachIndexed { i, (k, child) ->
                    if (i > 0) sb.append(',')
                    if (indent) sb.append('\n').append("  ".repeat(depth + 1))
                    writeString(sb, k)
                    sb.append(if (indent) ": " else ":")
                    writeTo(sb, child, depth + 1, indent)
                }
                if (indent) sb.append('\n').append("  ".repeat(depth))
                sb.append('}')
            }
            is JArr -> {
                if (v.items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                v.items.forEachIndexed { i, child ->
                    if (i > 0) sb.append(',')
                    if (indent) sb.append('\n').append("  ".repeat(depth + 1))
                    writeTo(sb, child, depth + 1, indent)
                }
                if (indent) sb.append('\n').append("  ".repeat(depth))
                sb.append(']')
            }
            is JStr -> writeString(sb, v.value)
            is JNum -> sb.append(v.raw)
            is JBool -> sb.append(v.value.toString())
            JNull -> sb.append("null")
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

    /** Structural, numeric-equality JSON equality: numbers compare as numbers, never as strings. */
    fun equal(a: JsonValue, b: JsonValue): Boolean = when {
        a is JNum && b is JNum -> a.num == b.num
        a is JBool && b is JBool -> a.value == b.value
        a is JStr && b is JStr -> a.value == b.value
        a === JNull && b === JNull -> true
        a is JArr && b is JArr -> a.items.size == b.items.size &&
            a.items.zip(b.items).all { equal(it.first, it.second) }
        a is JObj && b is JObj -> a.entries.size == b.entries.size &&
            a.entries.all { (k, v) -> b.map[k]?.let { equal(v, it) } == true }
        else -> false
    }

    private class Parser(val s: String) {
        var pos = 0

        fun eof(): Boolean = pos >= s.length
        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): JsonValue {
            skipWs()
            require(pos < s.length) { "unexpected end of input" }
            return when (s[pos]) {
                '{' -> readObj()
                '[' -> readArr()
                '"' -> JStr(readString())
                't', 'f' -> readBool()
                'n' -> readNull()
                else -> readNum()
            }
        }

        private fun readObj(): JObj {
            expect('{')
            val out = mutableListOf<Pair<String, JsonValue>>()
            skipWs()
            if (peek() == '}') { pos++; return JObj(out) }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                val v = readValue()
                out.add(key to v)
                skipWs()
                when (next()) {
                    ',' -> {}
                    '}' -> break
                    else -> error("expected , or }")
                }
            }
            return JObj(out)
        }

        private fun readArr(): JArr {
            expect('[')
            val out = mutableListOf<JsonValue>()
            skipWs()
            if (peek() == ']') { pos++; return JArr(out) }
            while (true) {
                out.add(readValue())
                skipWs()
                when (next()) {
                    ',' -> {}
                    ']' -> break
                    else -> error("expected , or ]")
                }
            }
            return JArr(out)
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < s.length) { "bad escape" }
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
                                val hex = s.substring(pos, pos + 4)
                                require(hex.length == 4) { "bad unicode escape" }
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readBool(): JBool {
            if (s.startsWith("true", pos)) { pos += 4; return JBool(true) }
            if (s.startsWith("false", pos)) { pos += 5; return JBool(false) }
            error("invalid literal")
        }

        private fun readNull(): JsonValue {
            if (s.startsWith("null", pos)) { pos += 4; return JNull }
            error("invalid literal")
        }

        private fun readNum(): JNum {
            val start = pos
            if (pos < s.length && (s[pos] == '-' || s[pos] == '+')) pos++
            var sawDigit = false
            while (pos < s.length && s[pos].isDigit()) { pos++; sawDigit = true }
            if (pos < s.length && s[pos] == '.') {
                pos++
                while (pos < s.length && s[pos].isDigit()) { pos++; sawDigit = true }
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '-' || s[pos] == '+')) pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            require(sawDigit) { "invalid number at $start" }
            val raw = s.substring(start, pos)
            return JNum(raw)
        }

        private fun peek(): Char { require(pos < s.length) { "unexpected end" }; return s[pos] }
        private fun next(): Char { require(pos < s.length) { "unexpected end" }; return s[pos++] }
        private fun expect(c: Char) {
            require(pos < s.length && s[pos] == c) { "expected '$c' at $pos" }
            pos++
        }
    }
}
