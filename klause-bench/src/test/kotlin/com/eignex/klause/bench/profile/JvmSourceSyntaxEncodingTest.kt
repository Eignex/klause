package com.eignex.klause.bench.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSourceSyntaxEncodingTest {
    private val row = Json.parseToJsonElement("""
        {"index":0,"parentIndex":null,"kind":"function","psiKind":"example.Function","name":"",
         "role":null,"package":"p","range":{"startUtf16":0,"endUtf16Exclusive":2,"startByte":0,
         "endByteExclusive":4,"firstLine":1,"lastLine":1,"sha256":"original"},"syntaxError":null,
         "referencedName":"\u03a9","symbolResolutionProven":false,"jvmMemberOrGeneratedBodyInferred":false}
    """.trimIndent()).jsonObject

    @Test
    fun `all node values and object order survive column encoding`() {
        val encoding = JvmSourceSyntaxEncoding()
        val kinds = ArrayList<Pair<Int, String>>()

        val encoded = encoding.encode(row) { index, name -> kinds.add(index to name) }

        assertEquals(listOf(0 to "example.Function"), kinds)
        assertEquals(row.toString(), encoding.expand(encoded).toString())
    }

    @Test
    fun `a repeated PSI kind retains one stable dictionary identity`() {
        val encoding = JvmSourceSyntaxEncoding()
        val kinds = ArrayList<Pair<Int, String>>()

        val first = encoding.encode(row) { index, name -> kinds.add(index to name) }
        val second = encoding.encode(row) { index, name -> kinds.add(index to name) }

        assertEquals(first, second)
        assertEquals(listOf(0 to "example.Function"), kinds)
    }

    @Test
    fun `distinct PSI kinds remain separately reconstructable`() {
        val encoding = JvmSourceSyntaxEncoding()
        val other = JsonObject(row + ("psiKind" to JsonPrimitive("example.Other")))

        val first = encoding.encode(row) { _, _ -> }
        val second = encoding.encode(other) { _, _ -> }

        assertEquals(row, encoding.expand(first))
        assertEquals(other, encoding.expand(second))
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "unknown", "order"])
    fun `unknown and incomplete schemas cannot omit node values`(variant: String) {
        val changed = when (variant) {
            "missing" -> JsonObject(row - "name")
            "unknown" -> JsonObject(row + ("other" to JsonPrimitive(true)))
            else -> JsonObject(row.entries.reversed().associate { it.key to it.value })
        }

        assertFailsWith<IllegalArgumentException> { JvmSourceSyntaxEncoding().encode(changed) { _, _ -> } }
    }

    @Test
    fun `incomplete column rows cannot be expanded as complete nodes`() {
        assertFailsWith<IllegalArgumentException> { JvmSourceSyntaxEncoding().expand(JsonArray(emptyList())) }
    }
}
