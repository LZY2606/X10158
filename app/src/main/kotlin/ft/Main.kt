package ft

import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5233
    var dataPath = System.getenv("FFT_DATA") ?: "data/flagtracker.json"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--data" -> { dataPath = args[++i] }
            "--help", "-h" -> {
                println("Usage: run --host 127.0.0.1 --port 5233 [--data path.json]")
                exitProcess(0)
            }
            else -> System.err.println("unknown argument: ${args[i]}")
        }
        i++
    }
    val store = Store(Path.of(dataPath))
    val service = Service(store)
    SeedData.seedIfEmpty(service)
    ApiServer(service, host, port).start()
}
