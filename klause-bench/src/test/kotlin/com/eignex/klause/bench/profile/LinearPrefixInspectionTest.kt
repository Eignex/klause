package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.formats.smtlib.SmtLib
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LinearPrefixInspectionTest {
    @Test
    fun `a fresh partial state replay preserves exact arithmetic and source names`() {
        val source = """
            (declare-const x Int)
            (declare-const b Bool)
            (assert (or b (<= x 0)))
            (assert (or (not b) (>= x 2)))
        """.trimIndent()
        val out = createTempDirectory("linear-prefix").toFile()
        try {
            val compiled = SmtLib.parse(source)

            LinearPrefixInspection.inspect(compiled, out, sha256(source.toByteArray()), targetBoundaries = 1)

            val report = Json.parseToJsonElement(out.resolve("replay.json").readText()).jsonObject
            assertEquals("true", report.getValue("exactReplay").jsonPrimitive.content)
            assertTrue(out.resolve("prefix-0.exact").readBytes()
                .contentEquals(out.resolve("prefix-1.exact").readBytes()))
            val snapshot = Json.parseToJsonElement(out.resolve("prefix-0.json").readText()).jsonObject
            assertTrue(snapshot.getValue("sourceNames").toString().contains("x"))
        } finally {
            out.deleteRecursively()
        }
    }
}
