package com.eignex.klause.bench.profile

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SmtWitnessValidationTest {
    @Test
    fun `gzip transport retains opaque bytes and line endings for either role`() {
        val source = "source\r\n".toByteArray()
        val model = "model\r\n".toByteArray()
        for ((sourceEncoding, modelEncoding) in listOf("gzip" to null, null to "gzip", "gzip" to "gzip")) {
            val input = SmtWitnessValidation.decode(request(source, model,
                sourceEncoding = sourceEncoding, modelEncoding = modelEncoding)).single()

            assertContentEquals(source, input.source)
            assertContentEquals(model, input.model)
        }
    }

    @Test
    fun `gzip sources retain original bytes beyond the raw payload limit`() {
        val source = ByteArray(SmtWitnessValidation.MAX_PAYLOAD_BYTES + 1) { index ->
            when (index % 3) { 0 -> 's'.code.toByte(); 1 -> 13; else -> 10 }
        }
        val model = "(model)\r\n".toByteArray()

        val input = SmtWitnessValidation.decode(request(source, model, sourceEncoding = "gzip")).single()

        assertContentEquals(source, input.source)
        assertContentEquals(model, input.model)
    }

    @Test
    fun `gzip byte counts and hashes describe the original payload in either role`() {
        for (model in listOf(false, true)) for (kind in listOf("hash", "bytes")) {
            val encoded = request("source\r\n".toByteArray(), "model".toByteArray(),
                invalid = kind, sourceEncoding = "gzip", modelEncoding = "gzip", invalidModel = model)

            assertFailsWith<IllegalArgumentException> { SmtWitnessValidation.decode(encoded) }
        }
    }

    @Test
    fun `gzip payloads reject truncated corrupt and uncompressed transports in either role`() {
        val source = "source".toByteArray()
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(source) }
            output.toByteArray()
        }
        val corrupt = compressed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        for (model in listOf(false, true)) {
            for (transport in listOf(compressed.copyOf(compressed.size - 1), corrupt, source)) {
                val encoded = request(source, source, sourceEncoding = "gzip", modelEncoding = "gzip",
                    sourceTransport = transport.takeUnless { model }, modelTransport = transport.takeIf { model })

                assertFailsWith<IllegalArgumentException> { SmtWitnessValidation.decode(encoded) }
            }
        }
    }

    @Test
    fun `gzip inflation cannot exceed the original source limit`() {
        val source = ByteArray(SmtWitnessValidation.MAX_GZIP_SOURCE_BYTES + 1)
        val encoded = request(source, "model".toByteArray(), sourceEncoding = "gzip",
            declaredSourceBytes = SmtWitnessValidation.MAX_GZIP_SOURCE_BYTES)

        assertFailsWith<IllegalArgumentException> { SmtWitnessValidation.decode(encoded) }
    }

    @Test
    fun `gzip models retain the raw model size limit`() {
        val encoded = request("source".toByteArray(), ByteArray(SmtWitnessValidation.MAX_PAYLOAD_BYTES + 1),
            modelEncoding = "gzip")

        assertFailsWith<IllegalArgumentException> { SmtWitnessValidation.decode(encoded) }
    }

    @Test
    fun `gzip payloads accept the declared maximum for each role`() {
        val source = ByteArray(SmtWitnessValidation.MAX_GZIP_SOURCE_BYTES)
        val model = ByteArray(SmtWitnessValidation.MAX_PAYLOAD_BYTES)

        val input = SmtWitnessValidation.decode(request(source, model,
            sourceEncoding = "gzip", modelEncoding = "gzip")).single()

        assertContentEquals(source, input.source)
        assertContentEquals(model, input.model)
    }

    @Test
    fun `unknown encodings cannot enter validation in either role`() {
        for (encoded in listOf(request("source".toByteArray(), "model".toByteArray(), sourceEncoding = "zip"),
            request("source".toByteArray(), "model".toByteArray(), modelEncoding = "zip"))) {
            assertFailsWith<IllegalArgumentException> { SmtWitnessValidation.decode(encoded) }
        }
    }

    @Test
    fun `gzip transport still requires canonical base64 in either role`() {
        val source = "ss".toByteArray()
        val model = "mm".toByteArray()
        val canonical = request(source, model, sourceEncoding = "gzip", modelEncoding = "gzip")
        for (payload in listOf(source, model)) {
            val text = Base64.getDecoder().decode(canonical).decodeToString()
            val compressed = ByteArrayOutputStream().use { output ->
                GZIPOutputStream(output).use { it.write(payload) }
                output.toByteArray()
            }
            val transport = Base64.getEncoder().encodeToString(compressed)
            val encoded = Base64.getEncoder().encodeToString(
                text.replace(transport, transport.trimEnd('=')).toByteArray())

            assertFailsWith<IllegalArgumentException> { SmtWitnessValidation.decode(encoded) }
        }
    }

    @Test
    fun `opaque source and model bytes retain their exact hashes and line endings`() {
        val source = "(assert true)\r\n".toByteArray()
        val model = "(model)\n".toByteArray()
        val input = SmtWitnessValidation.decode(request(source, model)).single()
        assertEquals("input-0", input.id)
        assertContentEquals(source, input.source)
        assertContentEquals(model, input.model)
    }

    @Test
    fun `hash or declared byte mismatches cannot enter validation`() {
        for (kind in listOf("hash", "bytes")) {
            val encoded = request("(assert true)".toByteArray(), "(model)".toByteArray(), invalid = kind)
            assertFailsWith<IllegalArgumentException> { SmtWitnessValidation.decode(encoded) }
        }
    }

    @Test
    fun `oversized input and duplicate case identities are refused before child execution`() {
        for (encoded in listOf("a".repeat(SmtWitnessValidation.MAX_REQUEST_BYTES + 1),
            request("s".toByteArray(), "m".toByteArray(), copies = 2),
            request(ByteArray(SmtWitnessValidation.MAX_PAYLOAD_BYTES + 1), "m".toByteArray()))) {
            assertFailsWith<IllegalArgumentException> { SmtWitnessValidation.decode(encoded) }
        }
    }

    @Test
    fun `extra fields and duplicate JSON keys cannot change the ground only request`() {
        val canonical = Base64.getDecoder().decode(request("s".toByteArray(), "m".toByteArray())).decodeToString()
        for (text in listOf(canonical.replace("\"cases\":", "\"solver\":\"z3\",\"cases\":"),
            canonical.replace("\"cases\":", "\"cases\":[],\"cases\":"))) {
            assertFailsWith<IllegalArgumentException> {
                SmtWitnessValidation.decode(Base64.getEncoder().encodeToString(text.toByteArray()))
            }
        }
    }

    @Test
    fun `every bounded case remains separate even when payloads are identical`() {
        val encoded = request("s".toByteArray(), "m".toByteArray(), copies = SmtWitnessValidation.MAX_CASES,
            uniqueIds = true)
        val inputs = SmtWitnessValidation.decode(encoded)
        assertEquals(SmtWitnessValidation.MAX_CASES, inputs.size)
        assertEquals((0 until SmtWitnessValidation.MAX_CASES).map { "input-$it" }, inputs.map { it.id })
    }

    private fun request(
        source: ByteArray, model: ByteArray, invalid: String? = null, copies: Int = 1, uniqueIds: Boolean = false,
        sourceEncoding: String? = null, sourceTransport: ByteArray? = null,
        declaredSourceBytes: Int = source.size, modelEncoding: String? = null,
        modelTransport: ByteArray? = null, invalidModel: Boolean = false,
    ): String {
        fun payload(bytes: ByteArray, sourcePayload: Boolean, encoding: String? = null) = buildJsonObject {
            val mismatch = sourcePayload != invalidModel
            val size = if (sourcePayload) declaredSourceBytes else bytes.size
            put("bytes", size + if (mismatch && invalid == "bytes") 1 else 0)
            put("sha256", if (mismatch && invalid == "hash") "0".repeat(64) else SmtWitnessValidation.hash(bytes))
            val supplied = if (sourcePayload) sourceTransport else modelTransport
            val transport = supplied ?: if (encoding == "gzip") ByteArrayOutputStream().use { output ->
                GZIPOutputStream(output).use { it.write(bytes) }
                output.toByteArray()
            } else bytes
            put("base64", Base64.getEncoder().encodeToString(transport))
            encoding?.let { put("encoding", it) }
        }
        val json = buildJsonObject {
            put("schema", "smt-ground-witness-request-v1")
            put("cases", buildJsonArray {
                repeat(copies) { index -> add(buildJsonObject {
                    put("id", "input-${if (uniqueIds) index else 0}")
                    put("source", payload(source, true, sourceEncoding))
                    put("model", payload(model, false, modelEncoding))
                }) }
            })
        }
        return Base64.getEncoder().encodeToString(json.toString().toByteArray())
    }
}
