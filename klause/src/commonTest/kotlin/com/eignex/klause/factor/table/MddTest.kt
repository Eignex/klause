package com.eignex.klause.factor.table

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MddTest {

    // Accepts exactly (1, 2) and (2, 1).
    private fun mdd() = Mdd(
        seq = intArrayOf(0, 1),
        numStatesPerLayer = intArrayOf(1, 2, 1),
        layerStarts = intArrayOf(0, 6, 12),
        transitions = longArrayOf(
            0, 1, 0, 0, 2, 1,
            0, 2, 0, 1, 1, 0,
        ),
        initial = 0,
        accepting = intArrayOf(0),
        recordStride = 3,
    )

    private fun enumerate(mdd: Mdd, d0: IntDomain, d1: IntDomain): Set<List<Int>> {
        val problem = Problem(numBoolVars = 0, numIntVars = 2, intDomains = arrayOf(d0, d1), factors = arrayOf(mdd))
        val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
        return BacktrackSolver(
            problem.bake(),
        ).enumerate(params).take(100_000).map { it.ints.map { v -> v.toInt() } }.toHashSet()
    }

    @Test
    fun `a domain-dead state should be dropped while the diagram stays solution-equivalent`() {
        val d0 = IntDomain(1, 1)
        val d1 = IntDomain(1, 2)
        val r = mdd().structuralReduce(arrayOf(d0, d1))
        assertTrue(r is FactorReduction.Rewrite)
        val reduced = r.replacement.single() as Mdd
        assertTrue(reduced.numStatesPerLayer.sum() < mdd().numStatesPerLayer.sum())
        assertEquals(setOf(listOf(1, 2)), enumerate(reduced, d0, d1))
    }
}
