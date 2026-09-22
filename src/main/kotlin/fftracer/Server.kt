package fftracer

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun Application.module(store: Store) {
    install(ContentNegotiation) { json(json) }

    fun projectOr404(id: String): Project =
        store.getProject(id) ?: throw NoSuchElementException("project $id not found")

    routing {
        get("/") {
            val html = this::class.java.classLoader.getResource("static/index.html")!!.readText()
            call.respondText(html, ContentType.Text.Html)
        }

        route("/api") {
            get("/projects") { call.respond(store.listProjects()) }
            post("/projects") {
                val body = call.receive<JsonObject>()
                call.respond(store.createProject(body["name"]?.jsonPrimitive?.content ?: "项目"))
            }

            route("/projects/{pid}") {
                get("/flags") {
                    projectOr404(call.parameters["pid"]!!)
                    call.respond(store.listFlags(call.parameters["pid"]!!))
                }
                post("/flags") {
                    val pid = call.parameters["pid"]!!
                    projectOr404(pid)
                    val body = call.receive<JsonObject>()
                    val key = body["key"]!!.jsonPrimitive.content
                    val default = body["defaultValue"] ?: JsonPrimitive(false)
                    try {
                        call.respond(store.createFlag(pid, key, default))
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message))
                    }
                }
                get("/flags/{key}") {
                    val flag = store.getFlag(call.parameters["pid"]!!, call.parameters["key"]!!)
                    if (flag == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found"))
                    else call.respond(flag)
                }
                post("/flags/{key}/publish") {
                    val pid = call.parameters["pid"]!!; val key = call.parameters["key"]!!
                    val body = call.receive<JsonObject>()
                    try {
                        val updated = store.publishVersion(pid, key) { head ->
                            head.copy(
                                defaultValue = body["defaultValue"] ?: head.defaultValue,
                                rules = body["rules"]?.let { json.decodeFromJsonElement(it) } ?: head.rules,
                                prerequisites = body["prerequisites"]?.let { json.decodeFromJsonElement(it) }
                                    ?: head.prerequisites,
                            )
                        }
                        call.respond(updated)
                    } catch (e: CycleException) {
                        call.respond(HttpStatusCode.Conflict, mapOf(
                            "error" to "CYCLE", "path" to e.path.joinToString(" -> ")))
                    } catch (e: NoSuchElementException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
                    }
                }

                post("/evaluate") {
                    val pid = call.parameters["pid"]!!
                    val project = projectOr404(pid)
                    val body = call.receive<JsonObject>()
                    val flagKey = body["flagKey"]!!.jsonPrimitive.content
                    val version = body["version"]?.jsonPrimitive?.int
                    val ctx = resolveContext(store, pid, body)
                    val result = Evaluator(project.secret, store.snapshot(pid),
                        ctx.sensitiveAttributes.toSet()).evaluate(flagKey, ctx, version)
                    if (body["save"]?.jsonPrimitive?.boolean == true) {
                        store.saveRecord(EvalRecord(
                            id = "r" + UUID.randomUUID().toString().take(10),
                            projectId = pid, flagKey = flagKey, version = result.version,
                            contextId = ctx.id, result = result,
                            createdAt = System.currentTimeMillis()))
                    }
                    call.respond(result)
                }

                post("/batch-evaluate") {
                    val pid = call.parameters["pid"]!!
                    val project = projectOr404(pid)
                    val body = call.receive<JsonObject>()
                    val ctx = resolveContext(store, pid, body)
                    val snapshot = store.snapshot(pid) // one fixed snapshot for the whole batch
                    val keys = body["flagKeys"]?.jsonArray?.map { it.jsonPrimitive.content }
                        ?: snapshot.keys.toList()
                    val evaluator = Evaluator(project.secret, snapshot, ctx.sensitiveAttributes.toSet())
                    call.respond(keys.associateWith { evaluator.evaluate(it, ctx) })
                }

                get("/compare") {
                    val pid = call.parameters["pid"]!!
                    val project = projectOr404(pid)
                    val flagKey = call.request.queryParameters["flag"]!!
                    val v1 = call.request.queryParameters["v1"]!!.toInt()
                    val v2 = call.request.queryParameters["v2"]!!.toInt()
                    val snapshot = store.snapshot(pid)
                    val rows = store.listContexts(pid).map { ctx ->
                        val ev = Evaluator(project.secret, snapshot, ctx.sensitiveAttributes.toSet())
                        mapOf(
                            "contextId" to JsonPrimitive(ctx.id),
                            "v1" to json.encodeToJsonElement(EvalResult.serializer(),
                                ev.evaluate(flagKey, ctx, v1)),
                            "v2" to json.encodeToJsonElement(EvalResult.serializer(),
                                ev.evaluate(flagKey, ctx, v2)),
                        )
                    }
                    call.respond(rows)
                }

                get("/contexts") { call.respond(store.listContexts(call.parameters["pid"]!!)) }
                post("/contexts") {
                    val pid = call.parameters["pid"]!!
                    projectOr404(pid)
                    val body = call.receive<JsonObject>()
                    val ctx = EvalContext(
                        id = body["id"]?.jsonPrimitive?.content
                            ?: "c" + UUID.randomUUID().toString().take(8),
                        projectId = pid,
                        attributes = body["attributes"]?.jsonObject?.toMap() ?: emptyMap(),
                        sensitiveAttributes = body["sensitiveAttributes"]?.jsonArray
                            ?.map { it.jsonPrimitive.content } ?: emptyList(),
                    )
                    call.respond(store.saveContext(ctx))
                }

                get("/records") {
                    call.respond(store.listRecords(call.parameters["pid"]!!,
                        call.request.queryParameters["flag"]))
                }
                get("/records/{rid}") {
                    val rec = store.getRecord(call.parameters["pid"]!!, call.parameters["rid"]!!)
                    if (rec == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found"))
                    else call.respond(rec)
                }
                get("/records/{rid}/replay") {
                    val pid = call.parameters["pid"]!!
                    val project = projectOr404(pid)
                    val rec = store.getRecord(pid, call.parameters["rid"]!!)
                    if (rec == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found"))
                    } else {
                        val ctx = store.getContext(pid, rec.contextId)
                        if (ctx == null) {
                            call.respond(mapOf("record" to json.encodeToJsonElement(
                                EvalRecord.serializer(), rec)))
                        } else {
                            val replay = Evaluator(project.secret, store.snapshot(pid),
                                ctx.sensitiveAttributes.toSet())
                                .evaluate(rec.flagKey, ctx, rec.version)
                            call.respond(mapOf(
                                "record" to json.encodeToJsonElement(EvalRecord.serializer(), rec),
                                "replay" to json.encodeToJsonElement(EvalResult.serializer(), replay),
                            ))
                        }
                    }
                }

                get("/export") {
                    projectOr404(call.parameters["pid"]!!)
                    call.respond(store.export(call.parameters["pid"]!!))
                }
                post("/import") {
                    val body = call.receive<StoreData>()
                    store.import(body)
                    call.respond(mapOf("imported" to body.projects.map { it.id }))
                }
            }
        }
    }
}

private fun resolveContext(store: Store, pid: String, body: JsonObject): EvalContext {
    val ctxId = body["contextId"]?.jsonPrimitive?.content
    if (ctxId != null) {
        return store.getContext(pid, ctxId)
            ?: throw NoSuchElementException("context $ctxId not found")
    }
    return EvalContext(
        id = "inline", projectId = pid,
        attributes = body["attributes"]?.jsonObject?.toMap() ?: emptyMap(),
        sensitiveAttributes = body["sensitiveAttributes"]?.jsonArray
            ?.map { it.jsonPrimitive.content } ?: emptyList(),
    )
}

