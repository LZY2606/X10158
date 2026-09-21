package tracker

import java.nio.file.Path

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5233
    var dataDir = "data"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> host = args[++i]
            "--port" -> port = args[++i].toInt()
            "--data-dir" -> dataDir = args[++i]
            else -> System.err.println("忽略未知参数: ${args[i]}")
        }
        i++
    }
    val store = Store(Path.of(dataDir))
    Server(store, host, port).start()
    Thread.currentThread().join()
}
