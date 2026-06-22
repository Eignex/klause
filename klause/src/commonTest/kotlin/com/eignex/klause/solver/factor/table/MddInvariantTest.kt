package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.table.Mdd
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MddInvariantTest {

    /** MDD accepting exactly (1,2) and (2,1) over a 2-symbol alphabet. */
    private fun mddFactor(): Factor = Mdd(
        seq = intArrayOf(0, 1),
        numStatesPerLayer = intArrayOf(1, 2, 1),
        layerStarts = intArrayOf(0, 6, 12),
        transitions = intArrayOf(
            0, 1, 0, 0, 2, 1, // layer 0: s0 --1--> s0; s0 --2--> s1
            0, 2, 0, 1, 1, 0, // layer 1: s0 --2--> term; s1 --1--> term
        ),
        initial = 0,
        accepting = intArrayOf(0),
        recordStride = 3,
    )

    @Test
    fun `satisfied when assignment follows an accepted path`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(mddFactor()),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1) // (1,2) is accepted
        state.assignment.setInt(1, 2)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        assertEquals(0, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `violated when assignment follows a rejected path`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(mddFactor()),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1) // (1,1) is rejected
        state.assignment.setInt(1, 1)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        assertTrue(state.factors[0].violationDegree(state, 0) > 0)
    }

    @Test
    fun `delta is negative when move leads to accepted path`() {
        // Violated: (1,1). Setting seq[1]=2 → (1,2) accepted → delta < 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(mddFactor()),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val delta = state.factors[0].deltaIfIntSet(state, 0, intVar = 1, newValue = 2)
        assertTrue(delta < 0, "move to (1,2) should reduce violation; delta=$delta")
    }

    @Test
    fun `ls solver finds only accepted words`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(mddFactor()),
        )
        val solver = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 100))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 2_000, randomSeed = 0)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val a = s.ints[0]
            val b = s.ints[1]
            assertTrue(
                (a == 1 && b == 2) || (a == 2 && b == 1),
                "rejected word ($a,$b)",
            )
        }
    }
}
