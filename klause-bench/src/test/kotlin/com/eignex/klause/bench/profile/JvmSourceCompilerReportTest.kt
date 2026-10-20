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
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSourceCompilerReportTest {
    @ParameterizedTest
    @CsvSource(
        "compilations,false", "inlining,false", "xml,false",
        "compilations,true", "inlining,true", "xml,true",
    )
    fun `direct and expanded decoding produce identical complete link reports`(
        variant: String,
        legacy: Boolean,
        @TempDir out: File,
    ) {
        val reference = JvmSavedJson.parse(
            """{
                "bindingRecordOrdinal":1,"bytecodeClassOrdinal":7,"studySourceHead":"head",
                "syntaxArchiveId":"syntax","syntaxClassOrdinal":3,"syntaxLineReportFile":"lines.gz",
                "originalClassIdentity":{"unknown":[null,false]},"originalSyntaxOrigin":{"entry":"Cold.class"}
            }""",
        ).jsonObject
        val context = JsonObject(reference + ("status" to JsonPrimitive("joined-candidates")))
        val observed = JsonPrimitive("sample.Cold.pick(I)V")
        val candidate = buildJsonObject {
            put("observedMethodCopiedVerbatim", observed)
            put("methodIdentityParsed", true)
            if (legacy) {
                put("declarationCandidates", JsonArray(listOf(0, 2).map {
                    JsonObject(reference + ("methodIndex" to JsonPrimitive(it)))
                }))
            } else {
                put("declarationCandidateReferences", JvmSavedJson.parse("[[1,0],[1,2]]"))
            }
        }
        val observation = buildJsonObject {
            put("record", if (variant == "xml") "xml-observation-candidates" else "compiler-observation-candidates")
            put("collection", if (variant == "xml") "tasks" else variant)
            put("unknown", JsonArray(listOf(JsonNull, JsonPrimitive(false))))
            put("originalObservationCopiedVerbatim", buildJsonObject {
                when (variant) {
                    "xml" -> put("rawAttributes", buildJsonObject { put("method", "sample/Cold\u00a0pick (I)V") })
                    "inlining" -> { put("caller", observed); put("callee", observed) }
                    else -> put("method", observed)
                }
            })
            put("methodCandidates", when (variant) {
                "xml" -> candidate
                "inlining" -> buildJsonObject { put("caller", candidate); put("callee", candidate) }
                else -> buildJsonObject { put("method", candidate) }
            })
        }
        val rows = buildList {
            add(buildJsonObject {
                put("record", "schema")
                put("encoding", if (legacy) "compiler-bytecode-candidates-v2" else "compiler-bytecode-candidates-v3")
                put("bindingInputId", "binding")
                put("censusInputId", "census")
                if (!legacy) put("deniedProofFlags", buildJsonObject { COHORT_PROOF_FLAGS.forEach { put(it, false) } })
            })
            add(buildJsonObject { put("record", "original-binding-schema") })
            add(buildJsonObject {
                put("record", "original-binding-reference")
                put("originalRecordOrdinal", 1)
                put("originalClassContext", context)
            })
            if (!legacy) add(buildJsonObject {
                put("record", "declaration-candidate-context")
                put("bindingRecordOrdinal", 1)
                put("reference", reference)
                put("methodCount", 3)
            })
            add(observation)
            add(observation)
            if (!legacy) add(buildJsonObject {
                put("record", "candidate-report-end")
                put("recordsBeforeEnd", 6)
                put("bindingReferences", 1)
                put("candidateContexts", 1)
            })
        }
        val audit = JvmSavedJson.parse(
            """{
                "record":"class-audit","originalSyntaxClassOrdinal":3,"internalName":"sample/Cold",
                "originalClassOrigin":{"entry":"Cold.class"},"originalMethodIndexOrder":[0,1,2],
                "noFormalAlternativeMethodIndexes":[0,1,2],"allOriginalMethodFormalChecks":[]
            }""",
        ).jsonObject
        val input = buildJsonObject { put("id", "whole-input"); put("unknown", "retained") }
        val proof = buildJsonObject { put("records", rows.size) }

        val actual = JvmSourceCompilerIndex(File(out, "source-index.sqlite")).use { index ->
            index.add("binding", "head", "audit.gz", 2, audit, JsonObject(emptyMap()))
            index.finish()
            index.addStatic(
                "binding", "static.gz", 1,
                buildJsonObject {
                    reference.filterKeys {
                        it !in setOf("bindingRecordOrdinal", "originalClassIdentity", "originalSyntaxOrigin")
                    }
                        .forEach { (key, value) -> put(key, value) }
                    put("status", "joined-candidates")
                    put("internalName", "sample/Cold")
                    put("classIdentity", reference.getValue("originalClassIdentity"))
                    put("syntaxOrigin", reference.getValue("originalSyntaxOrigin"))
                    put("methods", JvmSavedJson.parse(
                        """[{"methodIndex":0,"name":"pick","descriptor":"(I)V"},
                            {"methodIndex":1,"name":"cold","descriptor":"()V"},
                            {"methodIndex":2,"name":"pick","descriptor":"(I)V"}]""",
                    ))
                },
                JsonObject(emptyMap()),
            )
            index.sealStaticMethods()
            listOf(false, true).map { direct ->
                val file = File(out, "links-$direct.jsonl.gz")
                val output = JvmSavedReport(file, JvmSavedReportBudget())
                val writer = JvmSourceCompilerReport(index, "binding", input, "candidates.gz", proof, output)
                val project: JvmCandidateProjection = writer::projectCandidates
                JvmCompilerCandidates(File(out, "candidates-$direct.sqlite")).use { codec ->
                    rows.forEachIndexed { ordinal, row ->
                        val raw = (row.toString() + "\n").encodeToByteArray()
                        if (direct) {
                            writer.accept(ordinal.toLong(), raw, codec.acceptForLinks(row, project))
                        } else {
                            writer.accept(ordinal.toLong(), raw, codec.accept(row))
                        }
                    }
                    codec.finish()
                    writer.finish()
                }
                output.verify()
                GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }
            }
        }

        assertEquals(actual[0], actual[1])
    }

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
                put("bindingRecordOrdinal", 1)
                put("bytecodeClassOrdinal", 0)
                put("studySourceHead", "head")
                put("syntaxArchiveId", "syntax")
                put("syntaxClassOrdinal", 0)
                put("syntaxLineReportFile", "lines.gz")
                put("originalClassIdentity", JsonObject(emptyMap()))
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
            index.addStatic(
                "binding", "static.gz", 1,
                JvmSavedJson.parse(
                    """{
                        "status":"joined-candidates","internalName":"sample/Cold","bytecodeClassOrdinal":0,
                        "studySourceHead":"head","syntaxArchiveId":"syntax","syntaxClassOrdinal":0,
                        "syntaxLineReportFile":"lines.gz","classIdentity":{},"syntaxOrigin":{"entry":"Cold.class"},
                        "methods":[{"methodIndex":0,"name":"pick","descriptor":"(I)V"}]
                    }""",
                ).jsonObject,
                JsonObject(emptyMap()),
            )
            index.sealStaticMethods()
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
                assertEquals(JsonPrimitive("source-compiler-links-v4"), actual.first()["encoding"])
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
                    it.getValue("sourceAuditMethodReferences").jsonObject.getValue("method")
                }
                assertEquals(listOf(0, 0, 1).map { JsonPrimitive(it) }, references)
                val methods = actual.filter { it["record"] == JsonPrimitive("source-audit-method-definition") }
                assertEquals(listOf(JsonPrimitive(0), JsonPrimitive(1)), methods.map { it["reference"] })
                assertEquals(listOf(0, 1).map { JsonArray(listOf(JsonPrimitive(it))) }, methods.map {
                    it.getValue("allOriginalDeclarationCandidateSourceAuditLinkReferences")
                })
                assertEquals(JsonPrimitive(6), actual.last()["originalRawRecords"])
                assertEquals(JsonPrimitive(4), actual.last()["originalExpandedRecords"])
                assertEquals(JsonPrimitive(2), actual.last()["sourceLinkDefinitions"])
                assertEquals(JsonPrimitive(2), actual.last()["methodLinkDefinitions"])
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["reversed", "duplicate"])
    fun `shared method definitions retain candidate order and repetitions`(variant: String, @TempDir out: File) {
        val origin = buildJsonObject { put("entry", "Cold.class") }
        val audit = JvmSavedJson.parse(
            """{
                "record":"class-audit","originalSyntaxClassOrdinal":0,"internalName":"sample/Cold",
                "originalClassOrigin":{"entry":"Cold.class"},"originalMethodIndexOrder":[0],
                "noFormalAlternativeMethodIndexes":[0],"allOriginalMethodFormalChecks":[]
            }""",
        ).jsonObject
        val second = if (variant == "reversed") listOf(false, true) else listOf(true, true, false)
        val sequences = listOf(listOf(true, false), second)
        val rows = sequences.map { sequence ->
            buildJsonObject {
                put("record", "compiler-observation-candidates")
                put("methodCandidates", buildJsonObject {
                    put("method", buildJsonObject {
                        put("observedMethodCopiedVerbatim", "sample.Cold.pick(I)V")
                        put("methodIdentityParsed", true)
                        put("declarationCandidates", JsonArray(sequence.map { unknown ->
                            buildJsonObject {
                                put("bindingRecordOrdinal", 1)
                                put("bytecodeClassOrdinal", 0)
                                put("studySourceHead", "head")
                                put("syntaxArchiveId", "syntax")
                                put("syntaxClassOrdinal", 0)
                                put("syntaxLineReportFile", "lines.gz")
                                put("originalClassIdentity", JsonObject(emptyMap()))
                                put("methodIndex", 0)
                                put("originalSyntaxOrigin", origin)
                                put("unknown", unknown)
                            }
                        }))
                    })
                })
            }
        }
        val file = File(out, "links.jsonl.gz")

        JvmSourceCompilerIndex(File(out, "index.sqlite")).use { index ->
            index.add("binding", "head", "audit.gz", 2, audit, JsonObject(emptyMap()))
            index.finish()
            index.addStatic(
                "binding", "static.gz", 1,
                JvmSavedJson.parse(
                    """{
                        "status":"joined-candidates","internalName":"sample/Cold","bytecodeClassOrdinal":0,
                        "studySourceHead":"head","syntaxArchiveId":"syntax","syntaxClassOrdinal":0,
                        "syntaxLineReportFile":"lines.gz","classIdentity":{},"syntaxOrigin":{"entry":"Cold.class"},
                        "methods":[{"methodIndex":0,"name":"pick","descriptor":"(I)V"}]
                    }""",
                ).jsonObject,
                JsonObject(emptyMap()),
            )
            index.sealStaticMethods()
            val output = JvmSavedReport(file, JvmSavedReportBudget())
            val writer = JvmSourceCompilerReport(
                index, "binding", JsonObject(emptyMap()), "candidates.gz",
                buildJsonObject { put("records", rows.size) }, output,
            )
            rows.forEachIndexed { ordinal, row ->
                writer.accept(ordinal.toLong(), (row.toString() + "\n").encodeToByteArray(), row)
            }
            writer.finish()
            output.verify()
        }

        val actual = GZIPInputStream(file.inputStream()).bufferedReader().use { reader ->
            reader.readLines().map { JvmSavedJson.parse(it).jsonObject }
        }
        val methods = actual.filter { it["record"] == JsonPrimitive("source-audit-method-definition") }
        val expected = sequences.map { sequence -> JsonArray(sequence.map { JsonPrimitive(if (it) 0 else 1) }) }
        assertEquals(expected, methods.map { it["allOriginalDeclarationCandidateSourceAuditLinkReferences"] })
        assertEquals(JsonPrimitive(2), actual.last()["sourceLinkDefinitions"])
        assertEquals(JsonPrimitive(2), actual.last()["methodLinkDefinitions"])
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
