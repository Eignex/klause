package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The hybrid MIP/CP leaf verdict (issue #1232): a Linear row with an LP-only continuous term does not
 * propagate in CP, so a CP-consistent leaf is a solution only if the residual real LP is feasible. These
 * exercise that verdict — feasible ⇒ SAT, exact-infeasible ⇒ UNSAT, uncertifiable ⇒ UNKNOWN — and that
 * local search declines a model it cannot evaluate.
 */
class LpOnlyContinuousLeafTest {

    private fun problem(numInt: Int, intDoms: Array<IntDomain>, realLo: Double, realHi: Double, row: Linear) =
        Problem(
            numBoolVars = 0,
            numIntVars = numInt,
            intDomains = intDoms,
            factors = arrayOf<Factor>(row),
            numRealVars = 1,
            realLower = doubleArrayOf(realLo),
            realUpper = doubleArrayOf(realHi),
        )

    @Test
    fun `feasible residual real LP withholds SAT pending exact feasibility certification`() {
        // x in [0,3], r in [0,10], row x + r <= 5 is feasible (r = 0), but feasibility of a real LP is not
        // yet exactly certifiable (Phase 8), so the sound verdict is UNKNOWN — never a spurious UNSAT.
        val row = Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.LE, 5L)
        val p = problem(1, arrayOf(IntDomain(0, 3)), 0.0, 10.0, row)
        val r = assertIs<SolveResult.Unknown>(BacktrackSolver(p).solve(BacktrackParams()))
        assertEquals(TerminationReason.Unsupported, r.reason)
    }

    @Test
    fun `infeasible residual real LP is certified UNSAT`() {
        // No discrete variables; r in [0,1] with r >= 5 has no feasible point — exact Farkas certifies it.
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 5L)
        val p = problem(0, emptyArray(), 0.0, 1.0, row)
        assertIs<SolveResult.Unsat>(BacktrackSolver(p).solve(BacktrackParams()))
    }

    @Test
    fun `uncertifiable residual real LP degrades to UNKNOWN rather than UNSAT`() {
        // 0.1 is not dyadic within the rationalization budget: 0.1 r >= 1 is infeasible for r in [0,5] but
        // cannot be certified, so the verdict is UNKNOWN — never an unsound UNSAT.
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(0.1), intArrayOf(0), LinearOp.GE, 1L)
        val p = problem(0, emptyArray(), 0.0, 5.0, row)
        val r = assertIs<SolveResult.Unknown>(BacktrackSolver(p).solve(BacktrackParams()))
        assertEquals(TerminationReason.Unsupported, r.reason)
    }

    @Test
    fun `local search declines a model with LP-only continuous variables`() {
        val row = Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.LE, 5L)
        val p = problem(1, arrayOf(IntDomain(0, 3)), 0.0, 10.0, row)
        val r = assertIs<SolveResult.Unknown>(LocalSearchSolver(p).solve())
        assertEquals(TerminationReason.Unsupported, r.reason)
    }
}
