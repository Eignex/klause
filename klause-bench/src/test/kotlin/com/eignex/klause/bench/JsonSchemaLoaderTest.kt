package com.eignex.klause.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonSchemaLoaderTest {

    @Test
    fun loadsBundledCampaignSchema() {
        val entries = JsonSchemaLoader.loadBundled()
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("campaign", entry.name)
        assertEquals(true, entry.expectedSat)
        // CampaignSchema declares: 1 bool (premium) + 1 nominal × 3 labels (type, encoded
        // as 3 indicator booleans + 1 mutual-exclusion factor) + 1 int (budget). The two
        // declared constraints lower into additional factors.
        assertTrue(entry.problem.numBoolVars >= 4)
        assertEquals(1, entry.problem.numIntVars)
        assertTrue(entry.problem.factors.isNotEmpty())
    }
}
