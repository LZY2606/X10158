package ft

/**
 * Minimal JSON value model + parser + writer. Numbers retain their lexical
 * token and are exposed as Long/Double, keeping numeric semantics explicit
 * for the flag evaluator (no implicit string<->number coercion).
 */
sealed interface JsonValue
data class JsonObject(val members: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = members[key]
}
data class JsonArray(val items: List<JsonValue>) : JsonValue {
    operator fun get(i: Int): JsonValue = items[i]
}
data class JsonString(val value: String) : JsonValue
data class JsonNumber(val raw: String) : JsonValue {
    val isIntegral: Boolean get() = !raw.any { it == '.' || it == 'e' || it == 'E' }
    val long: Long get() = raw.toLong()
    val double: Double get() = raw.toDouble()
}
data class JsonBoolean(val value: Boolean) : JsonValue
data object JsonNull : JsonValue

val JsonValue?.asObject: JsonObject? get() = this as? JsonObject
val JsonValue?.asArray: JsonArray? get() = this as? JsonArray
val JsonValue?.asString: String? get() = (this as? JsonString)?.value
val JsonValue?.asNumber: JsonNumber? get() = this as? JsonNumber
val JsonValue?.asBoolean: Boolean? get() = (this as? JsonBoolean)?.value

@DslMarker
annotation class JsonDsl

@JsonDsl
class JsonObjectBuilder {
    private val members = linkedMapOf<String, JsonValue>()
    infix fun String.to(value: JsonValue?) {
        members[this] = value ?: JsonNull
    }
    fun put(key: String, value: JsonValue?) {
        members[key] = value ?: JsonNull
    }
    fun build(): JsonObject = JsonObject(members)
}

@JsonDsl
class JsonArrayBuilder {
    private val items = mutableListOf<JsonValue>()
    fun add(value: JsonValue?) {
        items.add(value ?: JsonNull)
    }
    fun build(): JsonArray = JsonArray(items)
}

fun jsonObject(block: JsonObjectBuilder.() -> Unit): JsonObject =
    JsonObjectBuilder().apply(block).build()

fun jsonArray(block: JsonArrayBuilder.() -> Unit): JsonArray =
    JsonArrayBuilder().apply(block).build()

fun jsonList(items: List<JsonValue>): JsonArray = JsonArray(items)

fun json(value: String?): JsonValue = if (value == null) JsonNull else JsonString(value)
fun json(value: Boolean): JsonValue = JsonBoolean(value)
fun json(value: Long): JsonValue = JsonNumber(value.toString())
fun json(value: Int): JsonValue = JsonNumber(value.toString())

/** JSON serialization with stable, insertion-ordered keys. */
fun JsonValue.toJson(indent: String = "  "): String = buildString {
    write(this@toJson, 0, indent, this)
}

private fun write(v: JsonValue, depth: Int, indent: String, out: StringBuilder) {
    when (v) {
        is JsonObject -> {
            if (v.members.isEmpty()) {
                out.append("{}")
                return
            }
            out.append("{\n")
            var first = true
            for ((key, value) in v.members) {
                if (!first) out.append(",\n")
                first = false
                out.append(indent.repeat(depth + 1))
                appendJsonString(out, key)
                out.append(": ")
                write(value, depth + 1, indent, out)
            }
            out.append("\n").append(indent.repeat(depth)).append("}")
        }
        is JsonArray -> {
            if (v.items.isEmpty()) {
                out.append("[]")
                return
            }
            out.append("[\n")
            var first = true
            for (item in v.items) {
                if (!first) out.append(",\n")
                first = false
                out.append(indent.repeat(depth + 1))
                write(item, depth + 1, indent, out)
            }
            out.append("\n").append(indent.repeat(depth)).append("]")
        }
        is JsonString -> appendJsonString(out, v.value)
        is JsonNumber -> out.append(v.raw)
        is JsonBoolean -> out.append(if (v.value) "true" else "false")
        JsonNull -> out.append("null")
    }
}

private fun appendJsonString(out: StringBuilder, s: String) {
    out.append('"')
    for (c in s) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            Char(0x0C) -> out.append("\\f")
            else -> if (c.code < 0x20) {
                out.append("\\u%04x".format(c.code))
            } else {
                out.append(c)
            }
        }
    }
    out.append('"')
}

class JsonParseException(message: String, val position: Int) :
    RuntimeException("$message at $position")

fun parseJson(text: String): JsonValue = JsonParser(text).parseValue()

private class JsonParser(private val text: String) {
    private var pos = 0

    fun parseValue(): JsonValue {
        skipWs()
        if (pos >= text.length) fail("unexpected end")
        return when (text[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonString(parseString())
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> parseNumber()
        }
    }

    private fun parseObject(): JsonObject {
        expect('{')
        val members = linkedMapOf<String, JsonValue>()
        skipWs()
        if (peek() == '}') {
            pos++
            return JsonObject(members)
        }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            expect(':')
            val value = parseValue()
            members[key] = value
            skipWs()
            when (peek()) {
                ',' -> {
                    pos++
                    continue
                }
                '}' -> {
                    pos++
                    return JsonObject(members)
                }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): JsonArray {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWs()
        if (peek() == ']') {
            pos++
            return JsonArray(items)
        }
        while (true) {
            items.add(parseValue())
            skipWs()
            when (peek()) {
                ',' -> {
                    pos++
                    continue
                }
                ']' -> {
                    pos++
                    return JsonArray(items)
                }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (pos < text.length) {
            val c = text[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= text.length) fail("bad escape")
                    when (val e = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append(Char(0x0C))
                        'u' -> {
                            if (pos + 4 > text.length) fail("bad unicode escape")
                            val hex = text.substring(pos, pos + 4)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> fail("bad escape '$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
        fail("unterminated string")
        error("unreachable")
    }

    private fun parseBoolean(): JsonValue {
        if (text.startsWith("true", pos)) {
            pos += 4
            return JsonBoolean(true)
        }
        if (text.startsWith("false", pos)) {
            pos += 5
            return JsonBoolean(false)
        }
        fail("invalid literal")
        error("unreachable")
    }

    private fun parseNull(): JsonValue {
        if (text.startsWith("null", pos)) {
            pos += 4
            return JsonNull
        }
        fail("invalid literal")
        error("unreachable")
    }

    private fun parseNumber(): JsonValue {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && text[pos].isDigit()) pos++
        if (peek() == '.') {
            pos++
            while (pos < text.length && text[pos].isDigit()) pos++
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++
            if (peek() == '+' || peek() == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
        }
        val raw = text.substring(start, pos)
        raw.toDoubleOrNull() ?: fail("invalid number '$raw'")
        return JsonNumber(raw)
    }

    private fun skipWs() {
        while (pos < text.length && text[pos].let {
                it == ' ' || it == '\n' || it == '\r' || it == '\t'
            }) pos++
    }

    private fun peek(): Char = if (pos >= text.length) ' ' else text[pos]

    private fun expect(c: Char) {
        skipWs()
        if (pos >= text.length || text[pos] != c) fail("expected '$c'")
        pos++
    }

    private fun fail(msg: String): Nothing = throw JsonParseException(msg, pos)
}
