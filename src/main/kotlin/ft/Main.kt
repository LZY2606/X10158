package ft

import java.nio.file.Paths

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5233
    var dataDir = "data"

    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--host" -> host = args[++index]
            "--port" -> port = args[++index].toInt()
            "--data" -> dataDir = args[++index]
            "--help", "-h" -> {
                println("用法: ./gradlew run --args='--host 127.0.0.1 --port 5233'")
                return
            }
            else -> throw IllegalArgumentException("未知参数: ${args[index]}")
        }
        index++
    }

    val store = Store(Paths.get(dataDir))
    if (store.listProjects().isEmpty()) {
        seedDemo(store)
    }
    WebServer(store, host, port).start()
}

/** A small demo dataset so the UI is useful on first launch. */
private fun seedDemo(store: Store) {
    val project = store.createProject(
        name = "示例项目",
        sensitiveFields = listOf("user.email", "user.phone", "user.pii.*")
    )

    store.saveDraft(
        projectId = project.id,
        key = "new_checkout",
        name = "新版结算页",
        description = "内部员工直接开启；VIP 用户 50% 分流；其余默认关闭。",
        rules = listOf(
            Rule(
                id = "employees",
                name = "员工全量",
                conditions = listOf(
                    Condition("user.tier", Operator.EQ, JsonString("employee"))
                ),
                serve = ServeValue.ON
            ),
            Rule(
                id = "vip_rollout",
                name = "VIP 50% 分流",
                conditions = listOf(
                    Condition("user.tier", Operator.EQ, JsonString("vip"))
                ),
                rollout = listOf(
                    RolloutClause(ServeValue.ON, 5000),
                    RolloutClause(ServeValue.OFF, 5000)
                ),
                fallbackServe = ServeValue.OFF,
                salt = "checkout_v1"
            )
        ),
        prerequisites = emptyList(),
        defaultValue = ServeValue.OFF,
        stableIdField = "user.id",
        publish = true,
        note = "初始发布"
    )

    store.saveDraft(
        projectId = project.id,
        key = "payments_beta",
        name = "支付 Beta",
        description = "依赖 new_checkout 开启后才允许进入。",
        rules = listOf(
            Rule(
                id = "beta_users",
                name = "Beta 用户",
                conditions = listOf(
                    Condition("user.beta", Operator.EQ, JsonBoolean(true))
                ),
                serve = ServeValue("blue")
            )
        ),
        prerequisites = listOf(
            Prerequisite(
                flagKey = "new_checkout",
                anyOf = listOf(ServeValue.ON),
                gateServe = ServeValue.OFF
            )
        ),
        defaultValue = ServeValue.OFF,
        stableIdField = "user.id",
        publish = true,
        note = "初始发布"
    )

    store.saveContext(
        "员工 Alice",
        parseJson("""{"user":{"id":"alice","tier":"employee","email":"alice@example.com"}}""") as JsonObject
    )
    store.saveContext(
        "VIP Bob",
        parseJson("""{"user":{"id":"bob","tier":"vip","beta":true}}""") as JsonObject
    )
    store.saveContext(
        "普通用户（字段缺失）",
        parseJson("""{"user":{"id":"carol"}}""") as JsonObject
    )
}
