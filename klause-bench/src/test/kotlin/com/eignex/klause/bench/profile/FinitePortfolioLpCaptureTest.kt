package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FinitePortfolioLpCaptureTest {
    @Test
    fun `finite portfolio capture should retain a bounded real query without fetching tools`() {
        val source = """
            NAME CAPTURE
            ROWS
             N OBJ
             G C
            COLUMNS
             M0 'MARKER' 'INTORG'
             X OBJ 1 C 1
             M1 'MARKER' 'INTEND'
             R OBJ 1 C 1
            RHS
             RHS1 C 1.5
            BOUNDS
             BV BND X
             UP BND R 2
            ENDATA
        """.trimIndent().encodeToByteArray()
        val out = createTempDirectory("finite-lp-capture").toFile()
        try {
            FinitePortfolioLpCapture.inspect(mapOf("suite" to "fixture", "problem" to "capture",
                "sha256" to sha256(source), "seed" to "1", "handoff" to "true",
                "timeout-ms" to "150", "capture-worker" to "any"), source, out)

            val result = Reports.json.parseToJsonElement(out.resolve("result.json").readText()).jsonObject
            assertEquals("1", result.getValue("retainedCaptures").jsonPrimitive.content)
            assertEquals("false", result.getValue("sourceVerdictClaim").jsonPrimitive.content)
            val queries = out.resolve("queries.jsonl").readLines().map {
                Reports.json.parseToJsonElement(it).jsonObject
            }
            assertTrue(queries.isNotEmpty())
            val retained = queries.single { it.getValue("selected").jsonPrimitive.content == "true" }
            val payload = out.resolve(retained.getValue("capture").jsonPrimitive.content)
            assertTrue(payload.length() in 1..1_048_576L)
            assertEquals(retained.getValue("captureSha256").jsonPrimitive.content, sha256(payload.readBytes()))
            assertFalse(result.getValue("fullWarmReplayQualified").jsonPrimitive.content.toBoolean())
        } finally {
            out.deleteRecursively()
        }
    }
}
