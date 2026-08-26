package com.eignex.klause.compile

import com.eignex.klause.formats.FormatException
import com.eignex.klause.formats.json.JsonSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonSchemaCompilerTest {

    private fun compile(text: String) = compileSchema(JsonSchema.decode(text))

    @Test
    fun `decodes minimal schema with bool var`() {
        val text = """
            {
              "entries": {
                "premium": { "${'$'}type": "bool" }
              }
            }
        """.trimIndent()
        val problem = compile(text).problem
        assertEquals(1, problem.numBoolVars)
        assertEquals(0, problem.numIntVars)
    }

    @Test
    fun `decodes int and nominal entries`() {
        val text = """
            {
              "entries": {
                "type": { "${'$'}type": "nominal", "labels": ["a", "b", "c"] },
                "budget": { "${'$'}type": "int", "min": 0, "max": 100 }
              }
            }
        """.trimIndent()
        val compiled = compile(text)
        assertEquals(3, compiled.problem.numBoolVars, "nominal expands to 3 indicators")
        assertEquals(1, compiled.problem.numIntVars)
        assertEquals(setOf("a", "b", "c"), compiled.nominalIndicators["type"]?.keys)
        assertTrue("budget" in compiled.intVarIdByName)
    }

    @Test
    fun `rejects an unknown key as a format exception`() {
        val text = """
            {
              "entries": {
                "budget": { "${'$'}type": "int", "min": 0, "max": 100, "maxx": 5 }
              }
            }
        """.trimIndent()
        assertFailsWith<FormatException> { JsonSchema.decode(text) }
    }

    @Test
    fun `wraps a malformed document as a format exception`() {
        assertFailsWith<FormatException> { JsonSchema.decode("{ not json") }
    }

    @Test
    fun `wraps invalid schema values as a format exception`() {
        val text = """
            {
              "entries": {
                "budget": { "${'$'}type": "int", "min": 100, "max": 0 }
              }
            }
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> { compile(text) }
    }
}
