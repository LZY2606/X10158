package tracer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.Executors

class Server(private val store: Store, host: String, port: Int) {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)

    init {
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/") { ex -> handle(ex) }
    }

    fun start() {
        server.start()
        println("功能开关追踪器 listening on http://${server.address.hostString}:${server.address.port}")
    }

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            val method = ex.requestMethod
            when {
                path == "/" && method == "GET" -> sendHtml(ex, indexHtml())
                path == "/api/state" && method == "GET" -> sendJson(ex, 200, stateJson())
                path == "/api/flags" && method == "POST" -> sendJson(ex, 200, createFlag(body(ex)))
                path.matches(Regex("/api/flags/[^/]+")) && method == "PUT" ->
                    sendJson(ex, 200, updateFlag(flagKey(path), body(ex)))
                path.matches(Regex("/api/flags/[^/]+")) && method == "DELETE" ->
                    sendJson(ex, 200, deleteFlag(flagKey(path)))
                path.matches(Regex("/api/flags/[^/]+/publish")) && method == "POST" ->
                    sendJson(ex, 200, publish(flagKey(path)))
                path == "/api/contexts" && method == "POST" -> sendJson(ex, 200, saveContext(body(ex)))
                path.matches(Regex("/api/contexts/[^/]+")) && method == "DELETE" ->
                    sendJson(ex, 200, deleteContext(path.substringAfterLast('/')))
                path == "/api/evaluate" && method == "POST" -> sendJson(ex, 200, evaluate(body(ex)))
                path == "/api/evaluate-batch" && method == "POST" -> sendJson(ex, 200, evaluateBatch(body(ex)))
                path == "/api/compare" && method == "POST" -> sendJson(ex, 200, compare(body(ex)))
                path == "/api/evaluations" && method == "POST" -> sendJson(ex, 200, saveEvaluation(body(ex)))
                path.matches(Regex("/api/evaluations/[^/]+")) && method == "GET" ->
                    sendJson(ex, 200, getEvaluation(path.substringAfterLast('/')))
                path.matches(Regex("/api/evaluations/[^/]+")) && method == "DELETE" ->
                    sendJson(ex, 200, deleteEvaluation(path.substringAfterLast('/')))
                path == "/api/settings" && method == "PUT" -> sendJson(ex, 200, updateSettings(body(ex)))
                path == "/api/export" && method == "GET" -> sendJson(ex, 200, store.exportJson())
                path == "/api/import" && method == "POST" -> sendJson(ex, 200, import(body(ex)))
                else -> sendJson(ex, 404, error("not found: $method $path"))
            }
        } catch (e: CycleException) {
            sendJson(ex, 409, objOf(
                "error" to JVal.JStr(e.message ?: "cycle"),
                "cyclePath" to JVal.JArr(e.path.map { JVal.JStr(it) })
            ))
        } catch (e: Exception) {
            sendJson(ex, 400, error(e.message ?: e.javaClass.simpleName))
        } finally {
            ex.close()
        }
    }

    private fun flagKey(path: String) = path.split("/")[3]

    private fun body(ex: HttpExchange): JVal.JObj {
        val text = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        if (text.isBlank()) return JVal.JObj(LinkedHashMap())
        return Json.parse(text) as? JVal.JObj
            ?: throw IllegalArgumentException("request body must be a JSON object")
    }

    private fun stateJson(): JVal.JObj = store.read { s ->
        objOf(
            "sensitiveFields" to JVal.JArr(s.sensitiveFields.map { JVal.JStr(it) }),
            "flags" to JVal.JArr(s.flags.values.map { f ->
                objOf(
                    "key" to JVal.JStr(f.key),
                    "latestVersion" to JVal.JNum(f.latestVersion.toDouble()),
                    "draft" to f.draft.toJson(),
                    "versions" to JVal.JArr(f.versions.map {
                        objOf(
                            "version" to JVal.JNum(it.version.toDouble()),
                            "publishedAt" to JVal.JNum(it.publishedAt.toDouble()),
                            "def" to it.def.toJson()
                        )
                    })
                )
            }),
            "contexts" to JVal.JArr(s.contexts.values.map { it.toJson() }),
            "evaluations" to JVal.JArr(s.evaluations.values.map {
                objOf(
                    "id" to JVal.JStr(it.id),
                    "flagKey" to JVal.JStr(it.flagKey),
                    "version" to JVal.JNum(it.version.toDouble()),
                    "contextName" to JVal.JStr(it.contextName),
                    "result" to it.result,
                    "createdAt" to JVal.JNum(it.createdAt.toDouble())
                )
            })
        )
    }

    private fun createFlag(j: JVal.JObj): JVal {
        val key = j["key"].strOrNull()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("key required")
        return store.write { s ->
            if (s.flags.containsKey(key)) throw IllegalArgumentException("flag '$key' already exists")
            val def = FlagDef(key, java.util.UUID.randomUUID().toString(), "id", emptyList(), emptyList(), emptyList(), JVal.JBool(false))
            s.flags[key] = Flag(key, def, emptyList())
            objOf("ok" to JVal.JBool(true))
        }
    }

    private fun updateFlag(key: String, j: JVal.JObj): JVal = store.write { s ->
        val f = s.flags[key] ?: throw EvalException("flag not found: $key")
        val draft = FlagDef.fromJson(j["draft"].objOrNull() ?: throw IllegalArgumentException("draft required"))
        if (draft.key != key) throw IllegalArgumentException("draft key mismatch")
        // validate cycle on the would-be graph
        val defs = s.flags.mapValues { (k, v) -> if (k == key) draft else (v.versions.lastOrNull()?.def ?: v.draft) }
        FlagSnapshot(defs, defs.mapValues { 0 }).checkCycles()
        s.flags[key] = f.copy(draft = draft)
        objOf("ok" to JVal.JBool(true))
    }

    private fun deleteFlag(key: String): JVal = store.write { s ->
        s.flags.remove(key) ?: throw EvalException("flag not found: $key")
        objOf("ok" to JVal.JBool(true))
    }

    private fun publish(key: String): JVal {
        val v = store.publish(key)
        return objOf("ok" to JVal.JBool(true), "version" to JVal.JNum(v.version.toDouble()))
    }

    private fun saveContext(j: JVal.JObj): JVal = store.write { s ->
        val id = j["id"].strOrNull()?.takeIf { it.isNotBlank() } ?: Store.newId()
        val name = j["name"].strOrNull() ?: "context"
        val fields = j["fields"].objOrNull() ?: JVal.JObj(LinkedHashMap())
        s.contexts[id] = SavedContext(id, name, fields)
        objOf("ok" to JVal.JBool(true), "id" to JVal.JStr(id))
    }

    private fun deleteContext(id: String): JVal = store.write { s ->
        s.contexts.remove(id)
        objOf("ok" to JVal.JBool(true))
    }

    private fun evaluate(j: JVal.JObj): JVal {
        val flagKey = j["flagKey"].strOrNull() ?: throw IllegalArgumentException("flagKey required")
        val version = j["version"].numOrNull()?.toInt()
        val context = j["context"].objOrNull() ?: JVal.JObj(LinkedHashMap())
        val snapshot = store.snapshotOf(flagKey, version)
        return store.engine().evaluate(snapshot, flagKey, context)
    }

    private fun evaluateBatch(j: JVal.JObj): JVal {
        val flagKey = j["flagKey"].strOrNull()
        val version = j["version"].numOrNull()?.toInt()
        val contextIds = j["contextIds"].arrOrNull()?.mapNotNull { it.strOrNull() }
        val contexts = store.read { s ->
            (contextIds ?: s.contexts.keys.toList()).mapNotNull { s.contexts[it] }
        }
        // one snapshot for the whole batch: versions published mid-batch cannot leak in
        val snapshot = if (flagKey != null) store.snapshotOf(flagKey, version) else store.snapshot()
        val engine = store.engine()
        val keys = if (flagKey != null) listOf(flagKey) else snapshot.keys.toList()
        val results = mutableListOf<JVal>()
        for (c in contexts) {
            for (k in keys) {
                val trace = engine.evaluate(snapshot, k, c.fields)
                results.add(objOf(
                    "contextId" to JVal.JStr(c.id),
                    "contextName" to JVal.JStr(c.name),
                    "flagKey" to JVal.JStr(k),
                    "result" to (trace["result"] ?: JVal.JNull),
                    "trace" to trace
                ))
            }
        }
        return objOf("results" to JVal.JArr(results))
    }

    private fun compare(j: JVal.JObj): JVal {
        val flagKey = j["flagKey"].strOrNull() ?: throw IllegalArgumentException("flagKey required")
        val va = j["versionA"].numOrNull()?.toInt() ?: throw IllegalArgumentException("versionA required")
        val vb = j["versionB"].numOrNull()?.toInt() ?: throw IllegalArgumentException("versionB required")
        val contextIds = j["contextIds"].arrOrNull()?.mapNotNull { it.strOrNull() }
        val contexts = store.read { s ->
            (contextIds ?: s.contexts.keys.toList()).mapNotNull { s.contexts[it] }
        }
        val snapA = store.snapshotOf(flagKey, va)
        val snapB = store.snapshotOf(flagKey, vb)
        val engine = store.engine()
        val rows = contexts.map { c ->
            val ra = engine.evaluate(snapA, flagKey, c.fields)
            val rb = engine.evaluate(snapB, flagKey, c.fields)
            objOf(
                "contextId" to JVal.JStr(c.id),
                "contextName" to JVal.JStr(c.name),
                "resultA" to (ra["result"] ?: JVal.JNull),
                "resultB" to (rb["result"] ?: JVal.JNull),
                "changed" to JVal.JBool(ra["result"] != rb["result"]),
                "traceA" to ra,
                "traceB" to rb
            )
        }
        return objOf(
            "flagKey" to JVal.JStr(flagKey),
            "versionA" to JVal.JNum(va.toDouble()),
            "versionB" to JVal.JNum(vb.toDouble()),
            "rows" to JVal.JArr(rows)
        )
    }

    private fun saveEvaluation(j: JVal.JObj): JVal = store.write { s ->
        val flagKey = j["flagKey"].strOrNull() ?: throw IllegalArgumentException("flagKey required")
        val version = j["version"].numOrNull()?.toInt()
            ?: throw IllegalArgumentException("version required")
        val trace = j["trace"].objOrNull() ?: throw IllegalArgumentException("trace required")
        val e = SavedEvaluation(
            id = Store.newId(),
            flagKey = flagKey,
            version = version,
            contextName = j["contextName"].strOrNull() ?: "",
            context = j["context"].objOrNull() ?: JVal.JObj(LinkedHashMap()),
            result = j["result"] ?: JVal.JNull,
            trace = trace,
            createdAt = System.currentTimeMillis()
        )
        s.evaluations[e.id] = e
        objOf("ok" to JVal.JBool(true), "id" to JVal.JStr(e.id))
    }

    private fun getEvaluation(id: String): JVal = store.read { s ->
        s.evaluations[id]?.toJson() ?: throw EvalException("evaluation not found: $id")
    }

    private fun deleteEvaluation(id: String): JVal = store.write { s ->
        s.evaluations.remove(id)
        objOf("ok" to JVal.JBool(true))
    }

    private fun updateSettings(j: JVal.JObj): JVal = store.write { s ->
        s.sensitiveFields.clear()
        j["sensitiveFields"].arrOrNull()?.mapNotNull { it.strOrNull() }?.forEach { s.sensitiveFields.add(it) }
        objOf("ok" to JVal.JBool(true))
    }

    private fun import(j: JVal.JObj): JVal {
        val (nf, nc) = store.importJson(j)
        return objOf(
            "ok" to JVal.JBool(true),
            "flags" to JVal.JNum(nf.toDouble()),
            "contexts" to JVal.JNum(nc.toDouble())
        )
    }

    private fun error(msg: String) = objOf("error" to JVal.JStr(msg))

    private fun sendJson(ex: HttpExchange, code: Int, body: JVal) {
        val bytes = Json.render(body).toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sendHtml(ex: HttpExchange, html: String) {
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun indexHtml(): String {
        val stream = javaClass.getResourceAsStream("/static/index.html")
            ?: return "<h1>static/index.html missing</h1>"
        return stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
    }
}
