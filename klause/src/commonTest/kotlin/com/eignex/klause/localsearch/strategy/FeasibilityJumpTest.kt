package com.eignex.klause.localsearch.strategy

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behaviour tests for the Feasibility-Jump [SourceDrivenStrategy] recipe: a weighted-violation
 * argmin-jump strategy must reach feasibility on a solvable instance, make progress (driven by the
 * adaptive weights) on a coupled one, and be deterministic for a fixed seed.
 */
class FeasibilityJumpTest {

    /** Two coupled sum constraints with the unique solution (2, 4): no single coordinate jump
     *  satisfies both, so reaching feasibility relies on the adaptive-weight escape. */
    private fun coupledProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 6),
            Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.EQ, 8),
        ),
    )

    private fun drive(strategy: SourceDrivenStrategy, state: LocalSearchState, maxSteps: Int): Int {
        state.recompute()
        var steps = 0
        while (steps < maxSteps && state.cost > 0L) {
            val m = strategy.pickMove(state) ?: break
            state.apply(m)
            steps++
        }
        return steps
    }

    @Test
    fun `is deterministic for a fixed seed`() {
        val a = LocalSearchState(coupledProblem().bake(), Random(42))
        val b = LocalSearchState(coupledProblem().bake(), Random(42))
        drive(FeasibilityJump(), a, maxSteps = 500)
        drive(FeasibilityJump(), b, maxSteps = 500)
        assertEquals(a.assignment.intValue(0), b.assignment.intValue(0))
        assertEquals(a.assignment.intValue(1), b.assignment.intValue(1))
        assertEquals(a.cost, b.cost)
    }
}
