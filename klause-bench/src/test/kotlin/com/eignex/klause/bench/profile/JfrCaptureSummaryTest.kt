package com.eignex.klause.bench.profile

import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchRequest
import com.github.luben.zstd.Zstd
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JfrCaptureSummaryTest {
    @TempDir
    lateinit var output: Path

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

    @ParameterizedTest
    @CsvSource("gzip-base64, false", "gzip-base64, true", "zstd-base64, false", "zstd-base64, true")
    fun `capture payload and event selection survive the frozen case protocol`(codec: String, events: Boolean) {
        val payload = "AAAA".repeat(100_000)
        val request = BenchRequest(mapOf("source" to "retained/capture.jfr", "sha256" to "a".repeat(64),
            codec to payload) + if (events) mapOf("events" to "true") else emptyMap())

        val planned = JfrCaptureSummary.plan(request).single()
        val frozen = BenchProtocol.json.encodeToString(BenchCase.serializer(), planned)
        val restored = BenchProtocol.json.decodeFromString(BenchCase.serializer(), frozen)

        assertEquals(payload, restored.parameters.getValue(codec))
        assertEquals(if (events) "true" else null, restored.parameters["events"])
    }

    @Test
    fun `invalid event selection fails before capture decoding`() {
        val request = BenchRequest(mapOf("source" to "retained/capture.jfr", "sha256" to "a".repeat(64),
            "gzip-base64" to "AAAA", "events" to "yes"))

        assertFailsWith<IllegalArgumentException> { JfrCaptureSummary.plan(request) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["gzip-base64", "zstd-base64"])
    fun `lossless capture transport preserves raw bytes`(codec: String) {
        val bytes = "retained capture bytes".toByteArray()
        val compressed = when (codec) {
            "zstd-base64" -> Zstd.compress(bytes)
            else -> ByteArrayOutputStream().also { output ->
                GZIPOutputStream(output).use { it.write(bytes) }
            }.toByteArray()
        }
        val parameters = mapOf("source" to "retained/capture.jfr",
            "sha256" to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            codec to Base64.getEncoder().encodeToString(compressed))
        val capture = output.resolve("capture.jfr")

        JfrCaptureSummary.decode(parameters, capture)

        assertContentEquals(bytes, Files.readAllBytes(capture))
    }

    @Test
    fun `ambiguous capture codecs fail before decoding`() {
        val request = BenchRequest(mapOf("source" to "retained/capture.jfr", "sha256" to "a".repeat(64),
            "gzip-base64" to "AAAA", "zstd-base64" to "AAAA"))

        assertFailsWith<IllegalArgumentException> { JfrCaptureSummary.plan(request) }
    }
    @ParameterizedTest
    @ValueSource(strings = ["gzip-base64", "zstd-base64"])
    fun `oversized encoded captures fail before decoding`(codec: String) {
        val request = BenchRequest(mapOf("source" to "retained/capture.jfr", "sha256" to "a".repeat(64),
            codec to "A".repeat(2_097_153)))

        assertFailsWith<IllegalArgumentException> { JfrCaptureSummary.plan(request) }
    }
}
