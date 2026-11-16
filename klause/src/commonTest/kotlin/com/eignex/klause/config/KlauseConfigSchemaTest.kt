package com.eignex.klause.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KlauseConfigSchemaTest {
    @Test
    fun `fromProps should apply overrides by property key and leave the rest at base`() {
        val overrides = mapOf(
            "klause.lp.max.tableau.cells" to "2048",
            "klause.lp.ceiling.tableau.cells" to "4096",
            "klause.bitset.threshold" to "256",
        )
        val config = KlauseConfig.fromProps(KlauseConfig.DEFAULT) { overrides[it] }
        assertEquals(2048L, config.lpMaxTableauCells)
        assertEquals(4096L, config.lpCeilingTableauCells)
        assertEquals(256, config.bitsetThreshold)
        assertEquals(KlauseConfig.DEFAULT.largeSpanThreshold, config.largeSpanThreshold)
    }

    @Test
    fun `fromProps should ignore an unparseable value and keep the base`() {
        val config = KlauseConfig.fromProps(KlauseConfig.DEFAULT) { key ->
            if (key == "klause.bitset.threshold") "not-a-number" else null
        }
        assertEquals(KlauseConfig.DEFAULT.bitsetThreshold, config.bitsetThreshold)
    }

    @Test
    fun `global source policy keys should reject overrides with the typed migration path`() {
        val names = listOf("pinAbsentOptVars", "unboundedIntLo", "unboundedIntHi", "floatBuckets", "floatScale",
            "unboundedFloatLo", "unboundedFloatHi")
        val keys = KlauseConfigSchema.keys.map { it.name }.toSet()

        for (name in names) {
            val property = klausePropertyKey(name)
            val error = assertFailsWith<IllegalArgumentException> {
                KlauseConfig.fromProps(KlauseConfig.DEFAULT) { if (it == property) "1" else null }
            }

            assertTrue(name !in keys)
            assertTrue(property in error.message.orEmpty())
            assertTrue("SemanticOverrides" in error.message.orEmpty())
        }
    }
}
