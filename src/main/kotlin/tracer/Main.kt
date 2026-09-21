package tracer

import java.io.File

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5233
    var dataFile = "data/flags.json"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args.getOrElse(i + 1) { host }; i += 2 }
            "--port" -> { port = args.getOrElse(i + 1) { "$port" }.toInt(); i += 2 }
            "--data" -> { dataFile = args.getOrElse(i + 1) { dataFile }; i += 2 }
            else -> i += 1
        }
    }
    val projectId = File("data/project.id").let { f ->
        if (f.exists()) f.readText().trim()
        else {
            f.parentFile?.mkdirs()
            java.util.UUID.randomUUID().toString().also { f.writeText(it) }
        }
    }
    val service = FlagService(File(dataFile), projectId)
    val staticDir = listOf(
        File("src/main/resources/static"),
        File("../src/main/resources/static"),
    ).firstOrNull { File(it, "index.html").isFile }
        ?: File("src/main/resources/static")
    Server(service, staticDir, host, port).start()
}
