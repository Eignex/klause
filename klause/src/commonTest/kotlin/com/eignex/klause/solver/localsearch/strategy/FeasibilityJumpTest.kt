package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behaviour tests for the Feasibility-Jump [SourceDrivenStrategy] recipe: a weighted-violation
 * argmin-jump strategy must reach feasibility on a solvable instance, make progress (driven by the
 * adaptive weights) on a coupled one, and be deterministic for a fixed seed.
 */
class FeasibilityJumpTest {

    /** A single sum constraint `x0 + x1 = 6` over 0..5 — one jump to the argmin value of either
     *  variable zeroes it. */
    private fun sumProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 6)),
    )

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
    fun `reaches feasibility on a solvable instance`() {
        val state = LocalSearchState(sumProblem(), Random(7))
        drive(FeasibilityJump(), state, maxSteps = 100)
        assertEquals(0L, state.cost, "FJ must reach feasibility on x0 + x1 = 6")
    }

    @Test
    fun `makes progress on a coupled instance via adaptive weights`() {
        val state = LocalSearchState(coupledProblem(), Random(7))
        state.recompute()
        val before = state.cost
        drive(FeasibilityJump(), state, maxSteps = 10_000)
        assertTrue(state.cost < before, "weight-escalating FJ must reduce violation (was $before, got ${state.cost})")
    }

    @Test
    fun `is deterministic for a fixed seed`() {
        val a = LocalSearchState(coupledProblem(), Random(42))
        val b = LocalSearchState(coupledProblem(), Random(42))
        drive(FeasibilityJump(), a, maxSteps = 500)
        drive(FeasibilityJump(), b, maxSteps = 500)
        assertEquals(a.assignment.intValue(0), b.assignment.intValue(0))
        assertEquals(a.assignment.intValue(1), b.assignment.intValue(1))
        assertEquals(a.cost, b.cost)
    }
}
