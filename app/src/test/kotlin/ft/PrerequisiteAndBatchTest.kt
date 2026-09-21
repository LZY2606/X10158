package ft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrerequisiteAndBatchTest {

    @Test fun `direct self prerequisite rejected`() {
        val (svc, _) = Fixtures.service()
        svc.createFlag("a", "a", Fixtures.draftBoolean(emptyList(), default = true))
        val ex = assertThrows(AppException::class.java) {
            svc.publishVersion("a", Fixtures.draftBoolean(
                rules = emptyList(), default = false,
                prerequisiteKey = "a", prerequisiteExpected = Json.b(true),
            ))
        }
        assertEquals(422, ex.status)
        assertTrue(ex.message!!.contains("cycle"))
    }

    @Test fun `two-flag cycle rejected with path`() {
        val (svc, _) = Fixtures.service()
        svc.createFlag("a", "a", Fixtures.draftBoolean(
            rules = listOf(Fixtures.valueRule("on", emptyList(), true))))
        svc.createFlag("b", "b", Fixtures.draftBoolean(
            rules = listOf(Fixtures.valueRule("on", emptyList(), true)), default = false,
            prerequisiteKey = "a", prerequisiteExpected = Json.b(true),
        ))
        // a -> b -> a
        val ex = assertThrows(AppException::class.java) {
            svc.publishVersion("a", Fixtures.draftBoolean(
                rules = emptyList(), default = true,
                prerequisiteKey = "b", prerequisiteExpected = Json.b(true),
            ))
        }
        assertTrue(ex.message!!.contains("a -> b -> a") || ex.message!!.contains("b -> a -> b"), ex.message)
    }

    @Test fun `prerequisite failure blocks value and trace shows actual vs expected`() {
        val (svc, _) = Fixtures.service()
        svc.createFlag("base", "base", Fixtures.draftBoolean(emptyList(), default = false))
        svc.createFlag("child", "child", Fixtures.draftBoolean(
            rules = listOf(Fixtures.valueRule("always", emptyList(), true)),
            prerequisiteKey = "base", prerequisiteExpected = Json.b(true),
        ))
        val r = svc.evaluate("child", Fixtures.ctx())
        assertEquals(false, (r.value as JBool).value)
        val pre = findStep(r.trace, "prerequisite")
        assertEquals("failed", pre.outcome)
        assertEquals(false, (pre.detail["actual"] as JBool).value)
        assertEquals(true, (pre.detail["expected"] as JBool).value)
    }

    @Test fun `batch uses one snapshot - publish during batch cannot mix versions`() {
        val (svc, _) = Fixtures.service()
        svc.createFlag("a", "a", Fixtures.draftBoolean(
            rules = listOf(Fixtures.valueRule("v1", emptyList(), true)), default = false))
        svc.createFlag("b", "b", Fixtures.draftBoolean(
            rules = listOf(Fixtures.valueRule("on", emptyList(), true)), default = false,
            prerequisiteKey = "a", prerequisiteExpected = Json.b(true),
        ))

        // Freeze a at v1 (true) via explicit snapshot, while current a becomes v2 (false).
        val frozen = svc.evaluateBatch(listOf("a", "b"), Fixtures.ctx())
        assertEquals(1, frozen.snapshotVersions["a"])

        svc.publishVersion("a", Fixtures.draftBoolean(
            rules = listOf(Fixtures.valueRule("v2", emptyList(), false)), default = false))

        // batch with pinned v1 of a: b's prerequisite must still see true
        val pinned = svc.evaluateBatch(
            listOf("a", "b"), Fixtures.ctx(),
            pinnedVersions = mapOf("a" to 1),
        )
        val aRes = pinned.results.first { it.first == "a" }.second
        val bRes = pinned.results.first { it.first == "b" }.second
        assertEquals(true, (aRes.value as JBool).value)
        assertEquals(true, (bRes.value as JBool).value)

        // current (unpinned) batch now resolves both to v2/current
        val live = svc.evaluateBatch(listOf("a", "b"), Fixtures.ctx())
        assertEquals(2, live.snapshotVersions["a"])
        val bLive = live.results.first { it.first == "b" }.second
        assertEquals(false, (bLive.value as JBool).value)
    }

    @Test fun `snapshot is captured once per batch and reported in response`() {
        val (svc, _) = Fixtures.service()
        svc.createFlag("x", "x", Fixtures.draftBoolean(emptyList(), default = true))
        val batch = svc.evaluateBatch(listOf("x"), Fixtures.ctx())
        assertEquals(mapOf("x" to 1), batch.snapshotVersions)
    }
}
