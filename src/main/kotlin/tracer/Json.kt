package tracer

/** Minimal JSON value model. Numbers are kept as Double; integral values render without decimals. */
sealed interface JVal {
    data object JNull : JVal
    data class JBool(val v: Boolean) : JVal
    data class JNum(val v: Double) : JVal
    data class JStr(val v: String) : JVal
    data class JArr(val items: List<JVal>) : JVal
    data class JObj(val fields: LinkedHashMap<String, JVal>) : JVal {
        operator fun get(k: String): JVal? = fields[k]
    }
}

object Json {
    fun parse(text: String): JVal {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        if (!p.end()) throw JsonException("trailing content at ${p.pos}")
        return v
    }

    fun render(v: JVal, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(v, sb, pretty, 0)
        return sb.toString()
    }

    private fun write(v: JVal, sb: StringBuilder, pretty: Boolean, indent: Int) {
        when (v) {
            is JVal.JNull -> sb.append("null")
            is JVal.JBool -> sb.append(if (v.v) "true" else "false")
            is JVal.JNum -> {
                val d = v.v
                if (d.isFinite() && d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.toLong())
                else sb.append(d.toString())
            }
            is JVal.JStr -> quote(v.v, sb)
            is JVal.JArr -> {
                if (v.items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                v.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    if (pretty) { sb.append('\n'); sb.append("  ".repeat(indent + 1)) }
                    write(item, sb, pretty, indent + 1)
                }
                if (pretty) { sb.append('\n'); sb.append("  ".repeat(indent)) }
                sb.append(']')
            }
            is JVal.JObj -> {
                if (v.fields.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, fv) in v.fields) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) { sb.append('\n'); sb.append("  ".repeat(indent + 1)) }
                    quote(k, sb)
                    sb.append(':')
                    if (pretty) sb.append(' ')
                    write(fv, sb, pretty, indent + 1)
                }
                if (pretty) { sb.append('\n'); sb.append("  ".repeat(indent)) }
                sb.append('}')
            }
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    class JsonException(msg: String) : RuntimeException(msg)

    private class Parser(val s: String) {
        var pos = 0
        fun end() = pos >= s.length
        fun ws() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun value(): JVal {
            ws()
            if (end()) throw JsonException("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JVal.JStr(str())
                't' -> { expect("true"); JVal.JBool(true) }
                'f' -> { expect("false"); JVal.JBool(false) }
                'n' -> { expect("null"); JVal.JNull }
                '-', in '0'..'9' -> num()
                else -> throw JsonException("unexpected '$c' at $pos")
            }
        }

        fun expect(w: String) {
            if (!s.startsWith(w, pos)) throw JsonException("expected '$w' at $pos")
            pos += w.length
        }

        fun obj(): JVal.JObj {
            pos++ // {
            val m = LinkedHashMap<String, JVal>()
            ws()
            if (pos < s.length && s[pos] == '}') { pos++; return JVal.JObj(m) }
            while (true) {
                ws()
                val k = str()
                ws()
                if (pos >= s.length || s[pos] != ':') throw JsonException("expected ':' at $pos")
                pos++
                m[k] = value()
                ws()
                if (pos >= s.length) throw JsonException("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return JVal.JObj(m) }
                    else -> throw JsonException("expected ',' or '}' at $pos")
                }
            }
        }

        fun arr(): JVal.JArr {
            pos++ // [
            val items = mutableListOf<JVal>()
            ws()
            if (pos < s.length && s[pos] == ']') { pos++; return JVal.JArr(items) }
            while (true) {
                items.add(value())
                ws()
                if (pos >= s.length) throw JsonException("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return JVal.JArr(items) }
                    else -> throw JsonException("expected ',' or ']' at $pos")
                }
            }
        }

        fun str(): String {
            if (pos >= s.length || s[pos] != '"') throw JsonException("expected string at $pos")
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonException("unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw JsonException("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("bad \\u escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): JVal.JNum {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return JVal.JNum(t.toDoubleOrNull() ?: throw JsonException("bad number '$t'"))
        }
    }
}

fun JVal?.strOrNull(): String? = (this as? JVal.JStr)?.v
fun JVal?.numOrNull(): Double? = (this as? JVal.JNum)?.v
fun JVal?.objOrNull(): JVal.JObj? = this as? JVal.JObj
fun JVal?.arrOrNull(): List<JVal>? = (this as? JVal.JArr)?.items

fun objOf(vararg pairs: Pair<String, JVal>): JVal.JObj =
    JVal.JObj(LinkedHashMap<String, JVal>().apply { pairs.forEach { (k, v) -> put(k, v) } })
