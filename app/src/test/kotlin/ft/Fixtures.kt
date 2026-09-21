package ft

import java.nio.file.Files

object Fixtures {
    fun service(): Pair<Service, Store> {
        val dir = Files.createTempDirectory("fft-test")
        val store = Store(dir.resolve("store.json"))
        return Service(store) to store
    }

    fun ctx(vararg pairs: Pair<String, Any?>): JObj {
        val list = pairs.map { (k, v) -> k to any(v) }
        return JObj(list)
    }

    fun any(v: Any?): JsonValue = when (v) {
        null -> JNull
        is Boolean -> Json.b(v)
        is Int -> Json.n(v)
        is Long -> Json.n(v)
        is Double -> Json.n(v)
        is String -> Json.s(v)
        else -> error("unsupported fixture value $v")
    }

    fun draftBoolean(
        rules: List<Rule>,
        default: Boolean = false,
        salt: String = "salt-x",
        identity: String = "user_id",
        prerequisiteKey: String? = null,
        prerequisiteExpected: JsonValue? = null,
        sensitive: List<String> = emptyList(),
    ) = VersionDraft(
        type = FlagType.BOOLEAN,
        default = Json.b(default),
        salt = salt,
        stableIdentityField = identity,
        prerequisiteKey = prerequisiteKey,
        prerequisiteExpected = prerequisiteExpected,
        sensitiveFields = sensitive,
        rules = rules,
    )

    fun valueRule(id: String, conds: List<Condition>, value: Boolean) =
        Rule(id, conds, Json.b(value), null)

    fun rolloutRule(id: String, conds: List<Condition>, slices: List<Pair<String, Int>>) =
        Rule(id, conds, null, slices.map { RolloutSlice(it.first, it.second) })

    fun cond(field: String, op: Operator, arg: JsonValue) = Condition(field, op, arg)
}
