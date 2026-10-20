package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSourceCompilerReportTest {
    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `each report shares complete links while retaining every original record reference`(
        reports: Int,
        @TempDir out: File,
    ) {
        val origin = buildJsonObject { put("entry", "Cold.class") }
        val audit = JvmSavedJson.parse(
            """{
                "record":"class-audit","originalSyntaxClassOrdinal":0,"internalName":"sample/Cold",
                "originalClassOrigin":{"entry":"Cold.class"},"originalMethodIndexOrder":[0],
                "noFormalAlternativeMethodIndexes":[0],"allOriginalMethodFormalChecks":[]
            }""",
        ).jsonObject
        val candidates = listOf(true, false).map { unknown ->
            buildJsonObject {
                put("studySourceHead", "head")
                put("syntaxClassOrdinal", 0)
                put("methodIndex", 0)
                put("originalSyntaxOrigin", origin)
                put("unknown", unknown)
            }
        }
        val observations = candidates.map { candidate ->
            buildJsonObject {
                put("record", "compiler-observation-candidates")
                put("methodCandidates", buildJsonObject {
                    put("method", buildJsonObject {
                        put("observedMethodCopiedVerbatim", "sample.Cold.pick(I)V")
                        put("methodIdentityParsed", true)
                        put("declarationCandidates", JsonArray(listOf(candidate)))
                    })
                })
            }
        }
        val rows = listOf(
            buildJsonObject { put("record", "schema"); put("unknown", "retained in original input") },
            buildJsonObject { put("record", "declaration-candidate-context"); put("unknown", JsonNull) },
            observations[0], observations[0], observations[1],
            buildJsonObject { put("record", "candidate-report-end") },
        )
        val raw = rows.map { (it.toString() + "\n").encodeToByteArray() }
        val input = buildJsonObject { put("id", "whole-input"); put("unknown", "retained") }
        val proof = buildJsonObject { put("records", rows.size) }

        JvmSourceCompilerIndex(File(out, "index.sqlite")).use { index ->
            index.add("binding", "head", "audit.gz", 2, audit, JsonObject(emptyMap()))
            index.finish()
            repeat(reports) { arm ->
                val file = File(out, "links-$arm.jsonl.gz")
                val output = JvmSavedReport(file, JvmSavedReportBudget())
                val writer = JvmSourceCompilerReport(index, "binding", input, "candidates.gz", proof, output)
                raw.forEachIndexed { ordinal, bytes ->
                    writer.accept(ordinal.toLong(), bytes, if (ordinal in setOf(1, 5)) null else rows[ordinal])
                }
                writer.finish()
                output.verify()

                val actual = GZIPInputStream(file.inputStream()).bufferedReader().use { reader ->
                    reader.readLines().map { JvmSavedJson.parse(it).jsonObject }
                }
                assertEquals(input, actual.first()["originalInputCatalogEntryCopiedVerbatim"])
                assertEquals(proof, actual.first()["originalReportProofCopiedVerbatim"])
                val denied = actual.first().getValue("deniedProofFlags").jsonObject
                (SOURCE_PROOF_FLAGS + COHORT_PROOF_FLAGS).forEach {
                    assertEquals(JsonPrimitive(false), denied[it])
                }
                val originals = actual.filter { it["record"] == JsonPrimitive("original-compiler-source-link") }
                assertEquals(raw.indices.map { JsonPrimitive(it) }, originals.map { it["originalRawRecordOrdinal"] })
                assertEquals(raw.map(JvmSavedReports::identity), originals.map { it["originalRawRecordIdentity"] })
                assertEquals(
                    listOf(JsonPrimitive(0), JsonNull, JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive(3), JsonNull),
                    originals.map { it["originalExpandedRecordOrdinal"] },
                )
                val definitions = actual.filter { it["record"] == JsonPrimitive("source-audit-link-definition") }
                assertEquals(listOf(JsonPrimitive(0), JsonPrimitive(1)), definitions.map { it["reference"] })
                assertEquals(candidates, definitions.map {
                    it.getValue("wholeSourceAuditLink").jsonObject["originalDeclarationCandidateCopiedVerbatim"]
                })
                val references = originals.slice(2..4).map {
                    it.getValue("sourceAuditLinkReferences").jsonObject.getValue("method").jsonObject
                        .getValue("allOriginalDeclarationCandidateSourceAuditLinkReferences").jsonArray
                }
                assertEquals(listOf(0, 0, 1).map { JsonArray(listOf(JsonPrimitive(it))) }, references)
                assertEquals(JsonPrimitive(6), actual.last()["originalRawRecords"])
                assertEquals(JsonPrimitive(4), actual.last()["originalExpandedRecords"])
                assertEquals(JsonPrimitive(2), actual.last()["sourceLinkDefinitions"])
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "reordered", "after-end"])
    fun `original record coverage cannot admit a gap or a late record`(variant: String, @TempDir out: File) {
        JvmSourceCompilerIndex(File(out, "index.sqlite")).use { index ->
            JvmSavedReport(File(out, "links.jsonl.gz"), JvmSavedReportBudget()).use { output ->
                val writer = JvmSourceCompilerReport(
                    index, "binding", JsonObject(emptyMap()), "candidates.gz",
                    buildJsonObject { put("records", 2) }, output,
                )
                val raw = "{\"record\":\"opaque\"}\n".encodeToByteArray()
                writer.accept(0, raw, null)

                when (variant) {
                    "missing" -> assertFailsWith<IllegalArgumentException> { writer.finish() }
                    "reordered" -> assertFailsWith<IllegalArgumentException> { writer.accept(2, raw, null) }
                    else -> {
                        writer.accept(1, raw, null)
                        writer.finish()
                        assertFailsWith<IllegalStateException> { writer.accept(2, raw, null) }
                    }
                }
            }
        }
    }
}
