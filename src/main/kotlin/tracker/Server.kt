package tracker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class TrackerHttpServer(
    private val store: TrackerStore,
    host: String,
    port: Int
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            try {
                route(exchange)
            } catch (error: CycleException) {
                sendJson(exchange, 409, JsonObject(mapOf(
                    "error" to JsonString(error.message ?: "依赖环"),
                    "cyclePath" to JsonArray(error.path.map(::JsonString))
                )))
            } catch (error: ValidationException) {
                sendJson(exchange, 400, JsonObject(mapOf("error" to JsonString(error.message ?: "请求无效"))))
            } catch (error: IllegalArgumentException) {
                sendJson(exchange, 400, JsonObject(mapOf("error" to JsonString(error.message ?: "请求无效"))))
            } catch (error: NoSuchElementException) {
                sendJson(exchange, 404, JsonObject(mapOf("error" to JsonString(error.message ?: "资源不存在"))))
            } catch (error: Exception) {
                error.printStackTrace()
                sendJson(exchange, 500, JsonObject(mapOf("error" to JsonString(error.message ?: "服务器错误"))))
            } finally {
                exchange.close()
            }
        }
    }

    fun start() {
        server.start()
    }

    private fun route(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        if (!path.startsWith("/api/")) {
            serveIndex(exchange)
            return
        }
        val segments = path.split('/').filter { it.isNotBlank() }
        val bodyText = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val body = if (bodyText.isBlank()) JsonObject(emptyMap()) else parseJson(bodyText) as JsonObject
        val response: JsonValue = when {
            exchange.requestMethod == "GET" && segments == listOf("api", "projects") ->
                JsonObject(mapOf("projects" to JsonArray(store.listProjects().map(::projectToJson))))
            exchange.requestMethod == "POST" && segments == listOf("api", "projects") ->
                projectToJson(store.createProject(body.string("name")))
            exchange.requestMethod == "POST" && segments == listOf("api", "import") ->
                projectToJson(store.importProject(body))
            segments.size >= 3 && segments[0] == "api" && segments[1] == "projects" ->
                projectRoute(exchange.requestMethod, segments, body)
            else -> throw NoSuchElementException("接口不存在")
        }
        sendJson(exchange, 200, response)
    }

    private fun projectRoute(method: String, segments: List<String>, body: JsonObject): JsonValue {
        val projectId = segments[2]
        return when {
            method == "GET" && segments.size == 3 -> projectToJson(store.projectById(projectId))
            method == "POST" && segments.size == 4 && segments[3] == "flags" ->
                versionToJson(store.publishFlag(projectId, body))
            method == "POST" && segments.size == 4 && segments[3] == "contexts" ->
                contextToJson(store.saveContext(projectId, body.string("name"), body.obj("context")))
            method == "DELETE" && segments.size == 5 && segments[3] == "contexts" -> {
                store.deleteContext(projectId, segments[4])
                JsonObject(mapOf("deleted" to JsonBoolean(true)))
            }
            method == "POST" && segments.size == 4 && segments[3] == "evaluate" -> {
                val record = store.evaluate(
                    projectId = projectId,
                    flagKey = body.string("flagKey"),
                    context = body.obj("context"),
                    contextName = body.stringOrNull("contextName") ?: "临时上下文",
                    saveRecord = (body.valueOrNull("saveRecord") as? JsonBoolean)?.value ?: true
                )
                recordToJson(record)
            }
            method == "POST" && segments.size == 4 && segments[3] == "evaluate-batch" -> {
                val keys = (body.valueOrNull("flagKeys") as? JsonArray)?.values
                    ?.mapNotNull { (it as? JsonString)?.value }
                store.evaluateBatch(projectId, body.obj("context"), keys)
            }
            method == "POST" && segments.size == 4 && segments[3] == "compare" -> {
                val contextIds = (body.valueOrNull("contextIds") as? JsonArray)?.values
                    ?.mapNotNull { (it as? JsonString)?.value } ?: emptyList()
                store.compareVersions(
                    projectId = projectId,
                    flagKey = body.string("flagKey"),
                    versionAId = body.string("versionAId"),
                    versionBId = body.string("versionBId"),
                    contextIds = contextIds
                )
            }
            method == "GET" && segments.size == 4 && segments[3] == "export" -> store.exportProject(projectId)
            else -> throw NoSuchElementException("接口不存在")
        }
    }

    private fun serveIndex(exchange: HttpExchange) {
        val resource = javaClass.getResourceAsStream("/web/index.html")
            ?: throw NoSuchElementException("网页资源不存在")
        val bytes = resource.readBytes()
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sendJson(exchange: HttpExchange, status: Int, value: JsonValue) {
        val bytes = value.toJsonText(pretty = true).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
