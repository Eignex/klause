package com.eignex.klause.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonSchemaLoaderTest {

    @Test
    fun `loads bundled campaign schema`() {
        val entries = JsonSchemaLoader.loadBundled()
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("campaign", entry.name)
        assertEquals(true, entry.expectedSat)

        assertTrue(entry.problem.numBoolVars >= 4)
        assertEquals(1, entry.problem.numIntVars)
        assertTrue(entry.problem.factors.isNotEmpty())
    }
}
