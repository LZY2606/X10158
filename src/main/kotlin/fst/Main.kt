package fst

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5233
    var dataDir: String? = null
    var webDir: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> host = args[++i]
            "--port" -> port = args[++i].toInt()
            "--data" -> dataDir = args[++i]
            "--web" -> webDir = args[++i]
            "--help", "-h" -> {
                println("用法: ./gradlew run --args='--host 127.0.0.1 --port 5233 [--data ./data] [--web ./src/main/resources/web]")
                exitProcess(0)
            }
            else -> throw IllegalArgumentException("未知参数 ${args[i]}")
        }
        i++
    }
    val dataPath = Paths.get(dataDir ?: "data")
    val webPath: Path = Paths.get(webDir ?: "src/main/resources/web")
    val store = Store(dataPath)
    val server = Server(store, webPath)
    server.start(host, port)
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}
