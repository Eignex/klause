package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class InvariantTest {

    @Test
    fun `default repair steps to the present neighbours across a hole`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5).excludeValue(3), IntDomain(0, 5)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10)),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 4)
        state.recompute()
        val sink = MoveSink()

        object : Invariant {}.proposeRepairMoves(state, 0, sink)

        val xTargets = sink.list.filterIsInstance<Move.IntSet>().filter { it.varId == 0 }.map { it.newValue }
        assertEquals(setOf(2L, 5L), xTargets.toSet(), "the step below 4 must skip the hole at 3")
    }
}
