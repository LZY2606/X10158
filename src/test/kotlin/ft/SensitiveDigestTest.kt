package ft

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveDigestTest {
    private fun ruleOnEmail() = Rule(
        "r", "email domain",
        listOf(Condition("user.email", Operator.ENDS_WITH, JsonString("@acme.test"))),
        serve = ServeValue.ON
    )

    @Test
    fun `sensitive values are redacted but still traceable within a project`() {
        val store = tempStore()
        val project = store.testProject(sensitive = listOf("user.email"))
        store.publishSimple(project.id, "f", listOf(ruleOnEmail()))
        val ctx = json("""{"user":{"id":"u","email":"alice@acme.test"}}""")

        val r1 = store.evalOnce(project.id, "f", ctx)
        val r2 = store.evalOnce(project.id, "f", ctx)
        val entry1 = r1.trace["fieldReads"].asObject!!.get("user.email").asObject!!
        assertTrue(entry1["sensitive"].asBoolean!!)
        assertTrue(entry1.members.keys.none { it == "value" })

        // Same input -> same digest (proves equality) in repeated evals.
        val digest1 = entry1["digest"].asString!!
        val digest2 = r2.trace["fieldReads"].asObject!!.get("user.email").asObject!!
            .get("digest").asString!!
        assertTrue(digest1 == digest2)

        // Different input -> different digest.
        val otherCtx = json("""{"user":{"id":"u2","email":"bob@acme.test"}}""")
        val other = store.evalOnce(project.id, "f", otherCtx)
        val digestOther = other.trace["fieldReads"].asObject!!.get("user.email").asObject!!
            .get("digest").asString!!
        assertFalse(digest1 == digestOther)
    }

    @Test
    fun `digests are not linkable across projects`() {
        val store = tempStore()
        val p1 = store.testProject(sensitive = listOf("user.email"))
        val p2 = store.testProject(sensitive = listOf("user.email"))
        assertFalse(p1.digestSalt == p2.digestSalt)

        store.publishSimple(p1.id, "f", listOf(ruleOnEmail()))
        store.publishSimple(p2.id, "f", listOf(ruleOnEmail()))
        val ctx = json("""{"user":{"id":"u","email":"alice@acme.test"}}""")

        val d1 = store.evalOnce(p1.id, "f", ctx).trace["fieldReads"].asObject!!
            .get("user.email").asObject!!.get("digest").asString!!
        val d2 = store.evalOnce(p2.id, "f", ctx).trace["fieldReads"].asObject!!
            .get("user.email").asObject!!.get("digest").asString!!
        assertFalse(d1 == d2, "same raw value across projects must not share a digest")
    }

    @Test
    fun `sensitive stable id is digested in the bucketing hash input`() {
        val store = tempStore()
        val project = store.testProject(sensitive = listOf("user.id"))
        store.publishSimple(
            project.id, "f",
            rules = listOf(
                Rule(
                    "r", "rollout", emptyList(),
                    rollout = listOf(RolloutClause(ServeValue("blue"), 5000)),
                    fallbackServe = ServeValue.OFF,
                    salt = "s"
                )
            ),
            stableIdField = "user.id"
        )
        val trace = store.evalOnce(
            project.id, "f", json("""{"user":{"id":"secret-id"}}""")
        ).trace
        val rollout = trace["steps"].asArray!!.items.first().asObject!!.get("rollout").asObject!!
        val hashInput = rollout["hashInput"].asString!!
        assertFalse(hashInput.contains("secret-id"), "raw stable id leaked: $hashInput")
        assertTrue(hashInput.startsWith("sha256hmac:"))
    }

    @Test
    fun `wildcard sensitive declaration covers nested fields`() {
        val store = tempStore()
        val project = store.testProject(sensitive = listOf("pii.*"))
        store.publishSimple(
            project.id, "f",
            listOf(
                Rule(
                    "r", "pii",
                    listOf(Condition("pii.ssn", Operator.EQ, JsonString("x"))),
                    serve = ServeValue.ON
                )
            )
        )
        val trace = store.evalOnce(
            project.id, "f", json("""{"pii":{"ssn":"x"}}""")
        ).trace
        assertTrue(
            trace["fieldReads"].asObject!!.get("pii.ssn").asObject!!.get("sensitive").asBoolean!!
        )
    }
}
