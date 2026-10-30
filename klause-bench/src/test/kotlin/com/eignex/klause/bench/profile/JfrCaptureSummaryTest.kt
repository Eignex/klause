package com.eignex.klause.bench.profile

import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class JfrCaptureSummaryTest {
    @Test
    fun `capture payload larger than a shell argument survives the frozen case protocol`() {
        val payload = "AAAA".repeat(100_000)
        val request = BenchRequest(mapOf("source" to "retained/capture.jfr", "sha256" to "a".repeat(64),
            "gzip-base64" to payload))

        val planned = JfrCaptureSummary.plan(request).single()
        val frozen = BenchProtocol.json.encodeToString(BenchCase.serializer(), planned)
        val restored = BenchProtocol.json.decodeFromString(BenchCase.serializer(), frozen)

        assertEquals(payload, restored.parameters.getValue("gzip-base64"))
    }
}
