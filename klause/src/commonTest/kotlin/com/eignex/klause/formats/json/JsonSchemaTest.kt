package com.eignex.klause.formats.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonSchemaTest {

    @Test
    fun `parses minimal schema with bool var`() {
        val text = """
            {
              "entries": {
                "premium": { "${'$'}type": "bool" }
              }
            }
        """.trimIndent()
        val problem = JsonSchema.parseProblem(text)
        assertEquals(1, problem.numBoolVars)
        assertEquals(0, problem.numIntVars)
    }

    @Test
    fun `parses int and nominal entries`() {
        val text = """
            {
              "entries": {
                "type": { "${'$'}type": "nominal", "labels": ["a", "b", "c"] },
                "budget": { "${'$'}type": "int", "min": 0, "max": 100 }
              }
            }
        """.trimIndent()
        val compiled = JsonSchema.parseCompiled(text)
        assertEquals(3, compiled.problem.numBoolVars, "nominal expands to 3 indicators")
        assertEquals(1, compiled.problem.numIntVars)
        // CompiledProblem retains the var/indicator maps for decoding samples.
        assertTrue(compiled.boolVarIdByName.isNotEmpty())
    }
}
