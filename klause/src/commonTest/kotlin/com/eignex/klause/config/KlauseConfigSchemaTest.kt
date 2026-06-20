package com.eignex.klause.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KlauseConfigSchemaTest {

    @Test
    fun `property key derives from the knob name as a dotted klause path`() {
        val keys = KlauseConfigSchema.keys.associateBy { it.name }
        assertEquals("klause.float.buckets", keys.getValue("floatBuckets").propertyKey)
        assertEquals("klause.lp.max.tableau.cells", keys.getValue("lpMaxTableauCells").propertyKey)
        assertEquals("klause.bitset.threshold", keys.getValue("bitsetThreshold").propertyKey)
    }

    @Test
    fun `fromProps applies overrides looked up by property key and leaves the rest at base`() {
        val overrides = mapOf(
            "klause.float.buckets" to "2048",
            "klause.bitset.threshold" to "256",
            "klause.pin.absent.opt.vars" to "off",
        )
        val config = KlauseConfig.fromProps(KlauseConfig.DEFAULT) { overrides[it] }
        assertEquals(2048, config.floatBuckets)
        assertEquals(256, config.bitsetThreshold)
        assertTrue(!config.pinAbsentOptVars)
        // An untouched knob keeps the base value.
        assertEquals(KlauseConfig.DEFAULT.floatScale, config.floatScale)
    }

    @Test
    fun `fromProps ignores an unparseable value and keeps the base`() {
        val config = KlauseConfig.fromProps(KlauseConfig.DEFAULT) { key ->
            if (key == "klause.float.buckets") "not-a-number" else null
        }
        assertEquals(KlauseConfig.DEFAULT.floatBuckets, config.floatBuckets)
    }
}
