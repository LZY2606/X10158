package fst

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ApiException(val code: Int, message: String) : RuntimeException(message)

class Server(private val store: Store, private val webDir: Path) {
    private lateinit var http: HttpServer

    fun start(host: String, port: Int) {
        http = HttpServer.create(InetSocketAddress(host, port), 0)
        http.createContext("/") { exch ->
            try { route(exch) } catch (e: ApiException) { writeJson(exch, e.code, Json.obj("error" to (e.message ?: "error"))) }
            catch (e: CycleException) { writeJson(exch, 422, Json.obj("error" to "cycle", "path" to e.path)) }
            catch (e: RuleValidationException) { writeJson(exch, 422, Json.obj("error" to (e.message ?: "invalid rule"))) }
            catch (e: IllegalArgumentException) { writeJson(exch, 400, Json.obj("error" to (e.message ?: "bad request"))) }
            catch (e: NoSuchElementException) { writeJson(exch, 404, Json.obj("error" to (e.message ?: "not found"))) }
            catch (e: Exception) { e.printStackTrace(); writeJson(exch, 500, Json.obj("error" to (e.message ?: "internal error"))) }
        }
        http.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        http.start()
        println("功能开关追踪器已启动：http://$host:$port")
    }

    fun stop() { http.stop(0) }

    private fun body(exch: HttpExchange): Map<String, Any?> {
        val text = exch.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        if (text.isBlank()) return emptyMap()
        return Json.parseObject(text)
    }

    private fun writeJson(exch: HttpExchange, code: Int, payload: Any?) {
        val bytes = Json.write(payload).toByteArray(StandardCharsets.UTF_8)
        exch.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exch.sendResponseHeaders(code, bytes.size.toLong())
        exch.responseBody.use { it.write(bytes) }
    }

    private fun route(exch: HttpExchange) {
        val uri: URI = exch.httpContext.let { exch.requestURI }
        val path = exch.requestURI.path.trimEnd('/').ifEmpty { "/" }
        val method = exch.requestMethod
        if (method == "GET" && (path == "/" || path == "/index.html")) return serveStatic(exch, "index.html", "text/html; charset=utf-8")
        if (method == "GET" && path.startsWith("/static/")) {
            val name = path.removePrefix("/static/")
            if (!name.matches(Regex("[A-Za-z0-9_.-]+"))) throw ApiException(400, "bad asset")
            return serveStatic(exch, name, mime(name))
        }

        when {
            method == "GET" && path == "/api/state" -> {
                val p = store.project
                writeJson(exch, 200, Json.obj(
                    "project" to Json.obj("id" to p.id, "name" to p.name),
                    "flags" to p.flags.map { it.toJson() },
                    "contexts" to p.contexts.map { it.toJson() },
                    "records" to p.records.sortedByDescending { it.createdAt }.map { maskRecord(it).toJson() },
                ))
            }
            method == "POST" && path == "/api/flags" -> createFlag(exch)
            method == "POST" && path.matches(Regex("/api/flags/[^/]+/meta")) -> updateMeta(exch, path)
            method == "POST" && path.matches(Regex("/api/flags/[^/]+/versions")) -> publish(exch, path)
            method == "POST" && path.matches(Regex("/api/flags/[^/]+/reorder")) -> reorder(exch, path)
            method == "POST" && path.matches(Regex("/api/flags/[^/]+/edit-version")) -> editVersion(exch, path)
            method == "POST" && path == "/api/evaluate" -> evaluate(exch)
            method == "POST" && path == "/api/batch" -> batch(exch)
            method == "POST" && path == "/api/compare" -> compare(exch)
            method == "GET" && path.matches(Regex("/api/records/[^/]+/replay")) -> replay(exch, path)
            method == "DELETE" && path.matches(Regex("/api/records/[^/]+")) -> deleteRecord(exch, path)
            method == "POST" && path == "/api/records/clear" -> { store.clearRecords(); writeJson(exch, 200, Json.obj("ok" to true)) }
            method == "POST" && path == "/api/contexts" -> saveCtx(exch, null)
            method == "POST" && path.matches(Regex("/api/contexts/[^/]+")) -> saveCtx(exch, path.substringAfterLast('/'))
            method == "DELETE" && path.matches(Regex("/api/contexts/[^/]+")) -> {
                store.deleteContext(path.substringAfterLast('/')); writeJson(exch, 200, Json.obj("ok" to true))
            }
            method == "GET" && path == "/api/export" -> writeJson(exch, 200, store.export())
            method == "POST" && path == "/api/import" -> import(exch)
            else -> throw ApiException(404, "未找到路径：$method $path")
        }
    }

