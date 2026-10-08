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

    @Test
    fun `a row judged another way replaces a stored proof, a weaker one of the same version does not`() {
        val proof = ReferenceStore.parseRow("miplib2017,neos4,false,-4.84543837043946E10,true,true,900,60000", "highs")
        val corrected = proof.copy(
            objective = -4.86034407505895E10,
            proven = false,
            version = "highs|${MpsWitness.VERSION}",
        )
        val weaker = corrected.copy(objective = null, feasible = null)

        assertEquals(
            listOf(true, false),
            listOf(ReferenceStore.replaces(corrected, proof), ReferenceStore.replaces(weaker, corrected)),
        )
        assertEquals(corrected.version, ReferenceStore.parseRow(ReferenceStore.encodeRow(corrected), "highs").version)
    }
}
