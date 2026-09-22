package fftracer

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File

fun main(args: Array<String>) {
    fun arg(name: String, default: String): String {
        val i = args.indexOf(name)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else default
    }
    val host = arg("--host", "127.0.0.1")
    val port = arg("--port", "5233").toInt()
    val store = Store(File(System.getenv("FFTRACER_DATA") ?: "data/store.json"))
    if (store.listProjects().isEmpty()) store.createProject("演示项目")
    println("功能开关追踪器 listening on http://$host:$port")
    embeddedServer(CIO, port = port, host = host) { module(store) }.start(wait = true)
}