    private fun serveStatic(exch: HttpExchange, name: String, mime: String) {
        val res = javaClass.getResourceAsStream("/web/$name")?.use { it.readBytes() }
            ?: Files.newInputStream(webDir.resolve(name)).use { it.readBytes() }
        exch.responseHeaders.add("Content-Type", mime)
        exch.sendResponseHeaders(200, res.size.toLong())
        exch.responseBody.use { it.write(res) }
    }
    private fun mime(n: String) = when {
        n.endsWith(".js") -> "application/javascript; charset=utf-8"
        n.endsWith(".css") -> "text/css; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun maskRecord(r: EvalRecord): EvalRecord {
        val sensitive = store.project.flags.firstOrNull { it.key == r.flagKey }?.sensitiveFields.orEmpty()
        if (sensitive.isEmpty()) return r
        val ctx2 = r.context.mapValues { e ->
            if (sensitive.any { sf -> e.key == sf || e.key.startsWith("$sf.") }) "***敏感值已隐藏***" else e.value
        }
        return r.copy(context = ctx2)
    }

    private fun createFlag(exch: HttpExchange) {
        val b = body(exch)
        val f = store.createFlag(b.str("key"), (b["name"] as? String) ?: "",
            (b["stableIdField"] as? String) ?: "id",
            (b["sensitiveFields"] as? List<*>)?.map { it.asString() } ?: emptyList())
        writeJson(exch, 200, f.toJson())
    }

    private fun updateMeta(exch: HttpExchange, path: String) {
        val key = path.split('/')[3]
        val b = body(exch)
        store.updateFlagMeta(key, b["name"] as? String, b["stableIdField"] as? String,
            (b["sensitiveFields"] as? List<*>)?.map { it.asString() })
        writeJson(exch, 200, store.requireFlag(key).toJson())
    }

    /* ---------- body parsing ---------- */
    private fun Map<String, Any?>.ruleList(): List<Rule> =
        (this["rules"] as? List<*>)?.map { Rule.from(it.asMap()) } ?: emptyList()
    private fun Map<String, Any?>.preList(): List<Prerequisite> =
        (this["prerequisites"] as? List<*>)?.map { Prerequisite.from(it.asMap()) } ?: emptyList()
    private fun Map<String, Any?>.variants(): List<String> =
        (this["onVariants"] as? List<*>)?.map { it.asString() }
            ?: throw IllegalArgumentException("缺少 onVariants")

    private fun publish(exch: HttpExchange, path: String) {
        val key = path.split('/')[3]
        val b = body(exch)
        val v = store.publishVersion(
            key = key,
            onVariants = b.variants(),
            prerequisites = b.preList(),
            rules = b.ruleList(),
            defaultServe = Serve.parse(b["defaultServe"]),
            salt = b["salt"] as? String,
            note = (b["note"] as? String) ?: "",
            versionOverride = (b["version"] as? Number)?.toInt(),
        )
        writeJson(exch, 200, Json.obj("flag" to store.requireFlag(key).toJson(), "version" to v.version))
    }

    private fun reorder(exch: HttpExchange, path: String) {
        val key = path.split('/')[3]
        val b = body(exch)
        val ids = (b["order"] as? List<*>)?.map { it.asString() } ?: throw IllegalArgumentException("缺少 order")
        val v = store.reorderRules(key, ids)
        writeJson(exch, 200, Json.obj("flag" to store.requireFlag(key).toJson(), "version" to v.version))
    }

    private fun editVersion(exch: HttpExchange, path: String) {
        val key = path.split('/')[3]
        val b = body(exch)
        val rules = b.ruleList()
        val pre = b.preList()
        val variants = (b["onVariants"] as? List<*>)?.map { it.asString() }
        val def = b["defaultServe"]?.let { Serve.parse(it) }
        val note = b["note"] as? String
        val v = store.editCurrentVersion(key) { cur ->
            cur.copy(
                onVariants = variants ?: cur.onVariants,
                prerequisites = pre.takeIf { b.containsKey("prerequisites") } ?: cur.prerequisites,
                rules = rules.takeIf { b.containsKey("rules") } ?: cur.rules,
                defaultServe = def ?: cur.defaultServe,
                note = note ?: cur.note,
            )
        }
        writeJson(exch, 200, Json.obj("flag" to store.requireFlag(key).toJson(), "version" to v.version))
    }

