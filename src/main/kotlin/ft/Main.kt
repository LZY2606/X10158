package ft

import java.nio.file.Paths

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5233
    var dataPath = "data/project.json"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--data" -> { dataPath = args[++i] }
            "--seed-demo" -> { dataPath = args.getOrNull(++i) ?: dataPath }
            else -> { System.err.println("Unknown argument: ${args[i]}"); i++; continue }
        }
        i++
    }

    val store = FileStore(Paths.get(dataPath))
    val service = Service(store)
    DemoData.seedIfEmpty(service)

    val server = WebServer(service, host, port)
    server.start()
    println("功能开关追踪器 (feature flag tracker) listening on http://$host:$port")
    println("Data file: $dataPath")

    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}
