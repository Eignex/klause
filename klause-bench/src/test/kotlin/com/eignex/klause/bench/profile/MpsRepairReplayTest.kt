package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MpsRepairReplayTest {
    @Test
    fun `source integer freedom should release its real affine backing without corpus access`() {
        val source = """
            NAME backing
            ROWS
             N cost
             E link
            COLUMNS
             MARK0000 'MARKER' 'INTORG'
             b link -1
             MARK0001 'MARKER' 'INTEND'
             y cost 1 link 1
            RHS
             rhs link 0
            BOUNDS
             BV bounds b
            ENDATA
        """.trimIndent().encodeToByteArray()
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(source) }
        }.toByteArray()
        val request = buildJsonObject {
            put("suite", "local")
            put("problem", "backing")
            put("sha256", sha256(source))
            put("sourceGzip64", Base64.getEncoder().encodeToString(compressed))
            put("witness", "v b=1 y=1")
            put("repairSets", JsonArray(listOf(JsonArray(listOf(JsonPrimitive("b"))))))
        }
        val out = Files.createTempDirectory("mps-repair-test").toFile()
        try {
            MpsResidualObjective.inspect(request, out)

            val result = Json.parseToJsonElement(out.resolve("repair-0.json").readText()).jsonObject
            assertEquals("improvement", result.getValue("status").jsonPrimitive.content)
            assertEquals("0", result.getValue("sourceCandidateObjective").jsonPrimitive.content)
            assertEquals(listOf("y"), result.getValue("freeSourceRealIntegerBackings").jsonArray
                .map { it.jsonPrimitive.content })
        } finally {
            out.deleteRecursively()
        }
    }
}
