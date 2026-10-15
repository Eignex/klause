package com.eignex.klause.factor.table

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MddInvariantTest {

    // Accepts exactly (1, 2) and (2, 1).
    private fun mddFactor(): Factor = Mdd(
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

    @Test
    fun `cost-MDD seed should set the cost var to an edge weight beyond Int range`() {
        val factor = Mdd(
            seq = intArrayOf(0),
            numStatesPerLayer = intArrayOf(1, 1),
            layerStarts = intArrayOf(0, 4),
            transitions = longArrayOf(0, 1, 0, 3_000_000_000L),
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = 4,
            cost = 1,
        )
        val problem = Problem(0, 2, arrayOf(IntDomain(1, 1), IntDomain(0, 5_000_000_000L)), arrayOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        assertTrue(state.factors[0].seedFeasible(state, 0))
        assertEquals(3_000_000_000L, state.assignment.intValue(1))
    }

    @Test
    fun `incremental degree and delta should match a full recompute over a stream of moves`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(mddFactor()),
        )
        val state = LocalSearchState(problem.bake(), Random(7))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.recompute()
        val rng = Random(99)
        repeat(400) { step ->
            val v = rng.nextInt(2)
            val nv = 1L + rng.nextInt(2)
            val before = state.factors[0].violationDegree(state, 0)
            val predicted = state.factors[0].deltaIfIntSet(state, 0, v, nv)
            state.apply(Move.IntSet(v, nv))
            val after = state.factors[0].violationDegree(state, 0)
            assertEquals(after - before, predicted, "step $step")
            val fresh = LocalSearchState(problem.bake(), Random(0))
            for (k in 0 until 2) fresh.assignment.setInt(k, state.assignment.intValue(k))
            fresh.recompute()
            assertEquals(
                fresh.factors[0].violationDegree(fresh, 0),
                state.factors[0].violationDegree(state, 0),
                "step $step",
            )
        }
    }

    @Test
    fun `DP-optimal repair moves should reach an accepted path`() {
        fun seeded(): LocalSearchState {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
                factors = arrayOf(mddFactor()),
            )
            val state = LocalSearchState(problem.bake(), Random(0))
            state.assignment.setInt(0, 1)
            state.assignment.setInt(1, 1)
            state.recompute()
            return state
        }
        val state = seeded()
        assertTrue(state.factors[0].isViolated(state, 0))
        val sink = MoveSink()
        state.factors[0].proposeExtendedRepairMoves(state, 0, sink)
        assertTrue(sink.list.isNotEmpty())
        val check = seeded()
        for (m in sink.list) check.apply(m)
        assertFalse(check.factors[0].isViolated(check, 0))
    }

    @Test
    fun `a cost mdd word should be violated until its cost equals the path weight`() {
        val factor = Mdd(
            seq = intArrayOf(0),
            numStatesPerLayer = intArrayOf(1, 1),
            layerStarts = intArrayOf(0, 8),
            transitions = longArrayOf(0, 1, 0, 2, 0, 2, 0, 4),
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = 4,
            cost = 1,
        )
        val problem = Problem(0, 2, arrayOf(IntDomain(1, 2), IntDomain(0, 5)), arrayOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 4)
        state.recompute()
        val invariant = state.factors[0]

        val offBefore = invariant.isViolated(state, 0)
        val delta = invariant.deltaIfIntSet(state, 0, 1, 2L)

        assertTrue(offBefore)
        assertEquals(-invariant.violationDegree(state, 0), delta)
    }
}
