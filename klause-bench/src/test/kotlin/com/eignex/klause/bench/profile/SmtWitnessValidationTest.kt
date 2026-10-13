package com.eignex.klause.bench.profile

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SmtWitnessValidationTest {
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
    ): String {
        fun payload(bytes: ByteArray, mismatch: Boolean) = buildJsonObject {
            put("bytes", bytes.size + if (mismatch && invalid == "bytes") 1 else 0)
            put("sha256", if (mismatch && invalid == "hash") "0".repeat(64) else SmtWitnessValidation.hash(bytes))
            put("base64", Base64.getEncoder().encodeToString(bytes))
        }
        val json = buildJsonObject {
            put("schema", "smt-ground-witness-request-v1")
            put("cases", buildJsonArray {
                repeat(copies) { index -> add(buildJsonObject {
                    put("id", "input-${if (uniqueIds) index else 0}")
                    put("source", payload(source, true))
                    put("model", payload(model, false))
                }) }
            })
        }
        return Base64.getEncoder().encodeToString(json.toString().toByteArray())
    }
}
