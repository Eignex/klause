package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReferenceStoreTest {
    @Test
    fun `exact objectives survive CSV and order references in either direction`() {
        val boundaries = listOf(
            "9007199254740992" to "9007199254740993",
            "9223372036854775808" to "9223372036854775809",
            "1/3" to "1000000000000000001/3000000000000000000",
        )
        for ((lower, higher) in boundaries) {
            for (maximize in listOf(false, true)) {
                val stored = ReferenceStore.parseRow("s,p,false,1.0,true,false,100,1000").copy(
                    maximize = maximize,
                    exactObjective = if (maximize) lower else higher,
                )
                val better = stored.copy(exactObjective = if (maximize) higher else lower)
                val decoded = ReferenceStore.parseRow(ReferenceStore.encodeRow(better))

                assertEquals(better.exactObjective, decoded.exactObjective)
                assertEquals(true, ReferenceStore.replaces(decoded, stored))
                assertEquals(false, ReferenceStore.replaces(stored, decoded))
            }
        }
    }

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
