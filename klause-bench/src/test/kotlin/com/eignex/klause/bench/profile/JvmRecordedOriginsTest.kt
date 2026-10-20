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
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class JvmRecordedOriginsTest {
    @ParameterizedTest
    @ValueSource(strings = ["matches", "conflicts", "unknown", "outside"])
    @Suppress("LongMethod")
    fun `whole origin audit retains static alternatives failures and unparsed records`(
        variant: String,
        @TempDir out: File,
    ) {
        val jar = when (variant) {
            "matches" -> buildJsonObject { put("sha256", "a".repeat(64)) }
            "conflicts" -> buildJsonObject { put("sha256", "c".repeat(64)) }
            else -> JsonNull
        }
        val record = buildJsonObject {
            put("className", if (variant == "outside") "outside.Other" else "sample.Cold")
            put("recordedInstalledJar", jar)
            put("unknown", JvmSavedJson.parse("""[null,{"future":false}]"""))
        }
        val unparsed = buildJsonObject {
            put("line", 2)
            put("text", "unparsed original line")
        }
        val raw = buildJsonObject {
            put("completed", false)
            put("error", buildJsonObject { put("type", "OriginalFailure") })
            put("observations", buildJsonObject {
                put("completed", false)
                put("records", JsonArray(listOf(record)))
                put("unparsedLines", JsonArray(listOf(unparsed)))
                put("unknown", true)
            })
        }.toString().encodeToByteArray()
        val origins = File(out, "origins.json.gz")
        GZIPOutputStream(origins.outputStream()).use { it.write(raw) }
        val original = buildJsonObject {
            put("retainedMetadata", buildJsonObject {
                put("path", "class-origins-000000-000.json.gz")
                put("compressed", savedIdentity(origins))
                put("uncompressed", JvmSavedReports.identity(raw))
            })
        }
        val case = buildJsonObject {
            put("caseDirectory", "case0")
            put("classOriginInputs", JsonArray(listOf(original)))
            put("recordedClassOriginsComplete", false)
        }
        val census = File(out, "census.json").apply {
            writeText(buildJsonObject {
                put("completed", true)
                put("originalInventory", buildJsonObject { put("cases", JsonArray(listOf(case))) })
                put("cases", JsonArray(listOf(case)))
            }.toString())
        }
        val input = buildJsonObject {
            put("id", "census")
            put("indexMember", "profile/census.json")
        }
        val file = File(out, "audit.jsonl.gz")
        val report = JvmSavedReport(file, JvmSavedReportBudget())
        JvmSourceCompilerIndex(File(out, "index.sqlite")).use { index ->
            listOf("sample/Cold", "sample/Cold", "sample/Unloaded", null).forEachIndexed { ordinal, name ->
                val row = buildJsonObject {
                    put("record", "class-audit")
                    put("originalSyntaxClassOrdinal", ordinal)
                    put("internalName", name)
                    put("originalClassOrigin", buildJsonObject {
                        put("jar", "lib/sample.jar")
                        put("jarSha256", if (ordinal == 0) "a".repeat(64) else "b".repeat(64))
                    })
                    put("originalMethodIndexOrder", JsonNull)
                    put("noFormalAlternativeMethodIndexes", JsonNull)
                    put("allOriginalMethodFormalChecks", JsonArray(emptyList()))
                }
                index.add("binding", "head", "source.jsonl.gz", ordinal.toLong(), row, JsonObject(emptyMap()))
            }
            index.finish()

            JvmRecordedOrigins(index, input, mapOf(
                "profile/census.json" to census,
                "profile/class-origins-000000-000.json.gz" to origins,
            ), report).review()
        }
        val proof = report.verify()
        val rows = ArrayList<JsonObject>()
        JvmSavedReports.read(file, proof) { _, bytes ->
            rows.add(JvmSavedJson.parse(bytes.decodeToString()).jsonObject)
        }

        assertEquals(4, rows.count { it["record"] == JsonPrimitive("static-source-class") })
        assertEquals(record, rows.single {
            it["record"] == JsonPrimitive("original-recorded-class-origin")
        }["wholeOriginalRecordCopiedVerbatim"])
        assertEquals(unparsed, rows.single {
            it["record"] == JsonPrimitive("original-unparsed-origin-line")
        }["wholeOriginalLineCopiedVerbatim"])
        assertEquals(case, rows.single { it["record"] == JsonPrimitive("original-case") }[
            "wholeCaseProtocolCopiedVerbatim"
        ])
        val sections = rows.single { it["record"] == JsonPrimitive("original-class-origin-input") }
        assertEquals(JsonPrimitive(false), sections.getValue("originalMetadataSections").jsonObject["completed"])
        assertEquals(JsonPrimitive(true), sections.getValue("originalObservationSections").jsonObject["unknown"])
        val agreements = rows.filter { it["record"] == JsonPrimitive("recorded-origin-static-candidate") }
            .map { it["recordedJarHashAgreement"] }
        assertEquals(when (variant) {
            "matches" -> listOf(JsonPrimitive(true), JsonPrimitive(false))
            "conflicts" -> listOf(JsonPrimitive(false), JsonPrimitive(false))
            "unknown" -> listOf(JsonNull, JsonNull)
            else -> emptyList()
        }, agreements)
        assertEquals(if (variant == "outside") 1 else 0, rows.count {
            it["record"] == JsonPrimitive("no-static-owner-candidate")
        })
        assertTrue(rows.first().getValue("deniedProofFlags").jsonObject.values.all { it == JsonPrimitive(false) })
        assertEquals(JsonPrimitive(1), rows.last()["originalOriginRecords"])
        assertEquals(JsonPrimitive(1), rows.last()["originalUnparsedLines"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["incomplete", "missing-case", "duplicate-input", "unreferenced-input"])
    fun `an origin audit cannot replace the complete case or input census with a prefix`(
        variant: String,
        @TempDir out: File,
    ) {
        val raw = """{"observations":{}}""".encodeToByteArray()
        val origins = File(out, "origins.json.gz")
        GZIPOutputStream(origins.outputStream()).use { it.write(raw) }
        val original = buildJsonObject {
            put("retainedMetadata", buildJsonObject {
                put("path", "class-origins-000000-000.json.gz")
                put("compressed", savedIdentity(origins))
                put("uncompressed", JvmSavedReports.identity(raw))
            })
        }
        val case = buildJsonObject {
            put("caseDirectory", "case0")
            put("classOriginInputs", JsonArray(if (variant == "duplicate-input") {
                listOf(original, original)
            } else {
                listOf(original)
            }))
        }
        val census = File(out, "census.json").apply {
            writeText(buildJsonObject {
                put("completed", variant != "incomplete")
                put("originalInventory", buildJsonObject { put("cases", JsonArray(listOf(case))) })
                put("cases", JsonArray(if (variant == "missing-case") emptyList() else listOf(case)))
            }.toString())
        }
        val paths = mutableMapOf(
            "profile/census.json" to census,
            "profile/class-origins-000000-000.json.gz" to origins,
        )
        if (variant == "unreferenced-input") paths["profile/class-origins-000001-000.json.gz"] = origins
        val input = buildJsonObject { put("indexMember", "profile/census.json") }
        JvmSourceCompilerIndex(File(out, "index.sqlite")).use { index ->
            JvmSavedReport(File(out, "report.jsonl.gz"), JvmSavedReportBudget()).use { report ->
                assertFails { JvmRecordedOrigins(index, input, paths, report).review() }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid", "number", "module", "ambiguous", "malformed"])
    fun `unavailable jar identity supplies no agreement`(variant: String) {
        val origin = buildJsonObject {
            if (variant != "module") {
                put("jar", "lib/sample.jar")
                put("jarSha256", "a".repeat(64))
            }
            if (variant in setOf("module", "ambiguous")) put("module", "java.base")
        }
        val recorded = when (variant) {
            "missing" -> JsonNull
            "invalid" -> buildJsonObject { put("sha256", "invalid") }
            "number" -> buildJsonObject { put("sha256", 1) }
            "module", "ambiguous" -> buildJsonObject { put("sha256", "a".repeat(64)) }
            else -> JsonPrimitive("not an object")
        }

        if (variant == "malformed") {
            assertFails { JvmRecordedOrigins.jarHashAgreement(origin, recorded) }
        } else {
            assertEquals(JsonNull, JvmRecordedOrigins.jarHashAgreement(origin, recorded))
        }
    }
}
