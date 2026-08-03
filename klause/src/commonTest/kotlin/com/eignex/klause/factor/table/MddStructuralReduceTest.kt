package com.eignex.klause.factor.table

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.solver.FactorReduction
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MddStructuralReduceTest {

    // 2-layer MDD accepting exactly (1,2) and (2,1); layer 1 has states s0 (via symbol 1) and s1 (via 2).
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
    fun `a domain-dead state is dropped and the diagram stays solution-equivalent`() {
        // x0 pinned to 1 kills the s0--2-->s1 arc, so layer-1 state s1 becomes unreachable.
        val d0 = IntDomain(1, 1)
        val d1 = IntDomain(1, 2)
        val r = mdd().structuralReduce(arrayOf(d0, d1))
        assertTrue(r is FactorReduction.Rewrite)
        val reduced = (r as FactorReduction.Rewrite).replacement.single() as Mdd
        assertTrue(reduced.numStatesPerLayer.sum() < mdd().numStatesPerLayer.sum(), "a dead state must be removed")
        assertEquals(setOf(listOf(1, 2)), enumerate(reduced, d0, d1), "reduced diagram must accept exactly (1,2)")
    }

    @Test
    fun `a fully reachable diagram is unchanged`() {
        assertEquals(FactorReduction.Unchanged, mdd().structuralReduce(arrayOf(IntDomain(1, 2), IntDomain(1, 2))))
    }
}
