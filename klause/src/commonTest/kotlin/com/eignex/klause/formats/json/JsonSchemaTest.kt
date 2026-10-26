package com.eignex.klause.formats.json

import com.eignex.klause.compile.compile
import com.eignex.klause.formats.FormatException
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
                "type": { "${'$'}type": "nominal", "labels": ["a", "b", "c"] },
                "budget": { "${'$'}type": "int", "min": 0, "max": 100 }
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
        val text = """
            {
              "entries": {
                "budget": { "${'$'}type": "int", "min": 0, "max": 100, "maxx": 5 }
              }
            }
        """.trimIndent()
        assertFailsWith<FormatException> { JsonSchema.parse(text) }
    }

    @Test
    fun `the compiler should wrap a malformed document as a format exception`() {
        assertFailsWith<FormatException> { JsonSchema.parse("{ not json") }
    }

    @Test
    fun `the compiler should wrap invalid schema values as a format exception`() {
        val text = """
            {
              "entries": {
                "budget": { "${'$'}type": "int", "min": 100, "max": 0 }
              }
            }
        """.trimIndent()

        assertFailsWith<FormatException> { compile(text) }
    }
}
