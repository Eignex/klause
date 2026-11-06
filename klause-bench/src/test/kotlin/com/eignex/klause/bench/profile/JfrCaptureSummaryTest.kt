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
import kotlin.test.assertTrue

class JfrCaptureSummaryTest {
    @TempDir
    lateinit var output: Path

    @Test
    fun `caller paths preserve recursive and root context`() {
        val samples = JfrCaptureSummary.Samples(256)

        samples.record(sequenceOf("fold", "fold", "root"), false)
        val path = samples.summary("capture", "hash").getValue("callerPaths").jsonArray.single().jsonObject

        assertEquals(listOf("fold", "fold", "root"), path.getValue("frames").jsonArray.map {
            it.jsonPrimitive.content
        })
        assertEquals(1L, path.getValue("samples").jsonPrimitive.long)
    }

    @Test
    fun `bounded caller paths retain omitted sample accounting`() {
        val samples = JfrCaptureSummary.Samples(256)

        repeat(1025) { samples.record(sequenceOf("caller$it", "root"), false) }
        repeat(2) { samples.record(sequenceOf("caller1024", "root"), false) }
        val summary = samples.summary("capture", "hash")

        assertEquals(1027L, summary.getValue("samples").jsonPrimitive.long)
        assertEquals(1024, summary.getValue("callerPathGroups").jsonPrimitive.int)
        assertEquals(32, summary.getValue("callerPaths").jsonArray.size)
        assertEquals(3L, summary.getValue("callerPathDroppedSamples").jsonPrimitive.long)
        assertEquals(992L, summary.getValue("callerPathUnreportedSamples").jsonPrimitive.long)
    }

    @Test
    fun `deep analysis should retain callers below the default frame limit`() {
        val samples = JfrCaptureSummary.Samples(256)

        samples.record((0 until 64).asSequence().map { "frame$it" } + sequenceOf("caller"), false)
        val summary = samples.summary("capture", "hash")

        assertEquals(256, summary.getValue("stackDepth").jsonPrimitive.int)
        assertEquals(0L, summary.getValue("depthLimitedStacks").jsonPrimitive.long)
        assertTrue(summary.getValue("sampledMethods").jsonArray.any {
            it.jsonObject.getValue("method").jsonPrimitive.content == "caller"
        })
    }

    @ParameterizedTest
    @CsvSource("false, 1", "true, 1", "false, 256", "true, 256")
    fun `requested analysis depth bounds traversal independently of truncation`(truncated: Boolean, depth: Int) {
        val samples = JfrCaptureSummary.Samples(depth)

        samples.record(generateSequence(0) { it + 1 }.map { "frame$it" }, truncated)
        val summary = samples.summary("capture", "hash")

        assertEquals(depth, summary.getValue("sampledMethods").jsonArray.size)
        assertEquals(1L, summary.getValue("depthLimitedStacks").jsonPrimitive.long)
        assertEquals(if (truncated) 1L else 0L, summary.getValue("truncatedStacks").jsonPrimitive.long)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "257", "invalid"])
    fun `invalid analysis depth fails before capture decoding`(depth: String) {
        val request = BenchRequest(mapOf("source" to "retained/capture.jfr", "sha256" to "a".repeat(64),
            "gzip-base64" to "AAAA", "stack-depth" to depth))

        assertFailsWith<IllegalArgumentException> { JfrCaptureSummary.plan(request) }
    }

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
            codec to payload, "stack-depth" to "256") + if (events) mapOf("events" to "true") else emptyMap())

        val planned = JfrCaptureSummary.plan(request).single()
        val frozen = BenchProtocol.json.encodeToString(BenchCase.serializer(), planned)
        val restored = BenchProtocol.json.decodeFromString(BenchCase.serializer(), frozen)

        assertEquals(payload, restored.parameters.getValue(codec))
        assertEquals("256", restored.parameters["stack-depth"])
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
