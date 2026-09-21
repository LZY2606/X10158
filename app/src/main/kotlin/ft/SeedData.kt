package ft

/**
 * Seeds a small demo project the first time the server starts, so opening the
 * browser immediately shows flags, rules, contexts and history.
 */
object SeedData {
    fun seedIfEmpty(service: Service) {
        val p = service.snapshot()
        if (p.flags.isNotEmpty() || p.contexts.isNotEmpty()) return

        service.createFlag(
            key = "beta_checkout",
            name = "Beta 结算",
            VersionDraft(
                type = FlagType.BOOLEAN,
                default = Json.b(false),
                salt = "beta-checkout-v1",
                stableIdentityField = "user_id",
                prerequisiteKey = null,
                prerequisiteExpected = null,
                sensitiveFields = listOf("email", "ssn"),
                rules = listOf(
                    Rule(
                        id = "staff",
                        conditions = listOf(Condition("staff", Operator.EQ, Json.b(true))),
                        value = Json.b(true),
                        rollout = null,
                    ),
                    Rule(
                        id = "rollout-half",
                        conditions = listOf(Condition("country", Operator.EQ, Json.s("JP"))),
                        value = null,
                        rollout = listOf(
                            RolloutSlice("on", 5000),
                            RolloutSlice("off", 5000),
                        ),
                    ),
                ),
            ),
        )

        service.createFlag(
            key = "pay_with_points",
            name = "积分支付",
            VersionDraft(
                type = FlagType.STRING,
                default = Json.s("off"),
                salt = "points-v2",
                stableIdentityField = "user_id",
                prerequisiteKey = "beta_checkout",
                prerequisiteExpected = Json.b(true),
                sensitiveFields = listOf("email"),
                rules = listOf(
                    Rule(
                        id = "vip-ui",
                        conditions = listOf(
                            Condition("tier", Operator.IN, JArr(listOf(Json.s("gold"), Json.s("platinum")))),
                        ),
                        value = null,
                        rollout = listOf(
                            RolloutSlice("full", 2500),
                            RolloutSlice("partial", 2500),
                        ),
                    ),
                ),
            ),
        )

        service.saveContext(
            "员工 akira",
            JObj(
                listOf(
                    "user_id" to Json.s("akira-001"),
                    "country" to Json.s("JP"),
                    "staff" to Json.b(true),
                    "tier" to Json.s("platinum"),
                    "email" to Json.s("akira@example.com"),
                )
            ),
        )
        service.saveContext(
            "普通用户 yuki",
            JObj(
                listOf(
                    "user_id" to Json.s("yuki-777"),
                    "country" to Json.s("JP"),
                    "staff" to Json.b(false),
                    "tier" to Json.s("silver"),
                    "email" to Json.s("yuki@example.com"),
                )
            ),
        )
        service.saveContext(
            "海外用户（国家缺失）",
            JObj(listOf("user_id" to Json.s("tourist-9"), "staff" to Json.b(false))),
        )

        // one saved history record, bound to v1
        service.evaluateBatch(
            flagKeys = listOf("beta_checkout", "pay_with_points"),
            context = JObj(
                listOf(
                    "user_id" to Json.s("yuki-777"),
                    "country" to Json.s("JP"),
                    "staff" to Json.b(false),
                    "tier" to Json.s("silver"),
                    "email" to Json.s("yuki@example.com"),
                )
            ),
            saveRecord = true,
            contextName = "普通用户 yuki",
        )
    }
}
