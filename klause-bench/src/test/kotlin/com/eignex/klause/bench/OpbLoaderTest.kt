package com.eignex.klause.bench

import com.eignex.klause.solver.factor.PseudoBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OpbLoaderTest {

    @Test
    fun loadsBundledSetCoverInstance() {
        val entries = OpbLoader.loadBundled()
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("setcover-tiny", entry.name)
        assertEquals(true, entry.expectedSat)
        assertEquals(4, entry.problem.numBoolVars)
        assertEquals(3, entry.problem.factors.size)
        assertEquals(true, entry.problem.factors.all { it is PseudoBoolean })
    }

    @Test
    fun bundledOpbCarriesObjective() {
        val opb = OpbLoader.loadOpb("setcover-tiny")
        val obj = assertNotNull(opb.objective)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), obj.boolWeights.toList())
        assertEquals(0.0, obj.constant)
    }
}
