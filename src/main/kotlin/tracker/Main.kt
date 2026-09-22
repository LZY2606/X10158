package tracker

import java.nio.file.Paths

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5233
    var dataDir = "data"
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--host" -> host = args.getOrElse(index + 1) { host }.also { index++ }
            "--port" -> port = args.getOrElse(index + 1) { port.toString() }.toInt().also { index++ }
            "--data-dir" -> dataDir = args.getOrElse(index + 1) { dataDir }.also { index++ }
        }
        index++
    }
    val store = TrackerStore(Paths.get(dataDir).resolve("tracker-store.json"))
    val server = TrackerHttpServer(store, host, port)
    server.start()
    println("功能开关追踪器已启动：http://$host:$port")
    println("本地持久化文件：${Paths.get(dataDir).resolve("tracker-store.json").toAbsolutePath()}")
}
