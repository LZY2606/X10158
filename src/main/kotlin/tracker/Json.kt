package tracker

sealed interface JsonValue
data class JsonObject(val entries: Map<String, JsonValue>) : JsonValue
data class JsonArray(val values: List<JsonValue>) : JsonValue, List<JsonValue> by values
data class JsonString(val value: String) : JsonValue
data class JsonNumber(val raw: String) : JsonValue {
    val number: Double get() = raw.toDouble()
    val isIntegral: Boolean get() = !raw.contains('.') && !raw.contains('e', ignoreCase = true)
}
data class JsonBoolean(val value: Boolean) : JsonValue
data object JsonNull : JsonValue
data object JsonMissing : JsonValue

fun JsonValue?.unwrap(): Any? = when (this) {
    null, JsonMissing -> null
    JsonNull -> null
    is JsonBoolean -> value
    is JsonString -> value
    is JsonNumber -> if (isIntegral) number.toLong() else number
    is JsonArray -> values.map { it.unwrap() }
    is JsonObject -> entries.mapValues { it.value.unwrap() }
}

fun parseJson(text: String): JsonValue = JsonParser(text).parse()

fun toJson(value: Any?): JsonValue = when (value) {
    null -> JsonNull
    is JsonValue -> value
    is Boolean -> JsonBoolean(value)
    is String -> JsonString(value)
    is Int -> JsonNumber(value.toString())
    is Long -> JsonNumber(value.toString())
    is Double -> JsonNumber(normalizeNumber(value))
    is Float -> JsonNumber(normalizeNumber(value.toDouble()))
    is Number -> JsonNumber(normalizeNumber(value.toDouble()))
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJson(it.value) })
    is Iterable<*> -> JsonArray(value.map { toJson(it) })
    is Array<*> -> JsonArray(value.map { toJson(it) })
    else -> JsonString(value.toString())
}

private fun normalizeNumber(value: Double): String =
    if (value.isFinite() && value % 1.0 == 0.0) value.toLong().toString() else value.toString()

fun JsonValue.toJsonText(pretty: Boolean = false): String {
    val builder = StringBuilder()
    writeJson(builder, this, if (pretty) 0 else -1)
    return builder.toString()
}

private fun writeJson(builder: StringBuilder, value: JsonValue, indent: Int) {
    when (value) {
        JsonNull -> builder.append("null")
        JsonMissing -> error("Missing JSON values cannot be encoded")
        is JsonBoolean -> builder.append(value.value.toString())
        is JsonString -> writeString(builder, value.value)
        is JsonNumber -> builder.append(value.raw)
        is JsonArray -> {
            if (value.values.isEmpty()) {
                builder.append("[]")
            } else {
                builder.append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) builder.append(',')
                    if (indent >= 0) builder.append('\n').append("  ".repeat(indent + 1))
                    writeJson(builder, item, if (indent >= 0) indent + 1 else -1)
                }
                if (indent >= 0) builder.append('\n').append("  ".repeat(indent))
                builder.append(']')
            }
        }
        is JsonObject -> {
            if (value.entries.isEmpty()) {
                builder.append("{}")
            } else {
                builder.append('{')
                value.entries.entries.forEachIndexed { index, entry ->
                    if (index > 0) builder.append(',')
                    if (indent >= 0) builder.append('\n').append("  ".repeat(indent + 1))
                    writeString(builder, entry.key)
                    builder.append(if (indent >= 0) ": " else ":")
                    writeJson(builder, entry.value, if (indent >= 0) indent + 1 else -1)
                }
                if (indent >= 0) builder.append('\n').append("  ".repeat(indent))
                builder.append('}')
            }
        }
    }
}

private fun writeString(builder: StringBuilder, value: String) {
    builder.append('"')
    value.forEach { char ->
        when (char) {
            '"' -> builder.append("\\\"")
            '\\' -> builder.append("\\\\")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            '\b' -> builder.append("\\b")
            '\u000C' -> builder.append("\\f")
            else -> if (char.code < 0x20) builder.append("\\u%04x".format(char.code)) else builder.append(char)
        }
    }
    builder.append('"')
}

