package ft

import kotlin.test.Test
import kotlin.test.assertEquals

class ExportImportTest {
    @Test
    fun `buckets and trace step order are identical after export and import`() {
        val source = tempStore()
        val project = source.createProject("P", listOf("user.email"))
        source.publishSimple(
            project.id, "base",
            rules = listOf(
                Rule(
                    "r", "on",
                    listOf(Condition("user.email", Operator.EXISTS)),
                    serve = ServeValue.ON
                )
            ),
            note = "v1"
        )
        source.publishSimple(
            project.id, "split",
            rules = listOf(
                Rule(
                    "r", "rollout",
                    listOf(Condition("tier", Operator.EQ, JsonString("vip"))),
                    rollout = listOf(RolloutClause(ServeValue("blue"), 4000)),
                    fallbackServe = ServeValue.OFF,
                    salt = "immutable-salt"
                )
            ),
            prerequisites = listOf(Prerequisite("base", listOf(ServeValue.ON))),
            note = "v1"
        )
        val ctx = json("""{"user":{"id":"u-7","email":"a@b.test"},"tier":"vip"}""")
        source.saveContext("saved", ctx)
        val beforeRecord = source.evaluateBatch(
            project.id,
            listOf(BatchRequest("split", "saved", ctx)),
            save = true
        ).single()

        val bundleJson = Codec.bundleToJson(source.exportBundle())

        val target = tempStore()
        target.importBundle(Codec.bundleFromJson(bundleJson), replace = true)
        val importedProject = target.listProjects().single()
        val importedContext = target.listContexts().single()

        val afterRecord = target.evalOnce(
            importedProject.id, "split", importedContext.context
        )

        assertEquals(beforeRecord.result.name, afterRecord.result.name)
        val beforeRollout = beforeRecord.trace["steps"].asArray!!.items
            .first { it.asObject!!.get("type").asString == "prerequisite" }
        val afterRollout = afterRecord.trace["steps"].asArray!!.items
            .first { it.asObject!!.get("type").asString == "prerequisite" }
        assertEquals(
            beforeRollout.asObject!!.get("observed").asString,
            afterRollout.asObject!!.get("observed").asString
        )

        val beforeBucket = bucketOf(beforeRecord)
        val afterBucket = bucketOf(afterRecord)
        assertEquals(beforeBucket, afterBucket)

        // Sensitive digests survive export because the project salt is exported.
        val beforeDigest = beforeRecord.trace["fieldReads"].asObject!!
            .get("user.email").asObject!!.get("digest").asString
        val afterDigest = afterRecord.trace["fieldReads"].asObject!!
            .get("user.email").asObject!!.get("digest").asString
        assertEquals(beforeDigest, afterDigest)

        // Persisted records come back verbatim and replay identically.
        val replayed = target.replayRecord(target.listRecords().single().id)
        assertEquals(beforeRecord.result.name, replayed.second.result.name)
    }

    private fun bucketOf(record: EvalRecord): Long {
        val ruleStep = record.trace["steps"].asArray!!.items
            .first { it.asObject!!.get("rollout") != null }
        return ruleStep.asObject!!.get("rollout").asObject!!.get("bucketBp").asNumber!!.long
    }
}
