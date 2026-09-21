package ft

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets

class ApiServer(private val service: Service, private val host: String, private val port: Int) {
    private lateinit var server: HttpServer

    fun start() {
        server = HttpServer.create(InetSocketAddress(host, port), 0)
        server.createContext("/", ::handle)
        server.executor = null
        server.start()
        println("功能开关追踪器 listening on http://$host:$port")
    }

    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        try {
            route(ex)
        } catch (e: AppException) {
            writeJson(ex, e.status, Json.obj("error" to Json.s(e.message!!)))
        } catch (e: IllegalArgumentException) {
            writeJson(ex, 400, Json.obj("error" to Json.s(e.message ?: "bad request")))
        } catch (e: Exception) {
            e.printStackTrace()
            writeJson(ex, 500, Json.obj("error" to Json.s(e.message ?: "internal error")))
        } finally {
            ex.close()
        }
    }

    private fun route(ex: HttpExchange) {
        val uri: URI = ex.requestURI
        val path = uri.path
        val method = ex.requestMethod
        if (method == "GET" && (path == "/" || path == "/index.html")) {
            return static(ex, "web/index.html", "text/html; charset=utf-8")
        }
        if (method == "GET" && path == "/app.js") {
            return static(ex, "web/app.js", "application/javascript; charset=utf-8")
        }
        if (method == "GET" && path == "/styles.css") {
            return static(ex, "web/styles.css", "text/css; charset=utf-8")
        }

        // ---- API ----
        when {
            method == "GET" && path == "/api/state" ->
                writeJson(ex, 200, Codecs.encodeProject(service.snapshot()))

            method == "POST" && path == "/api/flags" -> {
                val body = readBody(ex)
                val req = Json.parse(body) as JObj
                val draft = Drafts.parse(req.obj("version"))
                val flag = service.createFlag(req.str("key"), req.strOpt("name") ?: "", draft)
                writeJson(ex, 201, Codecs.encodeFlag(flag))
            }

            method == "POST" && Regex("^/api/flags/([^/]+)/versions$").matches(path) -> {
                val key = Regex("^/api/flags/([^/]+)/versions$").find(path)!!.groupValues[1]
                val req = Json.parse(readBody(ex)) as JObj
                val fv = service.publishVersion(key, Drafts.parse(req))
                writeJson(ex, 201, Codecs.encodeVersion(fv))
            }

            method == "POST" && Regex("^/api/flags/([^/]+)/evaluate$").matches(path) -> {
                val key = Regex("^/api/flags/([^/]+)/evaluate$").find(path)!!.groupValues[1]
                val req = Json.parse(readBody(ex)) as JObj
                val version = req.intOpt("version")
                val pinned = parsePinned(req.map["pinnedVersions"])
                val save = (req.map["saveRecord"] as? JBool)?.value == true
                val ctxId = req.strOpt("contextId")
                val ctxName = req.strOpt("contextName")
                val result = service.evaluate(
                    key, req.obj("context"),
                    version = version, pinnedVersions = pinned,
                    saveRecord = save, contextId = ctxId, contextName = ctxName,
                )
                writeJson(ex, 200, Json.obj(
                    "value" to result.value,
                    "trace" to Codecs.encodeTrace(result.trace),
                ))
            }

            method == "POST" && path == "/api/evaluate-batch" -> {
                val req = Json.parse(readBody(ex)) as JObj
                val keys = req.arr("flagKeys").items.map { (it as JStr).value }
                val pinned = parsePinned(req.map["pinnedVersions"])
                val save = (req.map["saveRecord"] as? JBool)?.value == true
                val ctxId = req.strOpt("contextId")
                val ctxName = req.strOpt("contextName")
                val batch = service.evaluateBatch(
                    keys, req.obj("context"),
                    pinnedVersions = pinned, saveRecord = save,
                    contextId = ctxId, contextName = ctxName,
                )
                writeJson(ex, 200, Json.obj(
                    "snapshotVersions" to JObj(batch.snapshotVersions.entries
                        .sortedBy { it.key }
                        .map { it.key to Json.n(it.value) }),
                    "results" to JArr(batch.results.map { (k, r) ->
                        Json.obj(
                            "flagKey" to Json.s(k),
                            "version" to Json.n(batch.snapshotVersions.getValue(k)),
                            "value" to r.value,
                            "trace" to Codecs.encodeTrace(r.trace),
                        )
                    }),
                ))
            }

            method == "GET" && Regex("^/api/flags/([^/]+)/compare$").matches(path) -> {
                val key = Regex("^/api/flags/([^/]+)/compare$").find(path)!!.groupValues[1]
                val params = parseQuery(uri.rawQuery)
                val a = params["a"]?.toIntOrNull() ?: throw AppException(400, "missing a")
                val b = params["b"]?.toIntOrNull() ?: throw AppException(400, "missing b")
                val contextIds = params["contexts"]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
                writeJson(ex, 200, Diff.compare(service.snapshot(), key, a, b, contextIds))
            }

            method == "POST" && path == "/api/contexts" -> {
                val req = Json.parse(readBody(ex)) as JObj
                val saved = service.saveContext(
                    req.str("name"),
                    req.obj("data"),
                    id = req.strOpt("id"),
                )
                writeJson(ex, 201, Codecs.encodeContext(saved))
            }

            method == "DELETE" && Regex("^/api/contexts/([^/]+)$").matches(path) -> {
                val id = Regex("^/api/contexts/([^/]+)$").find(path)!!.groupValues[1]
                service.deleteContext(id)
                writeJson(ex, 200, Json.obj("deleted" to Json.s(id)))
            }

            method == "GET" && path == "/api/records" -> {
                val params = parseQuery(uri.rawQuery)
                val records = service.records(params["flagKey"])
                writeJson(ex, 200, JArr(records.map { Codecs.encodeRecord(it) }))
            }

            method == "POST" && Regex("^/api/records/([^/]+)/replay$").matches(path) -> {
                val id = Regex("^/api/records/([^/]+)/replay$").find(path)!!.groupValues[1]
                val (rec, fresh) = service.replayRecord(id)
                writeJson(ex, 200, Json.obj(
                    "record" to Codecs.encodeRecord(rec),
                    "replay" to Json.obj(
                        "value" to fresh.value,
                        "trace" to Codecs.encodeTrace(fresh.trace),
                    ),
                    "sameValue" to Json.b(Json.equal(rec.result, fresh.value)),
                ))
            }

            method == "GET" && path == "/api/export" -> {
                val payload = service.export()
                val bytes = payload.toByteArray(StandardCharsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                ex.responseHeaders.add("Content-Disposition", "attachment; filename=flag-tracker-export.json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }

            method == "POST" && path == "/api/import" -> {
                val params = parseQuery(uri.rawQuery)
                val mode = if (params["mode"] == "replace") Service.ImportMode.REPLACE else Service.ImportMode.MERGE
                val p = service.import(readBody(ex), mode)
                writeJson(ex, 200, Json.obj(
                    "projectId" to Json.s(p.id),
                    "flags" to Json.n(p.flags.size),
                    "contexts" to Json.n(p.contexts.size),
                    "records" to Json.n(p.records.size),
                ))
            }

            else -> writeJson(ex, 404, Json.obj("error" to Json.s("not found: $method $path")))
        }
    }

    private fun parsePinned(v: JsonValue?): Map<String, Int>? {
        val o = v as? JObj ?: return null
        return o.entries.associate { (key, value) ->
            val num = value as? JNum ?: throw AppException(400, "pinnedVersions.$key must be a number")
            key to num.num.toInt()
        }
    }

    private fun static(ex: HttpExchange, resource: String, contentType: String) {
        val url = javaClass.classLoader.getResource(resource)
            ?: return writeJson(ex, 404, Json.obj("error" to Json.s("missing resource $resource")))
        val bytes = url.openStream().use { it.readBytes() }
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun readBody(ex: HttpExchange): String =
        ex.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }

    private fun writeJson(ex: HttpExchange, status: Int, body: JsonValue) {
        val bytes = Json.write(body).toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun parseQuery(q: String?): Map<String, String> {
        if (q.isNullOrBlank()) return emptyMap()
        return q.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i < 0) null
            else java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }.toMap()
    }
}

object Drafts {
    fun parse(v: JsonValue): VersionDraft {
        val o = v as? JObj ?: throw AppException(400, "version must be an object")
        return VersionDraft(
            type = FlagTypeEx.from(o.str("type")),
            default = o.map["default"] ?: throw AppException(400, "version.default required"),
            salt = o.strOpt("salt")?.takeIf { it.isNotBlank() } ?: ("salt-" + Store.shortId()),
            stableIdentityField = o.str("stableIdentityField"),
            prerequisiteKey = o.strOpt("prerequisiteKey"),
            prerequisiteExpected = o.map["prerequisiteExpected"],
            sensitiveFields = (o.map["sensitiveFields"] as? JArr)?.items?.map { (it as JStr).value }
                ?: emptyList(),
            rules = (o.map["rules"] as? JArr)?.items?.map { Codecs.decodeRule(it) } ?: emptyList(),
        )
    }
}
