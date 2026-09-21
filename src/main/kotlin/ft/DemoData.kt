package ft

/** Seeds a small, illustrative project on first run (idempotent). */
object DemoData {
    fun seedIfEmpty(service: Service) {
        if (service.listFlags().isNotEmpty()) return

        service.setProjectSettings("demo-project", listOf("email", "ssn"))

        // 1) Kill switch prerequisite
        service.createFlag(
            "checkout_enabled",
            Draft(
                name = "Checkout master switch",
                description = "Global kill switch for checkout features.",
                salt = "checkout-v1",
                stableIdField = "id",
                prerequisites = emptyList(),
                rules = listOf(
                    Rule("r-on", "internal testers always on",
                        listOf(Condition("tier", "eq", "internal")),
                        Outcome.On, null),
                    Rule("r-off", "disabled regions",
                        listOf(Condition("region", "in", listOf("blocked-a", "blocked-b"))),
                        Outcome.Off, null)
                ),
                defaultValue = Outcome.On
            )
        )
        service.publish("checkout_enabled")

        // 2) Percentage rollout flag with a prerequisite
        service.createFlag(
            "new_checkout",
            Draft(
                name = "New checkout experience",
                description = "50% stable rollout, gated by the checkout master switch.",
                salt = "new-checkout-2026",
                stableIdField = "id",
                prerequisites = listOf(Prerequisite("checkout_enabled", Outcome.On)),
                rules = listOf(
                    Rule("r-employees", "Employees get variant beta",
                        listOf(Condition("tier", "eq", "employee")),
                        Outcome.Variant("beta"), null),
                    Rule("r-rollout", "Stable 50/50 rollout",
                        listOf(Condition("region", "present")),
                        null,
                        listOf(
                            Slice(Outcome.Variant("beta"), 5000),
                            Slice(Outcome.Off, 5000)
                        )
                    )
                ),
                defaultValue = Outcome.Off
            )
        )
        service.publish("new_checkout")

        // 3) Type-strictness demo: string "18" must not equal number 18.
        service.createFlag(
            "age_gate",
            Draft(
                name = "Age gate",
                description = "Demonstrates missing vs null and strict string/number compare.",
                salt = "age-gate",
                stableIdField = "id",
                prerequisites = emptyList(),
                rules = listOf(
                    Rule("r-adult", "age >= 18 (number)",
                        listOf(Condition("age", "ge", 18)),
                        Outcome.On, null),
                    Rule("r-unknown", "age explicitly null",
                        listOf(Condition("age", "eq", null)),
                        Outcome.Variant("unknown"), null)
                ),
                defaultValue = Outcome.Off
            )
        )
        service.publish("age_gate")

        service.saveContext("Ada (internal, id 42)", mapOf(
            "id" to "user-42", "tier" to "internal", "region" to "us", "age" to 31, "email" to "ada@example.com"
        ))
        service.saveContext("Bob (id 7, region eu)", mapOf(
            "id" to "user-7", "tier" to "free", "region" to "eu", "age" to 24
        ))
        service.saveContext("Carol (age null)", mapOf(
            "id" to "user-9", "tier" to "free", "region" to "us", "age" to null
        ))
        service.saveContext("Dan (age missing)", mapOf(
            "id" to "user-12", "tier" to "free", "region" to "us"
        ))
        service.saveContext("Eve (age as string \"18\")", mapOf(
            "id" to "user-55", "tier" to "free", "region" to "us", "age" to "18"
        ))
    }
}
