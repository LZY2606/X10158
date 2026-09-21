package tracer

import java.nio.file.Path

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5233
    var data = Path.of("data", "store.json")
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> host = args[++i]
            "--port" -> port = args[++i].toInt()
            "--data" -> data = Path.of(args[++i])
        }
        i++
    }
    val store = Store(data)
    Server(store, host, port).start()
}
