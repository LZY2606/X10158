package tracer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object Json {
    val mapper: ObjectMapper = jacksonObjectMapper()

    fun toJson(value: Any): String = mapper.writeValueAsString(value)

    fun <T> fromJson(text: String, type: Class<T>): T = mapper.readValue(text, type)

    fun obj(): ObjectNode = mapper.createObjectNode()

    fun canonical(node: JsonNode): String = mapper.writeValueAsString(node)
}
