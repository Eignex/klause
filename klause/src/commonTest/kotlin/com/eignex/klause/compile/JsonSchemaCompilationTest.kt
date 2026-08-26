package com.eignex.klause.compile

import com.eignex.klause.formats.json.JsonSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonSchemaCompilationTest {

    @Test
    fun `compiles decoded schema entries`() {
        val text = """
            {
              "entries": {
                "type": { "${'$'}type": "nominal", "labels": ["a", "b", "c"] },
                "budget": { "${'$'}type": "int", "min": 0, "max": 100 }
              }
            }
        """.trimIndent()

        val compiled = compileSchema(JsonSchema.decode(text))

        assertEquals(3, compiled.problem.numBoolVars, "nominal expands to 3 indicators")
        assertEquals(1, compiled.problem.numIntVars)
        assertEquals(setOf("a", "b", "c"), compiled.nominalIndicators["type"]?.keys)
        assertTrue("budget" in compiled.intVarIdByName)
    }

    @Test
    fun `rejects invalid decoded schema values`() {
        val text = """
            {
              "entries": {
                "budget": { "${'$'}type": "int", "min": 100, "max": 0 }
              }
            }
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> { compileSchema(JsonSchema.decode(text)) }
    }
}
