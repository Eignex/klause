package com.eignex.klause.localsearch.strategy

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Behaviour tests for the Feasibility-Jump [SourceDrivenStrategy] recipe: a weighted-violation
 * argmin-jump strategy must reach feasibility on a solvable instance, make progress (driven by the
 * adaptive weights) on a coupled one, and be deterministic for a fixed seed.
 */
class FeasibilityJumpTest {

    @Test
    fun `stall kicks preserve maintained Boolean outputs`() {
        for (defined in listOf(false, true)) {
            val problem = Problem(
                1, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, false)))),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            if (defined) {
                val folds = listOf(BoolFoldDefinition(0, intArrayOf(), true))
                state.invariants = assertNotNull(DefinitionalSweep.infer(problem, boolFolds = folds)).network(0, 1)
            }
            state.assignment.setBool(0, true)
            state.recompute()
            val perturbation = StallPerturbation(1)
            assertNull(perturbation(state))
            state.tabu.step++

            val move = perturbation(state)

            assertEquals(if (defined) null else Move.BoolFlip(0), move)
        }
    }

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
