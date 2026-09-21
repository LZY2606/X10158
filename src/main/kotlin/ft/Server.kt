package ft

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class WebServer(
    private val store: Store,
    host: String,
    port: Int
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)

    fun start() {
        server.createContext("/", ::handle)
        server.executor = Executors.newFixedThreadPool(8)
        server.start()
        println("功能开关追踪器已启动: http://${server.address.hostName}:${server.address.port}")
    }

    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        try {
            route(exchange)
        } catch (e: ApiException) {
            writeJson(
                exchange, e.status,
                jsonObject { "error" to json(e.message ?: "error") }
            )
        } catch (e: IllegalArgumentException) {
            writeJson(
                exchange, 400,
                jsonObject { "error" to json(e.message ?: "bad request") }
            )
        } catch (e: IllegalStateException) {
            writeJson(
                exchange, 400,
                jsonObject { "error" to json(e.message ?: "invalid state") }
            )
        } catch (e: DependencyGraph.CycleError) {
            writeJson(
                exchange, 400,
                jsonObject {
                    "error" to json(e.message ?: "cycle")
                    "code" to json("prerequisite_cycle")
                    "cyclePath" to JsonArray(e.path.map { json(it) })
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            writeJson(
                exchange, 500,
                jsonObject { "error" to json(e.message ?: "internal error") }
            )
        } finally {
            exchange.close()
        }
    }

    private fun route(exchange: HttpExchange) {
        val path = exchange.requestURI.path.trimEnd('/').ifEmpty { "/" }
        val method = exchange.requestMethod

        if (method == "GET" && path == "/") {
            serveStatic(exchange, "/web/index.html", "text/html; charset=utf-8")
            return
        }
        if (method == "GET" && path == "/app.js") {
            serveStatic(exchange, "/web/app.js", "application/javascript; charset=utf-8")
            return
        }
        if (method == "GET" && path == "/styles.css") {
            serveStatic(exchange, "/web/styles.css", "text/css; charset=utf-8")
            return
        }

        when {
            method == "GET" && path == "/api/health" ->
                writeJson(exchange, 200, jsonObject { "status" to json("ok") })

            method == "GET" && path == "/api/projects" ->
                writeJson(exchange, 200, jsonList(store.listProjects().map {
                    Codec.projectToJson(it)
                }))
            method == "POST" && path == "/api/projects" ->
                createProject(exchange)

            path.startsWith("/api/projects/") ->
                projectSubroute(exchange, path.removePrefix("/api/projects/"))

            method == "GET" && path == "/api/contexts" ->
                writeJson(exchange, 200, jsonList(store.listContexts().map {
                    Codec.savedContextToJson(it)
                }))
            method == "POST" && path == "/api/contexts" ->
                createContext(exchange)
            method == "DELETE" && path.startsWith("/api/contexts/") -> {
                store.deleteContext(path.removePrefix("/api/contexts/"))
                writeJson(exchange, 200, jsonObject { "deleted" to json(true) })
            }

            method == "GET" && path == "/api/records" ->
                listRecords(exchange)
            method == "POST" && path.startsWith("/api/records/") &&
                path.endsWith("/replay") ->
                replayRecord(exchange, path)

            method == "GET" && path == "/api/export" ->
                writeJson(exchange, 200, Codec.bundleToJson(store.exportBundle()))
            method == "POST" && path == "/api/import" ->
                importBundle(exchange)

            else -> writeJson(exchange, 404, jsonObject { "error" to json("not found: $path") })
        }
    }

    private fun projectSubroute(exchange: HttpExchange, tailRaw: String) {
        val tail = tailRaw.split("?").first()
        val parts = tail.split("/").filter { it.isNotEmpty() }
        val projectId = parts.firstOrNull()
            ?: throw BadRequest("project id required")

        if (parts.size == 1 && exchange.requestMethod == "GET") {
            writeJson(exchange, 200, projectDetail(projectId))
            return
        }
        if (parts.size == 1 && exchange.requestMethod == "PUT") {
            updateProject(exchange, projectId)
            return
        }
        if (parts.getOrNull(1) == "flags" && parts.size == 2 &&
            exchange.requestMethod == "GET"
        ) {
            writeJson(exchange, 200, jsonList(store.listFlags(projectId).map {
                Codec.flagToJson(it)
            }))
            return
        }
        if (parts.getOrNull(1) == "flags" && parts.size == 3 &&
            exchange.requestMethod == "GET"
        ) {
            writeJson(exchange, 200, Codec.flagToJson(store.getFlag(projectId, parts[2])))
            return
        }
        if (parts.getOrNull(1) == "flags" && parts.size == 3 &&
            exchange.requestMethod == "PUT"
        ) {
            saveFlag(exchange, projectId, parts[2])
            return
        }
        if (parts.getOrNull(1) == "flags" && parts.size == 4 &&
            parts[3] == "publish" && exchange.requestMethod == "POST"
        ) {
            val body = readBodyObject(exchange)
            val note = body["note"].asString ?: ""
            writeJson(
                exchange, 200,
                Codec.flagToJson(store.publishExistingDraft(projectId, parts[2], note))
            )
            return
        }
        if (parts.getOrNull(1) == "evaluate" && parts.size == 2 &&
            exchange.requestMethod == "POST"
        ) {
            evaluate(exchange, projectId)
            return
        }
        if (parts.getOrNull(1) == "compare" && parts.size == 2 &&
            exchange.requestMethod == "POST"
        ) {
            compare(exchange, projectId)
            return
        }
        throw NotFound("route /$tail")
    }

    private fun projectDetail(projectId: String): JsonObject {
        val project = store.getProject(projectId)
        return jsonObject {
            "project" to Codec.projectToJson(project)
            "flags" to JsonArray(store.listFlags(projectId).map { Codec.flagToJson(it) })
            "contexts" to JsonArray(store.listContexts().map {
                Codec.savedContextToJson(it)
            })
        }
    }

    private fun serveStatic(exchange: HttpExchange, resource: String, contentType: String) {
        val url = javaClass.getResource(resource)
        if (url == null) {
            writeJson(exchange, 404, jsonObject { "error" to json("missing resource") })
            return
        }
        val bytes = url.readBytes()
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun writeJson(exchange: HttpExchange, status: Int, value: JsonValue) {
        val bytes = value.toJson().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun readBodyObject(exchange: HttpExchange): JsonObject {
        val text = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).readText()
        if (text.isBlank()) throw BadRequest("request body required")
        return parseJson(text).asObject ?: throw BadRequest("body must be a JSON object")
    }

    private fun createProject(exchange: HttpExchange) {
        val body = readBodyObject(exchange)
        val sensitive = body["sensitiveFields"].asArray?.items
            ?.mapNotNull { it.asString }.orEmpty()
        val project = store.createProject(body["name"].asString ?: "", sensitive)
        writeJson(exchange, 201, Codec.projectToJson(project))
    }

    private fun updateProject(exchange: HttpExchange, projectId: String) {
        val body = readBodyObject(exchange)
        val sensitive = body["sensitiveFields"].asArray?.items
            ?.mapNotNull { it.asString }.orEmpty()
        val updated = store.updateProject(
            projectId,
            body["name"].asString ?: "",
            sensitive
        )
        writeJson(exchange, 200, Codec.projectToJson(updated))
    }

    private fun createContext(exchange: HttpExchange) {
        val body = readBodyObject(exchange)
        val context = body["context"].asObject
            ?: throw BadRequest("context must be a JSON object")
        val saved = store.saveContext(body["name"].asString ?: "未命名上下文", context)
        writeJson(exchange, 201, Codec.savedContextToJson(saved))
    }

    private fun listRecords(exchange: HttpExchange) {
        val projectId = exchange.requestURI.query
            ?.split("&")
            ?.mapNotNull {
                val pair = it.split("=", limit = 2)
                if (pair.size == 2 && pair[0] == "projectId") pair[1] else null
            }?.firstOrNull()
        writeJson(exchange, 200, jsonList(store.listRecords(projectId).map {
            Codec.recordToJson(it)
        }))
    }

    private fun replayRecord(exchange: HttpExchange, path: String) {
        val id = path.removePrefix("/api/records/").removeSuffix("/replay")
        val (stored, fresh) = store.replayRecord(id)
        val identical = stored.result.name == fresh.result.name &&
            stored.version == fresh.version
        writeJson(exchange, 200, jsonObject {
            "record" to Codec.recordToJson(stored)
            "replay" to jsonObject {
                "version" to json(fresh.version)
                "result" to json(fresh.result.name)
                "trace" to fresh.trace
            }
            "resultMatches" to json(identical)
        })
    }

    private fun importBundle(exchange: HttpExchange) {
        val body = readBodyObject(exchange)
        val replace = body["replace"].asBoolean ?: false
        val bundle = Codec.bundleFromJson(body)
        store.importBundle(bundle, replace)
        writeJson(exchange, 200, jsonObject {
            "imported" to json(true)
            "projects" to json(bundle.projects.size)
            "flags" to json(bundle.flags.size)
            "contexts" to json(bundle.contexts.size)
            "records" to json(bundle.records.size)
        })
    }

    private fun saveFlag(exchange: HttpExchange, projectId: String, key: String) {
        val body = readBodyObject(exchange)
        val rules = body["rules"].asArray?.items?.map { Codec.ruleFromJson(it) }
            ?: throw BadRequest("rules array required")
        val prereqs = body["prerequisites"].asArray?.items
            ?.map { Codec.prereqFromJson(it) }.orEmpty()
        val defaultServe = Codec.serveFromJson(body["defaultValue"])
        val stableIdField = body["stableIdField"].asString
            ?: throw BadRequest("stableIdField required")
        val publish = body["publish"].asBoolean ?: false
        val note = body["note"].asString ?: ""
        val flag = store.saveDraft(
            projectId,
            key,
            body["name"].asString ?: key,
            body["description"].asString ?: "",
            rules,
            prereqs,
            defaultServe,
            stableIdField,
            publish,
            note
        )
        writeJson(exchange, 200, Codec.flagToJson(flag))
    }

    private fun parseBatchRequests(body: JsonObject): List<BatchRequest> {
        val contexts = body["contexts"].asArray?.items
            ?.mapNotNull { it.asObject }
            ?: throw BadRequest("contexts array required")
        val flagKeys = body["flagKeys"].asArray?.items
            ?.mapNotNull { it.asString }
            ?: throw BadRequest("flagKeys array required")
        return flagKeys.flatMap { flagKey ->
            contexts.map { ctx ->
                BatchRequest(
                    flagKey = flagKey,
                    contextName = ctx["name"].asString ?: "",
                    context = ctx["context"].asObject
                        ?: ctx
                )
            }
        }
    }

    private fun evaluate(exchange: HttpExchange, projectId: String) {
        val body = readBodyObject(exchange)
        val pinnedVersion = body["version"].asNumber?.long?.toInt()
        val save = body["save"].asBoolean ?: true
        val requests = parseBatchRequests(body)
        val records = store.evaluateBatch(projectId, requests, pinnedVersion, save)
        writeJson(exchange, 200, jsonList(records.map { Codec.recordToJson(it) }))
    }

    private fun compare(exchange: HttpExchange, projectId: String) {
        val body = readBodyObject(exchange)
        val from = body["fromVersion"].asNumber?.long?.toInt()
            ?: throw BadRequest("fromVersion required")
        val to = body["toVersion"].asNumber?.long?.toInt()
        val requests = parseBatchRequests(body)
        val before = store.evaluateBatch(projectId, requests, from, save = false)
        val after = store.evaluateBatch(projectId, requests, to, save = false)
        val rows = before.zip(after).map { (old, new) ->
            jsonObject {
                "flagKey" to json(old.flagKey)
                "contextName" to json(old.contextName)
                "fromVersion" to json(old.version)
                "toVersion" to json(new.version)
                "fromResult" to json(old.result.name)
                "toResult" to json(new.result.name)
                "changed" to json(old.result.name != new.result.name)
                "fromTrace" to old.trace
                "toTrace" to new.trace
            }
        }
        writeJson(exchange, 200, JsonArray(rows))
    }
}
