package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Move.Compound
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncreasingInvariantTest {

    private fun stateWith(strict: Boolean, values: IntArray, lo: Int = 0, hi: Int = 9): LocalSearchState {
        val n = values.size
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(lo, hi) },
            factors = arrayOf<Factor>(Increasing(IntArray(n) { it }, strict = strict)),
        )
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until n) state.assignment.setInt(i, values[i])
        state.recompute()
        return state
    }

    @Test
    fun `violated when a pair is out of order`() {
        val state = stateWith(strict = false, values = intArrayOf(5, 1, 3))
        assertTrue(state.factors[0].isViolated(state, 0))
        assertTrue(state.factors[0].violationDegree(state, 0) > 0)
    }

    @Test
    fun `satisfied chain has zero violation`() {
        val state = stateWith(strict = true, values = intArrayOf(0, 2, 5))
        assertFalse(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `repair offers local snaps and a cascading compound`() {
        val state = stateWith(strict = false, values = intArrayOf(5, 1, 3))
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        val intSets = sink.list.filterIsInstance<IntSet>()
        // Local snaps at the first inversion (x0=5 > x1=1): pull x0 down to 1, or push x1 up to 5.
        assertTrue(intSets.any { it.varId == 0 && it.newValue == 1 }, "expected IntSet(x0=1) in $intSets")
        assertTrue(intSets.any { it.varId == 1 && it.newValue == 5 }, "expected IntSet(x1=5) in $intSets")
        // A cascading compound re-monotonises the whole chain in one move.
        assertTrue(sink.list.filterIsInstance<Compound>().isNotEmpty(), "expected a cascading Compound")
    }

    @Test
    fun `seedFeasible produces an ordered assignment`() {
        val state = stateWith(strict = true, values = intArrayOf(2, 0, 1), lo = 0, hi = 2)
        assertTrue(state.factors[0].seedFeasible(state, 0))
        val v = IntArray(3) { state.assignment.intValue(it) }
        assertTrue(v[0] < v[1] && v[1] < v[2], "seed not strictly increasing: ${v.toList()}")
    }
}
