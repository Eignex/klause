package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearInvariantTest {

    @Test
    fun `not-equal repair steps across a hole`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5).excludeValue(3), IntDomain(0, 5)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.NE, 6)),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 4)
        state.assignment.setInt(1, 2)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val sink = MoveSink()

        state.factors[0].proposeRepairMoves(state, 0, sink)

        val xTargets = sink.list.filterIsInstance<IntSet>().filter { it.varId == 0 }.map { it.newValue }
        assertEquals(setOf(2L, 5L), xTargets.toSet(), "the step below 4 must skip the hole at 3")
    }
}