private class JsonParser(private val text: String) {
    private var index = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = readValue()
        skipWhitespace()
        require(index == text.length) { "Unexpected trailing JSON at $index" }
        return value
    }

    private fun readValue(): JsonValue {
        skipWhitespace()
        return when (peek()) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonString(readString())
            't' -> readLiteral("true", JsonBoolean(true))
            'f' -> readLiteral("false", JsonBoolean(false))
            'n' -> readLiteral("null", JsonNull)
            else -> readNumber()
        }
    }

    private fun readObject(): JsonObject {
        expect('{')
        val entries = linkedMapOf<String, JsonValue>()
        skipWhitespace()
        if (consume('}')) return JsonObject(entries)
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(':')
            entries[key] = readValue()
            skipWhitespace()
            when {
                consume(',') -> Unit
                consume('}') -> return JsonObject(entries)
                else -> error("Expected ',' or '}' at $index")
            }
        }
    }

    private fun readArray(): JsonArray {
        expect('[')
        val values = mutableListOf<JsonValue>()
        skipWhitespace()
        if (consume(']')) return JsonArray(values)
        while (true) {
            values += readValue()
            skipWhitespace()
            when {
                consume(',') -> Unit
                consume(']') -> return JsonArray(values)
                else -> error("Expected ',' or ']' at $index")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            when (val char = next()) {
                '"' -> return builder.toString()
                '\\' -> when (val escaped = next()) {
                    '"' -> builder.append('"')
                    '\\' -> builder.append('\\')
                    '/' -> builder.append('/')
                    'n' -> builder.append('\n')
                    'r' -> builder.append('\r')
                    't' -> builder.append('\t')
                    'b' -> builder.append('\b')
                    'f' -> builder.append('\u000C')
                    'u' -> {
                        val hex = buildString { repeat(4) { append(next()) } }
                        builder.append(hex.toInt(16).toChar())
                    }
                    else -> error("Invalid escape: $escaped")
                }
                else -> builder.append(char)
            }
        }
    }

    private fun readNumber(): JsonNumber {
        val start = index
        if (peek() == '-') index++
        readDigits()
        if (consume('.')) readDigits()
        if (peek() == 'e' || peek() == 'E') {
            index++
            if (peek() == '+' || peek() == '-') index++
            readDigits()
        }
        val raw = text.substring(start, index)
        require(raw.toDoubleOrNull() != null) { "Invalid number: $raw" }
        return JsonNumber(raw)
    }

    private fun readDigits() {
        val start = index
        while (index < text.length && text[index].isDigit()) index++
        require(index > start) { "Expected digits at $index" }
    }

    private fun readLiteral(literal: String, value: JsonValue): JsonValue {
        require(text.startsWith(literal, index)) { "Expected $literal at $index" }
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun peek(): Char {
        require(index < text.length) { "Unexpected end of JSON" }
        return text[index]
    }

    private fun next(): Char {
        require(index < text.length) { "Unexpected end of JSON" }
        return text[index++]
    }

    private fun expect(char: Char) {
        require(next() == char) { "Expected '$char' at $index" }
    }

    private fun consume(char: Char): Boolean {
        if (index < text.length && text[index] == char) {
            index++
            return true
        }
        return false
    }
}

fun readPath(root: JsonValue, path: String): JsonValue {
    if (path.isBlank()) return root
    var current: JsonValue = root
    path.split('.').forEach { segment ->
        val before = current
        current = when (before) {
            is JsonObject -> before.entries[segment] ?: JsonMissing
            is JsonArray -> segment.toIntOrNull()?.let { before.values.getOrNull(it) ?: JsonMissing } ?: JsonMissing
            else -> JsonMissing
        }
    }
    return current
}
