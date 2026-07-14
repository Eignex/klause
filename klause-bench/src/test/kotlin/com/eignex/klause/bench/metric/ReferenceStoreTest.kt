package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReferenceStoreTest {
    @Test
    fun `a legacy oracle-only row decodes with blank features`() {
        val e = ReferenceStore.parseRow("hakank,queens/q8,false,8.0,true,true,912,300000")
        assertEquals("hakank", e.suite)
        assertEquals(8.0, e.objective)
        assertEquals("", e.format, "features absent in a pre-classify row")
        assertEquals("", e.structure)
        assertNull(e.numGlobal)
        assertNull(e.boolHeavy)
    }

    @Test
    fun `a full row decodes its feature columns`() {
        val e = ReferenceStore.parseRow(
            "minizinc-benchmarks,q/q8,false,8.0,true,true,912,300000,minizinc,global,3,1,false",
        )
        assertEquals("minizinc", e.format)
        assertEquals("global", e.structure)
        assertEquals(3, e.numGlobal)
        assertEquals(1, e.numLinear)
        assertEquals(false, e.boolHeavy)
    }
}
