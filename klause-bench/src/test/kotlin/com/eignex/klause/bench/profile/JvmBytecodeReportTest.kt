package com.eignex.klause.bench.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JvmBytecodeReportTest {
    @Test
    fun `complete reports retain schema and end census after gzip verification`(@TempDir out: File) {
        val census = buildJsonObject { put("classes", 7) }

        val summary = JvmBytecodeReport.write(out) { census }

        val rows = GZIPInputStream(File(out, "bytecode-metadata.jsonl.gz").inputStream()).bufferedReader()
            .useLines { lines -> lines.map { Json.parseToJsonElement(it).jsonObject }.toList() }
        assertEquals(listOf(JsonPrimitive("schema"), JsonPrimitive("inventory-end")), rows.map { it["record"] })
        assertEquals(JsonPrimitive(7), rows.last()["classes"])
        assertEquals(JsonPrimitive(true), summary["completeGzipRoundTripVerified"])
        assertEquals(JsonPrimitive(JvmBytecodeInputs.digest(File(out, "bytecode-metadata.jsonl.gz"))),
            summary["reportSha256"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["logical", "compressed"])
    fun `report bounds preserve a partial file without accepting a prefix`(variant: String, @TempDir out: File) {
        assertFailsWith<IllegalStateException> {
            JvmBytecodeReport.write(out, maximumLogicalBytes = if (variant == "logical") 1 else 4096,
                maximumCompressedBytes = if (variant == "compressed") 1 else 4096) { buildJsonObject {} }
        }

        assertTrue(File(out, "bytecode-metadata.partial.jsonl.gz").isFile)
        assertFalse(File(out, "bytecode-metadata.jsonl.gz").exists())
    }

    @ParameterizedTest
    @ValueSource(strings = ["exception", "error"])
    fun `failed inventory actions retain the original failure and partial bytes`(variant: String, @TempDir out: File) {
        val failure = if (variant == "error") AssertionError("inventory failed") else IllegalStateException("failed")

        val actual = assertFailsWith<Throwable> { JvmBytecodeReport.write(out) { throw failure } }

        assertSame(failure, actual)
        assertTrue(File(out, "bytecode-metadata.partial.jsonl.gz").isFile)
        assertFalse(File(out, "bytecode-metadata.jsonl.gz").exists())
    }
}
