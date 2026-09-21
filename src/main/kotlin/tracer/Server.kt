package tracer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class Server(
    private val service: FlagService,
    private val staticDir: File,
    host: String,
    port: Int,
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)

    fun start() {
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        println("功能开关追踪器已启动: http://${server.address.hostString}:${server.address.port}")
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path ?: "/"
            if (path.startsWith("/api/")) {
                handleApi(exchange, path)
            } else {
                serveStatic(exchange, path)
            }
        } catch (e: ApiException) {
            sendJson(exchange, e.status, buildJsonObject { put("error", e.message ?: "请求错误") })
        } catch (e: Exception) {
            sendJson(exchange, 500, buildJsonObject { put("error", "服务器内部错误: ${e.message}") })
        } finally {
            exchange.close()
        }
    }

    private fun handleApi(exchange: HttpExchange, path: String) {
        val method = exchange.requestMethod
        val segments = path.removePrefix("/api/").split("/").filter { it.isNotEmpty() }
        val query = parseQuery(exchange.requestURI.rawQuery ?: "")

        fun body(): JsonObject = parseJsonObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8), "请求体")

        val result: Any = when {
            method == "GET" && segments == listOf("flags") ->
                service.listFlags().map { it.toJson() }

            method == "POST" && segments == listOf("flags") -> {
                val b = body()
                val key = b.getStringOrNull("key") ?: throw ApiException(400, "缺少 key")
                service.createFlag(key, b.getStringListOrEmpty("sensitiveFields")).toJson()
            }

            method == "GET" && segments.size == 2 && segments[0] == "flags" ->
                service.getFlag(segments[1]).toJson()

            method == "DELETE" && segments.size == 2 && segments[0] == "flags" -> {
                service.deleteFlag(segments[1])
                buildJsonObject { put("ok", true) }
            }

            method == "PUT" && segments.size == 3 && segments[0] == "flags" && segments[2] == "sensitive" -> {
                val b = body()
                service.setSensitiveFields(segments[1], b.getStringListOrEmpty("fields")).toJson()
            }

            method == "POST" && segments.size == 3 && segments[0] == "flags" && segments[2] == "versions" ->
                service.publishVersion(segments[1], body()).toJson()

            method == "POST" && segments.size == 3 && segments[0] == "flags" && segments[2] == "evaluate" -> {
                val b = body()
                val ctx = b["context"] as? JsonObject ?: throw ApiException(400, "缺少 context 对象")
                val record = b.getBooleanOrNull("record") ?: false
                service.evaluate(segments[1], ctx, record).toJson()
            }

            method == "POST" && segments == listOf("evaluate-batch") -> {
                val b = body()
                val keys = b.getStringListOrEmpty("flagKeys")
                val ctx = b["context"] as? JsonObject ?: throw ApiException(400, "缺少 context 对象")
                service.evaluateBatch(keys, ctx)
            }

            method == "POST" && segments.size == 3 && segments[0] == "flags" && segments[2] == "compare" -> {
                val b = body()
                val va = b.getIntOrNull("versionA") ?: throw ApiException(400, "缺少 versionA")
                val vb = b.getIntOrNull("versionB") ?: throw ApiException(400, "缺少 versionB")
                val contexts = mutableListOf<JsonObject>()
                (b["contexts"] as? kotlinx.serialization.json.JsonArray)?.forEach {
                    contexts.add(it as? JsonObject ?: throw ApiException(400, "contexts 元素必须是对象"))
                }
                b.getStringListOrEmpty("contextIds").forEach { id ->
                    contexts.add(service.getContexts(listOf(id)).first().context)
                }
                service.compareVersions(segments[1], va, vb, contexts)
            }

            method == "GET" && segments == listOf("contexts") ->
                service.listContexts().map { it.toJson() }

            method == "POST" && segments == listOf("contexts") -> {
                val b = body()
                val name = b.getStringOrNull("name") ?: "未命名上下文"
                val ctx = b["context"] as? JsonObject ?: throw ApiException(400, "缺少 context 对象")
                service.saveContext(name, ctx).toJson()
            }

            method == "DELETE" && segments.size == 2 && segments[0] == "contexts" -> {
                service.deleteContext(segments[1])
                buildJsonObject { put("ok", true) }
            }

            method == "GET" && segments == listOf("records") ->
                service.listRecords(query["flagKey"]).map { it.toJson() }

            method == "GET" && segments.size == 2 && segments[0] == "records" ->
                service.getRecord(segments[1]).toJson()

            method == "GET" && segments == listOf("export") -> {
                val flagKeys = query["flagKeys"]?.takeIf { it.isNotBlank() }?.split(",")
                val contextIds = query["contextIds"]?.takeIf { it.isNotBlank() }?.split(",")
                json.encodeToString(ExportBundle.serializer(), service.exportAll(flagKeys, contextIds))
            }

            method == "POST" && segments == listOf("import") -> {
                val text = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val bundle = try {
                    json.decodeFromString(ExportBundle.serializer(), text)
                } catch (e: Exception) {
                    throw ApiException(400, "导入数据格式错误: ${e.message}")
                }
                service.importBundle(bundle)
            }

            else -> throw ApiException(404, "接口不存在: $method $path")
        }
        when (result) {
            is String -> sendJson(exchange, 200, result, alreadyJson = true)
            is JsonObject -> sendJson(exchange, 200, result)
            is List<*> -> sendJson(exchange, 200, kotlinx.serialization.json.JsonArray(result.map { it as JsonObject }))
            else -> sendJson(exchange, 200, buildJsonObject { put("ok", true) })
        }
    }

    private fun serveStatic(exchange: HttpExchange, path: String) {
        val rel = if (path == "/" || path.isBlank()) "index.html" else path.removePrefix("/")
        if (rel.contains("..")) {
            sendText(exchange, 403, "Forbidden", "text/plain")
            return
        }
        val file = File(staticDir, rel)
        if (!file.isFile) {
            sendText(exchange, 404, "Not Found", "text/plain")
            return
        }
        val contentType = when (file.extension) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            "json" -> "application/json; charset=utf-8"
            else -> "application/octet-stream"
        }
        val bytes = file.readBytes()
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sendJson(exchange: HttpExchange, status: Int, obj: kotlinx.serialization.json.JsonElement) {
        sendJson(exchange, status, obj.toString(), alreadyJson = true)
    }

    private fun sendJson(exchange: HttpExchange, status: Int, payload: String, alreadyJson: Boolean) {
        sendText(exchange, status, payload, "application/json; charset=utf-8")
    }

    private fun sendText(exchange: HttpExchange, status: Int, payload: String, contentType: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split("&").filter { it.contains("=") }.associate {
            val k = it.substringBefore("=")
            val v = java.net.URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8)
            k to v
        }
}

fun EvalRecord.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("flagKey", flagKey)
    put("version", version)
    put("context", context)
    put("result", result)
    put("trace", trace)
    put("createdAt", createdAt)
}

fun SavedContext.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("context", context)
    put("createdAt", createdAt)
}
