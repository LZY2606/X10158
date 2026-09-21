package ft

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class WebServer(private val service: Service, host: String, port: Int) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)
    private val mappers = listOf(
        Route("GET", Regex("^/api/state$")) { _, _ -> ok(stateJson()) },
        Route("POST", Regex("^/api/settings$")) { _, b ->
            val m = Json.obj(b)
            @Suppress("UNCHECKED_CAST")
            service.setProjectSettings(
                m["name"] as? String,
                m["sensitiveFields"]?.let { Json.arr(it).map { f -> Json.str(f) } }
            )
            ok(stateJson())
        },
        Route("POST", Regex("^/api/flags$")) { _, b ->
            val m = Json.obj(b)
            val flag = service.createFlag(Json.str(m["key"]), Draft.fromJson(m["draft"]))
            ok(flag.toJson())
        },
        Route("PUT", Regex("^/api/flags/([^/]+)/draft$")) { g, b ->
            val flag = service.updateDraft(decode(g.groupValues[1]), Draft.fromJson(b))
            ok(flag.toJson())
        },
        Route("POST", Regex("^/api/flags/([^/]+)/publish$")) { g, _ ->
            val (flag, fv) = service.publish(decode(g.groupValues[1]))
            ok(linkedMapOf("flag" to flag.toJson(), "published" to fv.toJson()))
        },
        Route("POST", Regex("^/api/flags/([^/]+)/validate$")) { g, _ ->
            ok(service.validatePublish(decode(g.groupValues[1])).let {
                linkedMapOf("valid" to it.valid, "errors" to it.errors, "cyclePath" to it.cyclePath)
            })
        },
        Route("POST", Regex("^/api/flags/([^/]+)/evaluate$")) { g, b ->
            val m = Json.obj(b)
            @Suppress("UNCHECKED_CAST")
            val ctx = Json.obj(m["context"])
            val version = (m["version"] as? Number)?.toInt()
            val (eval, record) = service.evaluate(decode(g.groupValues[1]), ctx, version)
            ok(linkedMapOf("evaluation" to eval.toJson(), "recordId" to record.id))
        },
        Route("POST", Regex("^/api/batch$")) { _, b ->
            val m = Json.obj(b)
            val flagKeys = Json.arr(m["flagKeys"]).map { Json.str(it) }
            val contexts = Json.arr(m["contexts"]).map { Json.obj(it) }
            @Suppress("UNCHECKED_CAST")
            val versions = (m["versions"] as? Map<String, Any?>)?.mapValues { it.value.toString().toInt() } ?: emptyMap()
            ok(service.evaluateBatch(flagKeys, contexts, versions).toJson())
        },
        Route("POST", Regex("^/api/contexts$")) { _, b ->
            val m = Json.obj(b)
            ok(service.saveContext(Json.strOr(m["name"], "context"), Json.obj(m["context"])).toJson())
        },
        Route("DELETE", Regex("^/api/contexts/([^/]+)$")) { g, _ ->
            service.deleteContext(decode(g.groupValues[1])); ok(mapOf("deleted" to true))
        },
        Route("GET", Regex("^/api/records$")) { _, _ ->
            ok(mapOf("records" to service.listRecords().map { it.toJson() }))
        },
        Route("POST", Regex("^/api/records/([^/]+)/replay$")) { g, _ ->
            val (record, eval) = service.replay(decode(g.groupValues[1]))
            ok(linkedMapOf("record" to record.toJson(), "replay" to eval.toJson(),
                "consistent" to (record.outcome == eval.outcome)))
        },
        Route("POST", Regex("^/api/compare$")) { _, b ->
            val m = Json.obj(b)
            ok(service.compare(
                Json.str(m["flagKey"]),
                Json.int(m["versionA"]),
                Json.int(m["versionB"]),
                Json.arr(m["contextIds"]).map { Json.str(it) }
            ).toJson())
        },
        Route("GET", Regex("^/api/export$")) { _, _ ->
            ok(service.exportBundle().toJson())
        },
        Route("POST", Regex("^/api/import$")) { _, b ->
            val bundle = Bundle.fromJson(b)
            service.importBundle(bundle, replace = false)
            ok(stateJson())
        }
    )


    private fun ok(body: Map<String, Any?>): Map<String, Any?> = body

    private data class Route(
        val method: String,
        val path: Regex,
        val handle: (MatchResult, Any?) -> Any?
    )

    init {
        server.createContext("/") { exchange ->
            try {
                route(exchange)
            } catch (e: Throwable) {
                val payload = linkedMapOf("error" to (e.message ?: e.javaClass.simpleName))
                write(exchange, 400, Json.stringify(payload), "application/json")
            }
        }
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
    }

    fun start() { server.start() }
    fun stop() { server.stop(0) }
    val address: InetSocketAddress get() = server.address

    private fun stateJson(): Map<String, Any?> = linkedMapOf(
        "project" to service.project.let {
            linkedMapOf(
                "name" to it.name,
                "bucketSalt" to it.bucketSalt,
                "sensitiveFields" to it.sensitiveFields,
                "summaryDomain" to "project-local"
            )
        },
        "flags" to service.listFlags().map { it.toJson() },
        "savedContexts" to service.listContexts().map { it.toJson() },
        "records" to service.listRecords().take(100).map { it.toJson() }
    )

    private fun route(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.rawPath
        if (method == "GET" && (path == "/" || path == "/index.html")) {
            serveStatic(exchange, "web/index.html", "text/html; charset=utf-8")
            return
        }
        if (method == "GET" && path == "/app.js") {
            serveStatic(exchange, "web/app.js", "application/javascript; charset=utf-8")
            return
        }
        if (method == "GET" && path == "/styles.css") {
            serveStatic(exchange, "web/styles.css", "text/css; charset=utf-8")
            return
        }
        if (method == "GET" && path == "/favicon.ico") {
            exchange.sendResponseHeaders(204, -1); exchange.close(); return
        }

        val body = if (method in setOf("POST", "PUT", "PATCH"))
            exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        else ""
        val parsedBody: Any? = if (body.isBlank()) emptyMap<String, Any?>() else Json.parse(body)

        for (route in mappers) {
            if (route.method != method) continue
            val match = route.path.matchEntire(path) ?: continue
            val result = route.handle(match, parsedBody)
            write(exchange, 200, Json.stringify(result), "application/json")
            return
        }
        write(exchange, 404, Json.stringify(mapOf("error" to "Not found: $method $path")), "application/json")
    }

    private fun serveStatic(exchange: HttpExchange, resource: String, contentType: String) {
        val stream = javaClass.classLoader.getResourceAsStream(resource)
        if (stream == null) {
            write(exchange, 404, "not found", "text/plain")
            return
        }
        val bytes = stream.readBytes()
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun write(exchange: HttpExchange, status: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun decode(s: String) = URLDecoder.decode(s, StandardCharsets.UTF_8)
}
