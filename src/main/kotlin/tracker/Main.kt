package tracker

import java.nio.file.Paths

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5233
    var dataFile = "data/store.json"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args.getOrElse(i + 1) { host }; i += 2 }
            "--port" -> { port = args.getOrElse(i + 1) { port.toString() }.toInt(); i += 2 }
            "--data" -> { dataFile = args.getOrElse(i + 1) { dataFile }; i += 2 }
            else -> i += 1
        }
    }
    val store = Store(Paths.get(dataFile))
    val server = ApiServer(store, host, port)
    server.start()
    println("功能开关追踪器 已启动: http://$host:${server.actualPort}")
    Thread.currentThread().join()
}
