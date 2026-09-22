package fst

import java.io.StringReader

/* Minimal JSON parser / writer used everywhere. Values: Map<String,Any?>, List<Any?>,
   String, Double, Boolean, null. Object key order is preserved via LinkedHashMap. */
object Json {
    fun parse(text: String): Any? = Parser(StringReader(text)).parseValue().also { it /* ensure EOF below */ }.let {
        // handled in parser with EOF check
        it
    }

    fun parseObject(text: String): Map<String, Any?> {
        val p = Parser(StringReader(text))
        val v = p.parseValue()
        p.skipWs(); p.expectEof()
        @Suppress("UNCHECKED_CAST")
        return v as Map<String, Any?>
    }

    fun write(value: Any?, indent: Boolean = false): String = buildString { writeValue(value, if (indent) 0 else -1) }

    private fun StringBuilder.writeValue(v: Any?, depth: Int) {
        when (v) {
            null -> append("null")
            is Boolean -> append(v.toString())
            is Number -> {
                val d = v.toDouble()
                if (v is Double && (d.isNaN() || d.isInfinite())) throw IllegalArgumentException("bad number")
                if (d == d.toLong().toDouble() && d.isFinite() && v is Double) append(v.toLong().toString()) else append(v.toString())
            }
            is String -> writeJsonString(v)
            is Map<*, *> -> {
                if (v.isEmpty()) { append("{}"); return }
                append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) append(',')
                    first = false
                    if (depth >= 0) { append('\n'); append("  ".repeat(depth + 1)) }
                    writeJsonString(k.toString()); append(':')
                    if (depth >= 0) append(' ')
                    writeValue(value, depth + 1)
                }
                if (depth >= 0) { append('\n'); append("  ".repeat(depth)) }
                append('}')
            }
            is Iterable<*> -> {
                val list = v.toList()
                if (list.isEmpty()) { append("[]"); return }
                append('[')
                list.forEachIndexed { i, value ->
                    if (i > 0) append(',')
                    if (depth >= 0) { append('\n'); append("  ".repeat(depth + 1)) }
                    writeValue(value, depth + 1)
                }
                if (depth >= 0) { append('\n'); append("  ".repeat(depth)) }
                append(']')
            }
            else -> throw IllegalArgumentException("Cannot serialize ${v::class}")
        }
    }

    private fun StringBuilder.writeJsonString(s: String) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
            '\r' -> append("\\r"); '\t' -> append("\\t"); '\b' -> append("\\b"); 12.toChar() -> append("\\f")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(map: Map<String, Any?>) = LinkedHashMap(map)
    fun obj(vararg pairs: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*pairs)
    fun arr(vararg items: Any?): List<Any?> = listOf(*items)

    private class Parser(private val r: StringReader) {
        private fun read(): Int = r.read()
        private fun peek(): Int { r.mark(1); val c = r.read(); r.reset(); return c }
        fun skipWs() { while (true) { val c = peek(); if (c == -1 || Character.isWhitespace(c).not()) break; read() } }
        fun expectEof() { val c = peek(); if (c != -1) throw IllegalArgumentException("Unexpected trailing data: ${c.toChar()}") }

        fun parseValue(): Any? {
            skipWs()
            return when (val c = read()) {
                '{'.code -> parseObject()
                '['.code -> parseArray()
                '"'.code -> parseStringRaw()
                't'.code -> { expectLit("true"); true }
                'f'.code -> { expectLit("false"); false }
                'n'.code -> { expectLit("null"); null }
                -1 -> throw IllegalArgumentException("Unexpected EOF")
                else -> {
                    if (c == '-'.code || c in '0'.code..'9'.code) parseNumber(c)
                    else throw IllegalArgumentException("Unexpected char ${c.toChar()}")
                }
            }
        }

        private fun expectLit(lit: String) {
            val rest = lit.substring(1)
            val buf = CharArray(rest.length)
            val n = r.read(buf)
            if (n != rest.length || String(buf) != rest) throw IllegalArgumentException("Expected $lit")
        }

        private fun parseNumber(first: Int): Double {
            val sb = StringBuilder(first.toChar().toString())
            while (true) {
                val c = peek()
                if (c == -1) break
                val ch = c.toChar()
                if (ch in "0123456789.eE+-") { sb.append(ch); read() } else break
            }
            return sb.toString().toDouble()
        }

        private fun parseStringRaw(): String {
            val sb = StringBuilder()
            while (true) {
                val c = read()
                when {
                    c == -1 -> throw IllegalArgumentException("Unterminated string")
                    c == '"'.code -> return sb.toString()
                    c == '\\'.code -> {
                        when (val e = read()) {
                            '"'.code -> sb.append('"'); '\\'.code -> sb.append('\\'); '/'.code -> sb.append('/')
                            'n'.code -> sb.append('\n'); 't'.code -> sb.append('\t'); 'r'.code -> sb.append('\r')
                            'b'.code -> sb.append('\b'); 'f'.code -> sb.append(12.toChar())
                            'u'.code -> {
                                val hex = (1..4).map { val h = read(); if (h == -1) throw IllegalArgumentException("bad unicode"); h.toChar() }.joinToString("")
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("Bad escape $e")
                        }
                    }
                    else -> sb.append(c.toChar())
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}'.code) { read(); return m }
            while (true) {
                skipWs()
                if (read() != '"'.code) throw IllegalArgumentException("Expected string key")
                val key = parseStringRaw()
                skipWs()
                if (read() != ':'.code) throw IllegalArgumentException("Expected ':'")
                m[key] = parseValue()
                skipWs()
                when (read()) {
                    ','.code -> {}
                    '}'.code -> return m
                    else -> throw IllegalArgumentException("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']'.code) { read(); return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (read()) {
                    ','.code -> {}
                    ']'.code -> return list
                    else -> throw IllegalArgumentException("Expected ',' or ']'")
                }
            }
        }
    }
}

/* typed accessors for maps built from JSON */
fun Any?.asMap(): Map<String, Any?> = (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: throw IllegalArgumentException("expected object, got $this")
fun Any?.asList(): List<Any?> = this as? List<Any?> ?: throw IllegalArgumentException("expected array, got $this")
fun Any?.asString(): String = this as? String ?: throw IllegalArgumentException("expected string, got $this")
fun Any?.asNum(): Double = when (this) { is Number -> toDouble(); else -> throw IllegalArgumentException("expected number, got $this") }
fun Any?.asBool(): Boolean = this as? Boolean ?: throw IllegalArgumentException("expected boolean, got $this")
fun Map<String, Any?>.str(key: String): String = this[key].asString()
fun Map<String, Any?>.num(key: String): Double = this[key].asNum()
fun Map<String, Any?>.bool(key: String, default: Boolean = false): Boolean = (this[key] as? Boolean) ?: default
