package com.eignex.klause.formats.json

import com.eignex.klause.compile.compile
import com.eignex.klause.formats.FormatException
import com.eignex.klause.model.SchemaEntrySerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonSchemaTest {
    private fun compile(text: String) = JsonSchema.parse(text).compile()

    @Test
    fun `the compiler should parse int and nominal entries`() {
        val text = """
            {
              "entries": {
                "type": { "nominal": ["a", "b", "c"] },
                "budget": { "int": [0, 100] }
              }
            }
        """.trimIndent()
        val compiled = compile(text)
        assertEquals(3, compiled.problem.numBoolVars)
        assertEquals(1, compiled.problem.numIntVars)
        assertEquals(setOf("a", "b", "c"), compiled.nominalIndicators["type"]?.keys)
        assertTrue("budget" in compiled.intVarIdByName)
    }

    @Test
    fun `the compiler should reject an unknown key as a format exception`() {
        val text = """{ "entries": { "ratio": { "float": { "min": 0.0, "max": 1.0, "maxx": 5 } } } }"""
        assertFailsWith<FormatException> { JsonSchema.parse(text) }
    }

    @Test
    fun `the compiler should wrap a malformed document as a format exception`() {
        assertFailsWith<FormatException> { JsonSchema.parse("{ not json") }
    }

    @Test
    fun `the compiler should wrap invalid schema values as a format exception`() {
        val text = """{ "entries": { "budget": { "int": [100, 0] } } }"""

        assertFailsWith<FormatException> { compile(text) }
    }

    @Test
    fun `parse errors should name what is malformed`() {
        val cases = listOf(
            """{ "entries": { "budget": { "int": [0, 9.5] } } }""" to "'9.5' as an int",
            """{ "entries": { "c": { "constraint": { "ge": [{ "ref": "x" }] } } } }""" to "'ge' expects 2 operands",
            """{ "entries": {}, "extra": 1 }""" to "unknown key 'extra'",
        )

        for ((text, path) in cases) {
            val error = assertFailsWith<FormatException>(text) { JsonSchema.parse(text) }
            assertTrue(path in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `the compiler should reject discriminator documents`() {
        val text = """{ "entries": { "budget": { "${'$'}type": "int", "min": 0, "max": 100 } } }"""

        assertFailsWith<FormatException> { JsonSchema.parse(text) }
    }

    @Test
    fun `the JSON Schema should describe entries with the compact entry vocabulary`() {
        val document = JsonSchema.jsonSchema()

        val definitions = document.getValue("\$defs").jsonObject
        val root = definitions.getValue(document.getValue("\$ref").jsonPrimitive.content.substringAfterLast('/'))
        val entries = root.jsonObject.getValue("properties").jsonObject.getValue("entries").jsonObject
        val entry = entries.getValue("additionalProperties").jsonObject.getValue("\$ref").jsonPrimitive.content
        assertEquals(JsonPrimitive("https://json-schema.org/draft/2020-12/schema"), document["\$schema"])
        assertEquals("#/\$defs/${SchemaEntrySerializer.serialName}", entry)
        val branches = definitions.getValue(SchemaEntrySerializer.serialName).jsonObject.getValue("anyOf").jsonArray
        assertEquals(SchemaEntrySerializer.nodes.size, branches.size)
    }
}
