package tracker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class ApiServer(
    private val store: Store,
    host: String,
    port: Int,
) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)

    val actualPort: Int get() = server.address.port

    init {
        server.createContext("/") { ex -> handle(ex) }
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
    }

    fun start() = server.start()
    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        try {
            route(ex)
        } catch (e: CycleException) {
            respond(ex, 400, err("cycle", e.message ?: "依赖环"))
        } catch (e: NoSuchElementException) {
            respond(ex, 404, err("not_found", e.message ?: "不存在"))
        } catch (e: IllegalArgumentException) {
            respond(ex, 400, err("bad_request", e.message ?: "请求无效"))
        } catch (e: Exception) {
            respond(ex, 500, err("internal", e.message ?: e.toString()))
        } finally {
            ex.close()
        }
    }

    private fun err(code: String, message: String): JsonObject = JsonObject(
        mapOf("error" to JsonObject(mapOf("code" to JsonPrimitive(code), "message" to JsonPrimitive(message))))
    )

    private fun route(ex: HttpExchange) {
        val method = ex.requestMethod
        val path = ex.requestURI.path.trimEnd('/').ifEmpty { "/" }
        val seg = path.split("/").filter { it.isNotEmpty() }

        if (seg.isEmpty() || seg[0] != "api") {
            serveStatic(ex, path)
            return
        }

        val bodyText = if (method == "POST" || method == "PUT") String(ex.requestBody.readBytes(), StandardCharsets.UTF_8) else ""
        val body: JsonObject = if (bodyText.isBlank()) JsonObject(emptyMap()) else {
            val el = Json.parseToJsonElement(bodyText)
            if (el is JsonObject) el else throw IllegalArgumentException("请求体必须是 JSON 对象")
        }

        val result: JsonElement = when {
            method == "GET" && seg == listOf("api", "state") -> stateJson()
            method == "PUT" && seg == listOf("api", "project") -> {
                val fields = body["sensitiveFields"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
                    ?: throw IllegalArgumentException("缺少 sensitiveFields")
                json.encodeToJsonElement(ProjectSettings.serializer(), store.updateSensitiveFields(fields))
            }
            method == "POST" && seg == listOf("api", "flags") -> {
                val key = body["key"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺少 key")
                val defaultValue = body["defaultValue"] ?: JsonNull
                val salt = body["salt"]?.jsonPrimitive?.content
                json.encodeToJsonElement(Flag.serializer(), store.createFlag(key, defaultValue, salt))
            }
            method == "PUT" && seg.size == 3 && seg[0] == "api" && seg[1] == "flags" && seg[2].isNotEmpty() && body.containsKey("draft") -> {
                val doc = json.decodeFromJsonElement(FlagDoc.serializer(), body.getValue("draft"))
                json.encodeToJsonElement(Flag.serializer(), store.updateDraft(seg[2], doc))
            }
            method == "POST" && seg.size == 4 && seg[0] == "api" && seg[1] == "flags" && seg[3] == "publish" -> {
                json.encodeToJsonElement(FlagVersion.serializer(), store.publish(seg[2]))
            }
            method == "POST" && seg == listOf("api", "evaluate") -> evaluateOnce(body, save = body["save"]?.jsonPrimitive?.boolean ?: false)
            method == "POST" && seg == listOf("api", "evaluate", "batch") -> evaluateBatch(body)
            method == "POST" && seg == listOf("api", "contexts") -> {
                val name = body["name"]?.jsonPrimitive?.content ?: "未命名"
                val attrs = body["attributes"] as? JsonObject ?: throw IllegalArgumentException("attributes 必须是对象")
                json.encodeToJsonElement(SavedContext.serializer(), store.saveContext(name, attrs))
            }
            method == "DELETE" && seg.size == 3 && seg[0] == "api" && seg[1] == "contexts" -> {
                JsonObject(mapOf("deleted" to JsonPrimitive(store.deleteContext(seg[2]))))
            }
            method == "GET" && seg.size == 3 && seg[0] == "api" && seg[1] == "evaluations" -> {
                val rec = store.getEvaluation(seg[2]) ?: throw NoSuchElementException("求值记录不存在")
                json.encodeToJsonElement(EvaluationRecord.serializer(), rec)
            }
            method == "POST" && seg.size == 4 && seg[0] == "api" && seg[1] == "evaluations" && seg[3] == "replay" -> {
                val trace = store.replay(seg[2]) ?: throw NoSuchElementException("无法重放：记录或版本不存在")
                json.encodeToJsonElement(TraceNode.serializer(), trace)
            }
            method == "GET" && seg == listOf("api", "compare") -> compare(ex.requestURI.query)
            method == "GET" && seg == listOf("api", "export") ->
                json.encodeToJsonElement(ExportBundle.serializer(), store.export())
            method == "POST" && seg == listOf("api", "import") -> {
                val bundle = json.decodeFromJsonElement(ExportBundle.serializer(), body)
                store.import(bundle)
                JsonObject(mapOf("imported" to JsonPrimitive(true)))
            }
            else -> throw NoSuchElementException("接口不存在: $method $path")
        }
        respond(ex, 200, result)
    }

    private fun evaluateOnce(body: JsonObject, save: Boolean): JsonObject {
        val flagKey = body["flagKey"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺少 flagKey")
        val context = body["context"] as? JsonObject ?: throw IllegalArgumentException("context 必须是对象")
        val versionId = body["versionId"]?.jsonPrimitive?.content
        val snapshot = if (versionId != null) store.snapshotOf(mapOf(flagKey to versionId)) else store.snapshot()
        val trace = store.evaluator(snapshot).evaluate(flagKey, context)
        val recordId = if (save) store.recordEvaluation(flagKey, trace.versionId, context, trace).id else null
        return JsonObject(mapOf(
            "result" to trace.result,
            "trace" to json.encodeToJsonElement(TraceNode.serializer(), trace),
            "recordId" to (recordId?.let { JsonPrimitive(it) } ?: JsonNull),
        ))
    }

    private fun evaluateBatch(body: JsonObject): JsonObject {
        val contexts = body["contexts"]?.jsonArray?.map {
            it as? JsonObject ?: throw IllegalArgumentException("contexts 元素必须是对象")
        } ?: throw IllegalArgumentException("缺少 contexts")
        val flagKeys = body["flagKeys"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: store.state().flags.keys.toList()
        val snapshot = store.snapshot()
        val evaluator = store.evaluator(snapshot)
        val results = contexts.map { ctx ->
            JsonObject(flagKeys.associate { fk ->
                val trace = evaluator.evaluate(fk, ctx)
                fk to JsonObject(mapOf(
                    "result" to trace.result,
                    "versionId" to JsonPrimitive(trace.versionId),
                    "reason" to JsonPrimitive(trace.reason),
                    "trace" to json.encodeToJsonElement(TraceNode.serializer(), trace),
                ))
            })
        }
        return JsonObject(mapOf(
            "snapshot" to JsonObject(snapshot.versions.mapValues { JsonPrimitive(it.value.versionId) }),
            "results" to kotlinx.serialization.json.JsonArray(results),
        ))
    }

    private fun compare(query: String?): JsonObject {
        val params = (query ?: "").split("&").filter { it.isNotEmpty() }.associate {
            val kv = it.split("=", limit = 2)
            java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8) to
                (if (kv.size > 1) java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) else "")
        }
        val flagKey = params["flag"] ?: throw IllegalArgumentException("缺少 flag 参数")
        val v1 = params["v1"] ?: throw IllegalArgumentException("缺少 v1 参数")
        val v2 = params["v2"] ?: throw IllegalArgumentException("缺少 v2 参数")
        val contexts = store.state().contexts
        val rows = contexts.map { ctx ->
            val t1 = store.evaluator(store.snapshotOf(mapOf(flagKey to v1))).evaluate(flagKey, ctx.attributes)
            val t2 = store.evaluator(store.snapshotOf(mapOf(flagKey to v2))).evaluate(flagKey, ctx.attributes)
            JsonObject(mapOf(
                "contextId" to JsonPrimitive(ctx.id),
                "contextName" to JsonPrimitive(ctx.name),
                "v1Result" to t1.result,
                "v2Result" to t2.result,
                "changed" to JsonPrimitive(!JsonUtil.strictEquals(t1.result, t2.result)),
                "v1Trace" to json.encodeToJsonElement(TraceNode.serializer(), t1),
                "v2Trace" to json.encodeToJsonElement(TraceNode.serializer(), t2),
            ))
        }
        return JsonObject(mapOf(
            "flag" to JsonPrimitive(flagKey),
            "v1" to JsonPrimitive(v1),
            "v2" to JsonPrimitive(v2),
            "rows" to kotlinx.serialization.json.JsonArray(rows),
        ))
    }

    private fun stateJson(): JsonObject {
        val data = store.state()
        return json.encodeToJsonElement(StoreData.serializer(), data).jsonObject
    }

    private fun serveStatic(ex: HttpExchange, path: String) {
        val resource = when (path) {
            "/", "/index.html" -> "/web/index.html"
            "/app.js" -> "/web/app.js"
            "/style.css" -> "/web/style.css"
            else -> null
        }
        val bytes = resource?.let { javaClass.getResourceAsStream(it)?.readBytes() }
        if (bytes == null) {
            respond(ex, 404, err("not_found", "页面不存在"))
            return
        }
        val contentType = when {
            resource.endsWith(".html") -> "text/html; charset=utf-8"
            resource.endsWith(".js") -> "application/javascript; charset=utf-8"
            resource.endsWith(".css") -> "text/css; charset=utf-8"
            else -> "application/octet-stream"
        }
        ex.responseHeaders.set("Content-Type", contentType)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun respond(ex: HttpExchange, status: Int, body: JsonElement) {
        val bytes = json.encodeToString(JsonElement.serializer(), body).toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
