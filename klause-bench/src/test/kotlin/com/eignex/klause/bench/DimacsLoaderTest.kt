package com.eignex.klause.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DimacsLoaderTest {

    @Test
    fun loadsAllBundledInstances() {
        val entries = DimacsLoader.loadBundled()
        // Three pre-made files: php4 (unsat), random3sat-20-80 (sat), random3sat-50-200 (sat).
        assertEquals(3, entries.size)
        val byName = entries.associateBy { it.name }
        assertTrue("php4" in byName)
        assertTrue("random3sat-20-80" in byName)
        assertTrue("random3sat-50-200" in byName)
        assertEquals(false, byName["php4"]!!.expectedSat)
        assertEquals(true, byName["random3sat-20-80"]!!.expectedSat)
        assertEquals(true, byName["random3sat-50-200"]!!.expectedSat)
    }

    @Test
    fun bundledShapesMatchHeaders() {
        val php4 = DimacsLoader.loadProblem("php4")
        assertEquals(20, php4.numBoolVars)
        assertEquals(45, php4.factors.size)

        val r20 = DimacsLoader.loadProblem("random3sat-20-80")
        assertEquals(20, r20.numBoolVars)
        assertEquals(80, r20.factors.size)

        val r50 = DimacsLoader.loadProblem("random3sat-50-200")
        assertEquals(50, r50.numBoolVars)
        assertEquals(200, r50.factors.size)
    }
}
