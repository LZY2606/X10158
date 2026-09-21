package tracker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class Server(private val store: Store, host: String, port: Int) {
    private val server = HttpServer.create(InetSocketAddress(host, port), 0)

    init {
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/") { ex -> handle(ex) }
    }

    fun start() {
        server.start()
        println("功能开关追踪器已启动: http://${server.address.hostString}:${server.address.port}")
    }

    private fun handle(ex: HttpExchange) {
        try {
            val resp = route(ex)
            if (resp != null) respond(ex, 200, resp)
        } catch (e: CycleException) {
            respond(ex, 409, jObjOf(
                "error" to JStr(e.message ?: "前置依赖存在环"),
                "cycle" to JArr(e.path.map { JStr(it) })
            ))
        } catch (e: EvalException) {
            respond(ex, 400, jObjOf("error" to JStr(e.message ?: "求值错误")))
        } catch (e: IllegalArgumentException) {
            respond(ex, 400, jObjOf("error" to JStr(e.message ?: "参数错误")))
        } catch (e: Json.JsonException) {
            respond(ex, 400, jObjOf("error" to JStr("JSON 解析失败: ${e.message}")))
        } catch (e: Exception) {
            respond(ex, 500, jObjOf("error" to JStr("服务器错误: ${e.message}")))
        } finally {
            ex.close()
        }
    }

    private fun route(ex: HttpExchange): JVal? {
        val method = ex.requestMethod
        val path = ex.requestURI.path.trim('/').split('/').filter { it.isNotEmpty() }
        val query = parseQuery(ex.requestURI.rawQuery ?: "")

        if (path.isEmpty()) {
            val html = javaClass.getResourceAsStream("/static/index.html")!!.readBytes()
            ex.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, html.size.toLong())
            ex.responseBody.use { it.write(html) }
            return null // 已直接写出
        }
        require(path[0] == "api") { "未知路径" }

        fun body(): JObj {
            val text = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            return if (text.isBlank()) jObjOf() else Json.parse(text) as JObj
        }
        fun str(j: JObj, k: String) = (j[k] as? JStr)?.v
        fun projectOf(j: JObj) = str(j, "projectId") ?: "default"

        return when {
            // 状态总览
            method == "GET" && path == listOf("api", "state") -> jObjOf(
                "projects" to JArr(store.listProjects().map { it.toJson() }),
                "flags" to JArr(store.listFlags().map { it.toJson() }),
                "contexts" to JArr(store.listContexts().map { it.toJson() })
            )
            method == "POST" && path == listOf("api", "projects") -> {
                val b = body()
                store.createProject(str(b, "name") ?: "未命名项目").toJson()
            }
            method == "POST" && path == listOf("api", "flags") -> {
                val b = body()
                store.createFlag(str(b, "key") ?: throw EvalException("缺少 key")).toJson()
            }
            method == "POST" && path.size == 4 && path[0] == "api" && path[1] == "flags" && path[3] == "versions" -> {
                val b = body()
                val rules = (b["rules"] as? JArr)?.items?.map { Rule.fromJson(it as JObj) } ?: emptyList()
                val prereqs = (b["prerequisites"] as? JArr)?.items?.map { Prereq.fromJson(it as JObj) } ?: emptyList()
                store.publishVersion(path[2], b["defaultValue"] ?: JBool(false), rules, prereqs).toJson()
            }
            method == "POST" && path == listOf("api", "contexts") -> {
                val b = body()
                val sensitive = ((b["sensitive"] as? JArr)?.items?.map { (it as JStr).v } ?: emptyList()).toSet()
                store.saveContext(
                    str(b, "id"),
                    str(b, "name") ?: "未命名上下文",
                    (b["attrs"] as? JObj) ?: jObjOf(),
                    sensitive
                ).toJson()
            }
            method == "DELETE" && path.size == 3 && path[0] == "api" && path[1] == "contexts" -> {
                store.deleteContext(path[2]); jObjOf("ok" to JBool(true))
            }
            method == "POST" && path == listOf("api", "evaluate") -> {
                val b = body()
                val flagKey = str(b, "flagKey") ?: throw EvalException("缺少 flagKey")
                val version = (b["version"] as? JNum)?.dec?.toInt()
                val (attrs, sensitive, ctx) = resolveContext(b)
                val trace = store.evaluate(projectOf(b), flagKey, version, attrs, sensitive)
                val out = mutableMapOf<String, JVal>("trace" to trace)
                if (b["save"] == JBool(true) && ctx != null) {
                    out["record"] = store.saveRecord(flagKey, trace, ctx).toJson()
                }
                JObj(LinkedHashMap(out))
            }
            method == "POST" && path == listOf("api", "evaluate", "batch") -> {
                val b = body()
                val pid = projectOf(b)
                val flagKeys = (b["flagKeys"] as? JArr)?.items?.map { (it as JStr).v }
                    ?: store.listFlags().map { it.key }
                val ctxIds = (b["contextIds"] as? JArr)?.items?.map { (it as JStr).v }
                    ?: store.listContexts().map { it.id }
                // 关键：一次批量求值只捕获一次快照
                val snap = store.snapshot()
                val ev = store.evaluator(pid, snap)
                val results = ctxIds.flatMap { cid ->
                    val c = store.context(cid)
                    flagKeys.map { fk ->
                        jObjOf(
                            "flagKey" to JStr(fk),
                            "contextId" to JStr(cid),
                            "version" to JNum(snap.versions[fk]!!.version.toString()),
                            "result" to ev.evaluate(fk, c.attrs, c.sensitive)["result"]!!
                        )
                    }
                }
                jObjOf("results" to JArr(results))
            }
            method == "GET" && path == listOf("api", "records") -> {
                JArr(store.listRecords(query["flagKey"]).map { it.toJson() })
            }
            method == "POST" && path.size == 4 && path[0] == "api" && path[1] == "records" && path[3] == "replay" -> {
                val b = body()
                jObjOf("trace" to store.replay(projectOf(b), path[2]))
            }
            method == "GET" && path == listOf("api", "compare") -> {
                val flagKey = query["flagKey"] ?: throw EvalException("缺少 flagKey")
                val v1 = query["v1"]?.toInt() ?: throw EvalException("缺少 v1")
                val v2 = query["v2"]?.toInt() ?: throw EvalException("缺少 v2")
                val pid = query["projectId"] ?: "default"
                val rows = store.listContexts().map { c ->
                    val r1 = store.evaluate(pid, flagKey, v1, c.attrs, c.sensitive)["result"]!!
                    val r2 = store.evaluate(pid, flagKey, v2, c.attrs, c.sensitive)["result"]!!
                    jObjOf(
                        "contextId" to JStr(c.id),
                        "contextName" to JStr(c.name),
                        "v1" to r1,
                        "v2" to r2,
                        "changed" to JBool(!Json.strictEquals(r1, r2))
                    )
                }
                jObjOf("rows" to JArr(rows))
            }
            method == "GET" && path == listOf("api", "export") -> store.export()
            method == "POST" && path == listOf("api", "import") -> {
                store.import(body())
                jObjOf("ok" to JBool(true))
            }
            else -> throw EvalException("未知接口: $method /${path.joinToString("/")}")
        }
    }

    private fun resolveContext(b: JObj): Triple<JObj, Set<String>, Ctx?> {
        val ctxId = (b["contextId"] as? JStr)?.v
        if (ctxId != null) {
            val c = store.context(ctxId)
            return Triple(c.attrs, c.sensitive, c)
        }
        val attrs = (b["attrs"] as? JObj) ?: jObjOf()
        val sensitive = ((b["sensitive"] as? JArr)?.items?.map { (it as JStr).v } ?: emptyList()).toSet()
        return Triple(attrs, sensitive, null)
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            java.net.URLDecoder.decode(k, StandardCharsets.UTF_8) to
                java.net.URLDecoder.decode(v, StandardCharsets.UTF_8)
        }

    private fun respond(ex: HttpExchange, code: Int, body: JVal) {
        val bytes = Json.render(body).toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
