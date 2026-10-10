package com.eignex.klause.factor.global

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.Compound
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncreasingInvariantTest {

    private fun stateWith(strict: Boolean, values: IntArray, lo: Int = 0, hi: Int = 9): LocalSearchState {
        val n = values.size
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(lo.toLong(), hi.toLong()) },
            factors = arrayOf<Factor>(Increasing(IntArray(n) { it }, strict = strict)),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        for (i in 0 until n) state.assignment.setInt(i, values[i].toLong())
        state.recompute()
        return state
    }

    @Test
    fun `repair offers local snaps and a cascading compound`() {
        val state = stateWith(strict = false, values = intArrayOf(5, 1, 3))
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        val intSets = sink.list.filterIsInstance<IntSet>()
        // Local snaps at the first inversion (x0=5 > x1=1): pull x0 down to 1, or push x1 up to 5.
        assertTrue(intSets.any { it.varId == 0 && it.newValue == 1L }, "expected IntSet(x0=1) in $intSets")
        assertTrue(intSets.any { it.varId == 1 && it.newValue == 5L }, "expected IntSet(x1=5) in $intSets")
        // A cascading compound re-monotonises the whole chain in one move.
        assertTrue(sink.list.filterIsInstance<Compound>().isNotEmpty(), "expected a cascading Compound")
    }

    @Test
    fun `cascade rounds a raised value up past a hole`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9).excludeValue(5), IntDomain(0, 9).excludeValue(5)),
            factors = arrayOf<Factor>(Increasing(intArrayOf(0, 1, 2), strict = false)),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 5)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 3)
        state.recompute()
        val sink = MoveSink()

        state.factors[0].proposeRepairMoves(state, 0, sink)

        val compounds = sink.list.filterIsInstance<Compound>()
        assertTrue(
            compounds.any { it.parts == listOf(IntSet(1, 6), IntSet(2, 6)) },
            "expected the raising cascade to land on 6, past the hole at 5, in $compounds",
        )
    }

    @Test
    fun `seedFeasible rounds a chain value up past a hole`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9).excludeValue(5)),
            factors = arrayOf<Factor>(Increasing(intArrayOf(0, 1), strict = true)),
        )
        val state = LocalSearchState(problem.bake(), Random(0), Assumptions(ints = mapOf(0 to 4L)))
        state.assignment.setInt(0, 4)

        assertTrue(state.factors[0].seedFeasible(state, 0))

        assertEquals(6L, state.assignment.intValue(1), "x1 must take the first present value above x0 = 4")
    }
}
