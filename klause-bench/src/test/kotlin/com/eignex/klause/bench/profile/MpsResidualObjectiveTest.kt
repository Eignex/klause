package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MpsResidualObjectiveTest {
    @Test
    fun `inline source should preserve its bytes without corpus access`() {
        val source = "NAME backing\nENDATA\n".encodeToByteArray()
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(source) }
        }.toByteArray()
        val request = buildJsonObject {
            put("sha256", sha256(source))
            put("sourceGzip64", Base64.getEncoder().encodeToString(compressed))
        }

        val loaded = MpsResidualObjective.sourceBytes(request)

        assertContentEquals(source, loaded)
    }

    @Test
    fun `inline source should reject a mismatched hash`() {
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write("NAME backing\nENDATA\n".encodeToByteArray()) }
        }.toByteArray()
        val request = buildJsonObject {
            put("sha256", "wrong")
            put("sourceGzip64", Base64.getEncoder().encodeToString(compressed))
        }

        assertFailsWith<IllegalArgumentException> { MpsResidualObjective.sourceBytes(request) }
    }
}
