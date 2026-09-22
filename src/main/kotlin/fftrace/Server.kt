package fftrace

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress

@Serializable
data class EvalRequest(
    val flagKeys: List<String> = emptyList(),
    val contextIds: List<String> = emptyList(),
    val inlineContexts: List<SavedContext> = emptyList()
)

@Serializable
data class EvalResponseItem(
    val recordId: String,
    val flagKey: String,
    val version: Int,
    val contextName: String,
    val result: String,
    val trace: EvaluationTrace
)

@Serializable
data class CompareItem(
    val contextId: String,
    val contextName: String,
    val a: EvaluationTrace,
    val b: EvaluationTrace
)

@Serializable
data class ErrorResponse(val error: String)

class ApiServer(private val store: Store, host: String, port: Int) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)

    init {
        server.createContext("/") { ex -> ex.use { route(it) } }
        server.executor = null
    }

    fun start() = server.start()
    fun stop(delay: Int = 0) = server.stop(delay)
    val port: Int get() = server.address.port

    private fun route(ex: HttpExchange) {
        try {
            dispatch(ex)
        } catch (e: ApiError) {
            respond(ex, e.status, json.encodeToString(ErrorResponse(e.message ?: "error")))
        } catch (e: Exception) {
            respond(ex, 500, json.encodeToString(ErrorResponse(e.message ?: e.toString())))
        }
    }

    private class ApiError(val status: Int, message: String) : Exception(message)

    private fun dispatch(ex: HttpExchange) {
        val method = ex.requestMethod
        val path = ex.requestURI.path.trimEnd('/').ifEmpty { "/" }
        val segments = path.split('/').filter { it.isNotEmpty() }

        when {
            path == "/" && method == "GET" -> {
                val html = javaClass.getResourceAsStream("/static/index.html")!!.readBytes()
                respondBytes(ex, 200, html, "text/html; charset=utf-8")
            }
            path == "/api/state" && method == "GET" ->
                respondJson(ex, 200, json.encodeToString(store.state()))
            path == "/api/flags" && method == "POST" -> {
                val body = bodyJson(ex)
                val key = body.str("key")
                val (flag, err) = store.createFlag(key, body.str("description"))
                if (err != null) throw ApiError(400, err)
                respondJson(ex, 200, json.encodeToString(flag!!))
            }
            segments.size == 3 && segments[0] == "api" && segments[1] == "flags" && segments[2] == "draft" && method == "PUT" ->
                throw ApiError(400, "缺少开关 key")
            segments.size == 4 && segments[0] == "api" && segments[1] == "flags" && segments[3] == "draft" && method == "PUT" -> {
                val config = json.decodeFromString<FlagConfig>(bodyText(ex))
                val err = store.saveDraft(segments[2], config)
                if (err != null) throw ApiError(400, err)
                respondJson(ex, 200, """{"ok":true}""")
            }
            segments.size == 4 && segments[0] == "api" && segments[1] == "flags" && segments[3] == "publish" && method == "POST" -> {
                val (version, err) = store.publish(segments[2])
                if (err != null) throw ApiError(400, err)
                respondJson(ex, 200, json.encodeToString(version!!))
            }
            segments.size == 3 && segments[0] == "api" && segments[1] == "flags" && method == "DELETE" -> {
                store.deleteFlag(segments[2])
                respondJson(ex, 200, """{"ok":true}""")
            }
            path == "/api/sensitive-fields" && method == "PUT" -> {
                val body = bodyJson(ex)
                val fields = body["fields"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                store.setSensitiveFields(fields)
                respondJson(ex, 200, """{"ok":true}""")
            }
            path == "/api/contexts" && method == "POST" -> {
                val ctx = json.decodeFromString<SavedContext>(bodyText(ex))
                respondJson(ex, 200, json.encodeToString(store.saveContext(ctx)))
            }
            segments.size == 3 && segments[0] == "api" && segments[1] == "contexts" && method == "DELETE" -> {
                store.deleteContext(segments[2])
                respondJson(ex, 200, """{"ok":true}""")
            }
            path == "/api/evaluate" && method == "POST" -> handleEvaluate(ex)
            path == "/api/evaluations" && method == "GET" ->
                respondJson(ex, 200, json.encodeToString(store.state().evaluations))
            segments.size == 3 && segments[0] == "api" && segments[1] == "evaluations" && method == "GET" -> {
                val rec = store.getEvaluation(segments[2]) ?: throw ApiError(404, "记录不存在")
                respondJson(ex, 200, json.encodeToString(rec))
            }
            path == "/api/compare" && method == "GET" -> handleCompare(ex)
            path == "/api/export" && method == "GET" ->
                respondJson(ex, 200, Json { prettyPrint = true }.encodeToString(store.export()))
            path == "/api/import" && method == "POST" -> {
                val imp = json.decodeFromString<ExportData>(bodyText(ex))
                val err = store.import(imp)
                if (err != null) throw ApiError(400, err)
                respondJson(ex, 200, """{"ok":true}""")
            }
            else -> throw ApiError(404, "未找到路径 $path")
        }
    }

    private fun handleEvaluate(ex: HttpExchange) {
        val req = json.decodeFromString<EvalRequest>(bodyText(ex))
        val state = store.state()
        val contexts = req.contextIds.mapNotNull { state.contexts[it] } + req.inlineContexts
        if (req.flagKeys.isEmpty()) throw ApiError(400, "请至少选择一个开关")
        if (contexts.isEmpty()) throw ApiError(400, "请至少选择一个上下文")
        val snapshot = store.snapshot()
        val evaluator = Evaluator(snapshot)
        val items = mutableListOf<EvalResponseItem>()
        for (flagKey in req.flagKeys) {
            for (ctx in contexts) {
                val trace = evaluator.evaluate(flagKey, ctx.attributes)
                val rec = store.record(flagKey, trace.version, ctx.id.ifBlank { null }, ctx.name, ctx.attributes, trace)
                items += EvalResponseItem(rec.id, flagKey, trace.version, ctx.name, trace.result, trace)
            }
        }
        respondJson(ex, 200, json.encodeToString(items))
    }

    private fun handleCompare(ex: HttpExchange) {
        val params = ex.requestURI.query?.split('&')?.associate {
            val parts = it.split('=', limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        } ?: emptyMap()
        val flagKey = params["flag"] ?: throw ApiError(400, "缺少 flag 参数")
        val v1 = params["v1"]?.toIntOrNull() ?: throw ApiError(400, "缺少 v1 参数")
        val v2 = params["v2"]?.toIntOrNull() ?: throw ApiError(400, "缺少 v2 参数")
        val contexts = store.state().contexts.values.toList()
        val snapA = store.snapshot(mapOf(flagKey to v1))
        val snapB = store.snapshot(mapOf(flagKey to v2))
        val evalA = Evaluator(snapA)
        val evalB = Evaluator(snapB)
        val items = contexts.map { ctx ->
            CompareItem(ctx.id, ctx.name, evalA.evaluate(flagKey, ctx.attributes), evalB.evaluate(flagKey, ctx.attributes))
        }
        respondJson(ex, 200, json.encodeToString(items))
    }

    private fun bodyText(ex: HttpExchange): String =
        ex.requestBody.readBytes().toString(Charsets.UTF_8)

    private fun bodyJson(ex: HttpExchange): JsonObject =
        Json.parseToJsonElement(bodyText(ex)).jsonObject

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: ""

    private fun respondJson(ex: HttpExchange, status: Int, body: String) =
        respondBytes(ex, status, body.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")

    private fun respondBytes(ex: HttpExchange, status: Int, body: ByteArray, contentType: String) {
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(status, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }
}

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5233
    var i = 0
    while (i < args.size - 1) {
        when (args[i]) {
            "--host" -> host = args[i + 1]
            "--port" -> port = args[i + 1].toInt()
        }
        i += 2
    }
    val store = Store(java.io.File("data/store.json"))
    val server = ApiServer(store, host, port)
    server.start()
    println("功能开关追踪器已启动: http://$host:$port")
}