    private fun contextPairs(b: Map<String, Any?>): List<Pair<String?, Map<String, Any?>>> {
        val list = b["contexts"] as? List<*>
        if (list != null) {
            return list.map { item ->
                val m = item.asMap()
                val name = m["name"] as? String
                val value = (m["context"] as? Map<*, *>)?.let { mm -> mm.entries.associate { e -> e.key.toString() to e.value } }
                    ?: throw IllegalArgumentException("contexts[].context 必须是对象")
                name to value
            }
        }
        val single = (b["context"] as? Map<*, *>)?.let { mm -> mm.entries.associate { e -> e.key.toString() to e.value } }
            ?: throw IllegalArgumentException("缺少 context 或 contexts")
        return listOf((b["contextName"] as? String) to single)
    }

    private fun evaluate(exch: HttpExchange) {
        val b = body(exch)
        val key = b.str("flagKey")
        val version = (b["version"] as? Number)?.toInt()
        val pairs = contextPairs(b)
        val sel = version?.let { mapOf(key to it) }
        val res = EvalService.batch(store, listOf(key), pairs, sel, persist = true)
        writeJson(exch, 200, Json.obj("snapshot" to res.snapshotAt, "results" to res.results))
    }

    private fun batch(exch: HttpExchange) {
        val b = body(exch)
        val keys = (b["flagKeys"] as? List<*>)?.map { it.asString() } ?: throw IllegalArgumentException("缺少 flagKeys")
        val pairs = contextPairs(b)
        val sel = (b["versions"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.asNum().toInt() }
        val persist = (b["persist"] as? Boolean) ?: false
        val res = EvalService.batch(store, keys, pairs, sel, persist)
        writeJson(exch, 200, Json.obj("snapshot" to res.snapshotAt, "snapshotId" to res.snapshotId, "results" to res.results))
    }

    private fun compare(exch: HttpExchange) {
        val b = body(exch)
        val key = b.str("flagKey")
        val vA = (b["versionA"] as? Number)?.toInt() ?: throw IllegalArgumentException("缺少 versionA")
        val vB = (b["versionB"] as? Number)?.toInt() ?: throw IllegalArgumentException("缺少 versionB")
        val pairList = if (b.containsKey("contextIds")) {
            val ids = (b["contextIds"] as? List<*>)?.map { it.asString() } ?: emptyList()
            ids.map { id ->
                val sc = store.project.contexts.firstOrNull { it.id == id }
                    ?: throw NoSuchElementException("保存的上下文不存在：$id")
                sc.name to sc.value
            }
        } else contextPairs(b)

        val rows = pairList.map { (name, ctx) ->
            val ra = EvalService.batch(store, listOf(key), listOf(name to ctx), mapOf(key to vA)).results.single()
            val rb = EvalService.batch(store, listOf(key), listOf(name to ctx), mapOf(key to vB)).results.single()
            Json.obj(
                "contextName" to name,
                "a" to Json.obj("result" to ra["result"], "trace" to ra["trace"]),
                "b" to Json.obj("result" to rb["result"], "trace" to rb["trace"]),
                "changed" to (ra["result"] != rb["result"]),
            )
        }
        writeJson(exch, 200, Json.obj("flagKey" to key, "versionA" to vA, "versionB" to vB, "rows" to rows))
    }

    private fun replay(exch: HttpExchange, path: String) {
        val id = path.split('/')[3]
        val rec = store.project.records.firstOrNull { it.id == id }
            ?: throw NoSuchElementException("记录不存在：$id")
        writeJson(exch, 200, EvalService.replay(store, rec))
    }

    private fun deleteRecord(exch: HttpExchange, path: String) {
        store.deleteRecord(path.substringAfterLast('/'))
        writeJson(exch, 200, Json.obj("ok" to true))
    }

    private fun saveCtx(exch: HttpExchange, fixedId: String?) {
        val b = body(exch)
        val raw = b["context"] ?: b["value"]
        val value = (raw as? Map<*, *>)?.let { mm -> mm.entries.associate { e -> e.key.toString() to e.value } }
            ?: throw IllegalArgumentException("缺少 context 对象")
        val sc = store.saveContext(b.str("name"), value, fixedId)
        writeJson(exch, 200, sc.toJson())
    }

    private fun import(exch: HttpExchange) {
        val b = body(exch)
        val mode = (b["mode"] as? String) ?: "replace"
        val regenerate = (b["regenerateDigestSalt"] as? Boolean) ?: true
        val bundle = b["bundle"]?.asMap() ?: b
        store.importBundle(bundle, mode, regenerate)
        writeJson(exch, 200, Json.obj("ok" to true, "flags" to store.project.flags.size, "contexts" to store.project.contexts.size))
    }
}
