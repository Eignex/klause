package com.eignex.klause.bench.profile

import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchRequest
import kotlinx.serialization.json.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals

class JfrCaptureSummaryTest {
    @Test
    fun `a sampled method should count once per stack despite recursive frames`() {
        val samples = JfrCaptureSummary.Samples()

        samples.record(sequenceOf("fold", "fold", "leaf"), false)
        samples.record(sequenceOf("fold"), false)
        val methods = samples.summary("capture", "hash").getValue("sampledMethods").jsonArray.associate {
            it.jsonObject.getValue("method").jsonPrimitive.content to
                it.jsonObject.getValue("samples").jsonPrimitive.long
        }

        assertEquals(mapOf("fold" to 2L, "leaf" to 1L), methods)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `analysis depth should bound frame traversal independently of capture truncation`(truncated: Boolean) {
        val samples = JfrCaptureSummary.Samples()
        val frames = generateSequence(0) { it + 1 }.map { "frame$it" }

        samples.record(frames, truncated)
        val summary = samples.summary("capture", "hash")

        assertEquals(32, summary.getValue("sampledMethods").jsonArray.size)
        assertEquals(1L, summary.getValue("depthLimitedStacks").jsonPrimitive.long)
        assertEquals(if (truncated) 1L else 0L, summary.getValue("truncatedStacks").jsonPrimitive.long)
    }

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
