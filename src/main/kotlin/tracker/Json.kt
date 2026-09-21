package tracker

import java.math.BigDecimal

/** JSON 值模型。显式区分：字段缺失（map 中无此键）、JNull、字符串、数字。 */
sealed interface JVal

object JNull : JVal {
    override fun toString() = "null"
}

data class JBool(val v: Boolean) : JVal

/** 数字保留原始文本，比较时按 BigDecimal 数值比较，绝不与字符串互转。 */
data class JNum(val raw: String) : JVal {
    val dec: BigDecimal get() = BigDecimal(raw)
}

data class JStr(val v: String) : JVal

data class JArr(val items: List<JVal>) : JVal

data class JObj(val map: LinkedHashMap<String, JVal>) : JVal {
    operator fun get(k: String): JVal? = map[k]
    fun has(k: String) = map.containsKey(k)
}

object Json {
    fun parse(s: String): JVal {
        val p = Parser(s)
        val v = p.value()
        p.ws()
        if (!p.end()) throw JsonException("第 ${p.i} 字符后有多余内容")
        return v
    }

    fun render(v: JVal): String = StringBuilder().also { write(v, it) }.toString()

    /** 规范化渲染：对象键排序，用于摘要与稳定导出。 */
    fun renderCanonical(v: JVal): String = StringBuilder().also { writeCanonical(v, it) }.toString()

    private fun write(v: JVal, sb: StringBuilder) {
        when (v) {
            is JNull -> sb.append("null")
            is JBool -> sb.append(if (v.v) "true" else "false")
            is JNum -> sb.append(v.raw)
            is JStr -> writeString(v.v, sb)
            is JArr -> {
                sb.append('[')
                v.items.forEachIndexed { i, it -> if (i > 0) sb.append(','); write(it, sb) }
                sb.append(']')
            }
            is JObj -> {
                sb.append('{')
                var first = true
                for ((k, x) in v.map) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k, sb); sb.append(':'); write(x, sb)
                }
                sb.append('}')
            }
        }
    }

    private fun writeCanonical(v: JVal, sb: StringBuilder) {
        if (v is JObj) write(JObj(LinkedHashMap(v.map.toSortedMap())), sb) else write(v, sb)
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    /** 严格相等：类型必须相同；数字按数值比较；字符串与数字永不相等。 */
    fun strictEquals(a: JVal, b: JVal): Boolean = when {
        a is JNull && b is JNull -> true
        a is JBool && b is JBool -> a.v == b.v
        a is JNum && b is JNum -> a.dec.compareTo(b.dec) == 0
        a is JStr && b is JStr -> a.v == b.v
        a is JArr && b is JArr -> a.items.size == b.items.size &&
            a.items.zip(b.items).all { (x, y) -> strictEquals(x, y) }
        a is JObj && b is JObj -> a.map.keys == b.map.keys &&
            a.map.all { (k, x) -> strictEquals(x, b.map[k]!!) }
        else -> false
    }

    class JsonException(msg: String) : Exception(msg)

    private class Parser(val s: String) {
        var i = 0
        fun end() = i >= s.length
        fun ws() { while (!end() && s[i].isWhitespace()) i++ }
        private fun peek() = if (end()) 0.toChar() else s[i]
        private fun expect(c: Char) {
            if (peek() != c) throw JsonException("第 $i 字符处期望 '$c'")
            i++
        }

        fun value(): JVal {
            ws()
            return when (peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JStr(str())
                't' -> lit("true"); JBool(true)
                'f' -> lit("false"); JBool(false)
                'n' -> lit("null"); JNull
                '-', in '0'..'9' -> num()
                else -> throw JsonException("第 $i 字符处无法解析")
            }
        }

        private fun lit(w: String) {
            if (!s.startsWith(w, i)) throw JsonException("第 $i 字符处期望 $w")
            i += w.length
        }

        private fun obj(): JObj {
            expect('{'); ws()
            val m = LinkedHashMap<String, JVal>()
            if (peek() == '}') { i++; return JObj(m) }
            while (true) {
                ws()
                val k = str()
                ws(); expect(':')
                m[k] = value()
                ws()
                when (peek()) {
                    ',' -> { i++; }
                    '}' -> { i++; return JObj(m) }
                    else -> throw JsonException("第 $i 字符处期望 ',' 或 '}'")
                }
            }
        }

        private fun arr(): JArr {
            expect('['); ws()
            val items = mutableListOf<JVal>()
            if (peek() == ']') { i++; return JArr(items) }
            while (true) {
                items.add(value())
                ws()
                when (peek()) {
                    ',' -> { i++; }
                    ']' -> { i++; return JArr(items) }
                    else -> throw JsonException("第 $i 字符处期望 ',' 或 ']'")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (end()) throw JsonException("字符串未结束")
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (end()) throw JsonException("转义未结束")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("\\u 转义不完整")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw JsonException("非法转义 '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): JNum {
            val start = i
            if (peek() == '-') i++
            while (!end() && s[i].isDigit()) i++
            if (!end() && s[i] == '.') { i++; while (!end() && s[i].isDigit()) i++ }
            if (!end() && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (!end() && (s[i] == '+' || s[i] == '-')) i++
                while (!end() && s[i].isDigit()) i++
            }
            val raw = s.substring(start, i)
            try { BigDecimal(raw) } catch (e: Exception) { throw JsonException("非法数字 '$raw'") }
            return JNum(raw)
        }
    }
}

fun jObjOf(vararg pairs: Pair<String, JVal>) = JObj(LinkedHashMap(mapOf(*pairs)))
