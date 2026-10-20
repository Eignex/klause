package com.eignex.klause.bench.profile

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmSavedReportsTest {
    @Test
    fun `a complete compressed object retains unknown and failed source metadata`(@TempDir out: File) {
        val raw = """ {"completed":false,"unknown":[null,{"future":1}]} """.encodeToByteArray()
        val file = File(out, "object.json.gz")
        GZIPOutputStream(file.outputStream()).use { it.write(raw) }
        val proof = buildJsonObject {
            put("compressed", savedIdentity(file))
            put("uncompressed", JvmSavedReports.identity(raw))
        }

        val value = JvmSavedReports.readObject(file, proof)

        assertEquals(JvmSavedJson.parse(raw.decodeToString()).jsonObject, value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["compressed", "uncompressed", "size", "duplicate", "utf8", "truncated"])
    fun `a compressed object requires whole identities and strict JSON`(variant: String, @TempDir out: File) {
        val raw = when (variant) {
            "duplicate" -> """{"x":1,"x":2}""".encodeToByteArray()
            "utf8" -> byteArrayOf(0xff.toByte())
            else -> "{}".encodeToByteArray()
        }
        val file = File(out, "object.json.gz")
        GZIPOutputStream(file.outputStream()).use { it.write(raw) }
        if (variant == "truncated") file.writeBytes(file.readBytes().dropLast(3).toByteArray())
        val proof = buildJsonObject {
            put("compressed", if (variant == "compressed") {
                JsonObject(savedIdentity(file) + ("sha256" to JsonPrimitive("a".repeat(64))))
            } else {
                savedIdentity(file)
            })
            put("uncompressed", when (variant) {
                "uncompressed" -> JsonObject(
                    JvmSavedReports.identity(raw) + ("sha256" to JsonPrimitive("a".repeat(64))),
                )
                "size" -> JsonObject(JvmSavedReports.identity(raw) + ("size" to JsonPrimitive(1)))
                else -> JvmSavedReports.identity(raw)
            })
        }

        assertFails { JvmSavedReports.readObject(file, proof) }
    }

    @Test
    fun `all raw record bytes and ordinals survive complete report reading`(@TempDir out: File) {
        val raw = " {\"first\":null}\r\n{\"unknown\":[false,0]}".encodeToByteArray()
        val file = File(out, "report.jsonl.gz")
        GZIPOutputStream(file.outputStream()).use { it.write(raw) }
        val proof = buildJsonObject {
            savedIdentity(file).forEach { (key, value) -> put(key, value) }
            put("uncompressedBytes", raw.size)
            put("uncompressedSha256", JvmSavedReports.identity(raw).getValue("sha256"))
            put("records", 2)
        }
        val rows = ArrayList<Pair<Long, String>>()

        JvmSavedReports.read(file, proof) { ordinal, bytes -> rows.add(ordinal to bytes.decodeToString()) }

        assertEquals(listOf(0L to " {\"first\":null}\r\n", 1L to "{\"unknown\":[false,0]}"), rows)
    }

    @ParameterizedTest
    @ValueSource(strings = ["records", "uncompressedBytes", "uncompressedSha256", "sha256", "truncated"])
    fun `a valid prefix cannot complete a changed or truncated report`(variant: String, @TempDir out: File) {
        val raw = "{}\n{}\n".encodeToByteArray()
        val file = File(out, "report.jsonl.gz")
        GZIPOutputStream(file.outputStream()).use { it.write(raw) }
        if (variant == "truncated") file.writeBytes(file.readBytes().dropLast(3).toByteArray())
        val original = buildJsonObject {
            savedIdentity(file).forEach { (key, value) -> put(key, value) }
            put("uncompressedBytes", raw.size)
            put("uncompressedSha256", JvmSavedReports.identity(raw).getValue("sha256"))
            put("records", 2)
        }
        val proof = if (variant == "truncated") {
            original
        } else {
            val replacement = if (variant.endsWith("sha256", ignoreCase = true)) {
                JsonPrimitive("a".repeat(64))
            } else {
                JsonPrimitive(99)
            }
            JsonObject(original + (variant to replacement))
        }

        assertFails { JvmSavedReports.read(file, proof) { _, _ -> } }
    }

    @Test
    fun `written reports require a full byte round trip`(@TempDir out: File) {
        val file = File(out, "report.jsonl.gz")
        val row = buildJsonObject {
            put("unknown", "retained")
            put("credit", false)
        }
        val report = JvmSavedReport(file, JvmSavedReportBudget())
        report.write(row)

        val proof = report.verify()

        assertEquals(JsonPrimitive(true), proof["completeGzipRoundTripVerified"])
        assertEquals(savedIdentity(file)["sha256"], proof["sha256"])
        val actual = ArrayList<String>()
        JvmSavedReports.read(file, proof) { _, raw -> actual.add(raw.decodeToString()) }
        assertEquals(listOf(row.toString() + "\n"), actual)
    }

    @Test
    fun `failed whole review can retain a closed prefix only as partial output`(@TempDir out: File) {
        val file = File(out, "report.jsonl.gz")
        val report = JvmSavedReport(file, JvmSavedReportBudget())
        report.write(buildJsonObject { put("record", "prefix") })

        report.retainPartial()

        assertFalse(file.exists())
        assertTrue(File(out, "report.partial.jsonl.gz").isFile)
        assertFails { report.write(buildJsonObject { put("record", "late") }) }
    }
}
