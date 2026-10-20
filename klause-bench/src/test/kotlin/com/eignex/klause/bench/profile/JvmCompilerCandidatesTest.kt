package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JvmCompilerCandidatesTest {
    @ParameterizedTest
    @ValueSource(strings = ["compilations", "inlining", "xml"])
    fun `normalized observations retain every ordered declaration and opaque field`(
        variant: String,
        @TempDir out: File,
    ) {
        val reference = JvmSavedJson.parse(
            """{
            "bindingRecordOrdinal":1,"bytecodeClassOrdinal":7,"studySourceHead":"head",
            "syntaxArchiveId":"syntax","syntaxClassOrdinal":3,"syntaxLineReportFile":"lines.gz",
            "originalClassIdentity":{"unknown":[null,false]},"originalSyntaxOrigin":{"entry":"Cold.class"}
        }"""
        ).jsonObject
        val context = JsonObject(reference + ("status" to JsonPrimitive("joined-candidates")))
        val observed = JsonPrimitive("sample.Cold.pick(I)V")
        val candidate = buildJsonObject {
            put("observedMethodCopiedVerbatim", observed)
            put("methodIdentityParsed", true)
            put("declarationCandidateReferences", JvmSavedJson.parse("[[1,0],[1,2]]"))
        }
        val observation = buildJsonObject {
            put("record", if (variant == "xml") "xml-observation-candidates" else "compiler-observation-candidates")
            put("collection", if (variant == "xml") "tasks" else variant)
            put("unknown", JsonArray(listOf(JsonNull, JsonPrimitive(false))))
            put(
                "originalObservationCopiedVerbatim",
                buildJsonObject {
                when (variant) {
                    "xml" -> put("rawAttributes", buildJsonObject { put("method", "sample/Cold\u00a0pick (I)V") })

                    "inlining" -> {
                        put("caller", observed)
                        put("callee", observed)
                    }

                    else -> put("method", observed)
                }
            }
            )
            put(
                "methodCandidates",
                when (variant) {
                    "xml" -> candidate

                    "inlining" -> buildJsonObject {
                        put("caller", candidate)
                        put("callee", candidate)
                    }

                    else -> buildJsonObject { put("method", candidate) }
                },
            )
        }

        val actual = JvmCompilerCandidates(File(out, "index.sqlite")).use { codec ->
            codec.accept(
                buildJsonObject {
                put("record", "schema")
                put("encoding", "compiler-bytecode-candidates-v3")
                put("bindingInputId", "binding")
                put("censusInputId", "census")
                put("deniedProofFlags", buildJsonObject { COHORT_PROOF_FLAGS.forEach { put(it, false) } })
            }
            )
            codec.accept(buildJsonObject { put("record", "original-binding-schema") })
            codec.accept(
                buildJsonObject {
                put("record", "original-binding-reference")
                put("originalRecordOrdinal", 1)
                put("originalClassContext", context)
            }
            )
            assertNull(
                codec.accept(
                    buildJsonObject {
                put("record", "declaration-candidate-context")
                put("bindingRecordOrdinal", 1)
                put("reference", reference)
                put("methodCount", 3)
            }
                )
            )
            val result = requireNotNull(codec.accept(observation))
            codec.accept(
                buildJsonObject {
                put("record", "candidate-report-end")
                put("recordsBeforeEnd", 5)
                put("bindingReferences", 1)
                put("candidateContexts", 1)
            }
            )
            codec.finish()
            result
        }

        val methodCandidates = actual.getValue("methodCandidates").jsonObject
        val methods = if (variant == "xml") {
            methodCandidates
        } else {
            methodCandidates.getValue(if (variant == "inlining") "caller" else "method").jsonObject
        }
        assertEquals(
            JsonArray(listOf(0, 2).map { JsonObject(reference + ("methodIndex" to JsonPrimitive(it))) }),
            methods["declarationCandidates"],
        )
        assertEquals(observation["unknown"], actual["unknown"])
        assertEquals(observation["originalObservationCopiedVerbatim"], actual["originalObservationCopiedVerbatim"])
        assertEquals(observed, methods["observedMethodCopiedVerbatim"])
    }

    @ParameterizedTest
    @CsvSource(
        "missing-end,false", "false-proof-default,false", "unknown-record,false",
        "missing-end,true", "false-proof-default,true", "unknown-record,true",
    )
    fun `an incomplete or altered normalized report cannot complete`(
        variant: String,
        direct: Boolean,
        @TempDir out: File,
    ) {
        JvmCompilerCandidates(File(out, "index.sqlite")).use { codec ->
            val schema = buildJsonObject {
                put("record", "schema")
                put("encoding", "compiler-bytecode-candidates-v3")
                put("bindingInputId", "binding")
                put("censusInputId", "census")
                put(
                    "deniedProofFlags",
                    buildJsonObject {
                    COHORT_PROOF_FLAGS.forEach { put(it, variant == "false-proof-default") }
                }
                )
            }
            val project: JvmCandidateProjection = { _, _, _ -> error("A refused report cannot project candidates") }

            assertFailsWith<IllegalArgumentException> {
                if (direct) codec.acceptForLinks(schema, project) else codec.accept(schema)
                if (variant == "unknown-record") {
                    val row = buildJsonObject { put("record", "unknown") }
                    if (direct) codec.acceptForLinks(row, project) else codec.accept(row)
                }
                codec.finish()
            }
        }
    }

    @Test
    fun `candidate and projection lookups preserve each owning projection`(@TempDir out: File) {
        val reference = JvmSavedJson.parse(
            """{
                "bindingRecordOrdinal":1,"bytecodeClassOrdinal":0,"studySourceHead":"head",
                "syntaxArchiveId":"syntax","syntaxClassOrdinal":0,"syntaxLineReportFile":"lines.gz",
                "originalClassIdentity":{"unknown":[null,false]},"originalSyntaxOrigin":{"entry":"Cold.class"}
            }""",
        ).jsonObject
        val context = JsonObject(reference + ("status" to JsonPrimitive("joined-candidates")))
        val candidate = JvmSavedJson.parse(
            """{"observedMethodCopiedVerbatim":"sample.Cold.pick(I)V",
                "methodIdentityParsed":true,"declarationCandidateReferences":[[1,0]]}""",
        ).jsonObject
        val observation = buildJsonObject {
            put("record", "compiler-observation-candidates")
            put("collection", "compilations")
            put("originalObservationCopiedVerbatim", buildJsonObject { put("method", "sample.Cold.pick(I)V") })
            put("methodCandidates", buildJsonObject { put("method", candidate) })
        }
        var firstLoads = 0
        var secondLoads = 0
        val first: JvmCandidateProjection = { _, _, _ -> firstLoads++; JsonPrimitive(7) }
        val second: JvmCandidateProjection = { _, _, _ -> secondLoads++; JsonPrimitive(11) }

        val actual = JvmCompilerCandidates(File(out, "index.sqlite")).use { codec ->
            codec.accept(buildJsonObject {
                put("record", "schema")
                put("encoding", "compiler-bytecode-candidates-v3")
                put("bindingInputId", "binding")
                put("censusInputId", "census")
                put("deniedProofFlags", buildJsonObject { COHORT_PROOF_FLAGS.forEach { put(it, false) } })
            })
            codec.accept(buildJsonObject { put("record", "original-binding-schema") })
            codec.accept(buildJsonObject {
                put("record", "original-binding-reference")
                put("originalRecordOrdinal", 1)
                put("originalClassContext", context)
            })
            codec.accept(buildJsonObject {
                put("record", "declaration-candidate-context")
                put("bindingRecordOrdinal", 1)
                put("reference", reference)
                put("methodCount", 1)
            })
            val projected = codec.acceptForLinks(observation, first)
            val expanded = requireNotNull(codec.accept(observation))
            val repeated = codec.acceptForLinks(observation, first)
            val other = codec.acceptForLinks(observation, second)
            codec.accept(buildJsonObject {
                put("record", "candidate-report-end")
                put("recordsBeforeEnd", 8)
                put("bindingReferences", 1)
                put("candidateContexts", 1)
            })
            codec.finish()
            val expected = JsonArray(listOf(JsonObject(reference + ("methodIndex" to JsonPrimitive(0)))))
            assertEquals(expected, expanded.getValue("methodCandidates").jsonObject
                .getValue("method").jsonObject["declarationCandidates"])
            listOf(projected, repeated, other).map {
                requireNotNull(it.value).getValue("methodCandidates").jsonObject["method"]
            }
        }

        assertEquals(listOf(JsonPrimitive(7), JsonPrimitive(7), JsonPrimitive(11)), actual)
        assertEquals(1, firstLoads)
        assertEquals(1, secondLoads)
    }

    @ParameterizedTest
    @ValueSource(strings = ["parsed", "order", "bounds", "observation", "proof"])
    fun `a cached projection cannot admit altered observation or reference validation`(
        variant: String,
        @TempDir out: File,
    ) {
        val reference = JvmSavedJson.parse(
            """{
                "bindingRecordOrdinal":1,"bytecodeClassOrdinal":0,"studySourceHead":"head",
                "syntaxArchiveId":"syntax","syntaxClassOrdinal":0,"syntaxLineReportFile":"lines.gz",
                "originalClassIdentity":{"unknown":[null,false]},"originalSyntaxOrigin":{"entry":"Cold.class"}
            }""",
        ).jsonObject
        val context = JsonObject(reference + ("status" to JsonPrimitive("joined-candidates")))
        val candidate = JvmSavedJson.parse(
            """{"observedMethodCopiedVerbatim":"sample.Cold.pick(I)V",
                "methodIdentityParsed":true,"declarationCandidateReferences":[[1,0]]}""",
        ).jsonObject
        val observation = buildJsonObject {
            put("record", "compiler-observation-candidates")
            put("collection", "compilations")
            put("originalObservationCopiedVerbatim", buildJsonObject { put("method", "sample.Cold.pick(I)V") })
            put("methodCandidates", buildJsonObject { put("method", candidate) })
        }
        val changedCandidate = when (variant) {
            "parsed" -> JsonObject(candidate + ("methodIdentityParsed" to JsonPrimitive(false)))
            "order", "bounds" -> JsonObject(candidate + ("declarationCandidateReferences" to
                JvmSavedJson.parse(if (variant == "order") "[[1,0],[1,0]]" else "[[1,1]]")))
            else -> candidate
        }
        val changedMethod = buildJsonObject { put("method", changedCandidate) }
        val otherObservation = buildJsonObject { put("method", "sample.Cold.other(I)V") }
        val changed = when (variant) {
            "observation" -> JsonObject(observation + ("originalObservationCopiedVerbatim" to otherObservation))
            "proof" -> JsonObject(observation + (COHORT_PROOF_FLAGS.first() to JsonPrimitive(true)))
            else -> JsonObject(observation + ("methodCandidates" to changedMethod))
        }
        val project: JvmCandidateProjection = { _, _, _ -> JsonPrimitive(7) }

        JvmCompilerCandidates(File(out, "index.sqlite")).use { codec ->
            codec.accept(buildJsonObject {
                put("record", "schema")
                put("encoding", "compiler-bytecode-candidates-v3")
                put("bindingInputId", "binding")
                put("censusInputId", "census")
                put("deniedProofFlags", buildJsonObject { COHORT_PROOF_FLAGS.forEach { put(it, false) } })
            })
            codec.accept(buildJsonObject { put("record", "original-binding-schema") })
            codec.accept(buildJsonObject {
                put("record", "original-binding-reference")
                put("originalRecordOrdinal", 1)
                put("originalClassContext", context)
            })
            codec.accept(buildJsonObject {
                put("record", "declaration-candidate-context")
                put("bindingRecordOrdinal", 1)
                put("reference", reference)
                put("methodCount", 1)
            })
            codec.acceptForLinks(observation, project)

            assertFailsWith<IllegalArgumentException> { codec.acceptForLinks(changed, project) }
        }
    }

    @Test
    fun `legacy candidate records preserve unknown and cold alternatives`(@TempDir out: File) {
        val row = JvmSavedJson.parse("""{"record":"unknown-legacy","cold":[null,false,{},[]]}""").jsonObject

        val actual = JvmCompilerCandidates(File(out, "index.sqlite")).use { codec ->
            codec.accept(
                buildJsonObject {
                put("record", "schema")
                put("encoding", "compiler-bytecode-candidates-v2")
            }
            )
            val result = codec.accept(row)
            codec.finish()
            result
        }

        assertEquals(row, actual)
    }
}
