package com.eignex.klause.solver

import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Affine singleton elimination (#318). Checks that the reduced problem has the same SAT/UNSAT
 * verdict as the original and that a reconstructed solution is genuinely feasible in the original.
 */
class AffineEliminationTest {

    private fun isFeasible(problem: Problem, sample: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, sample.bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, sample.ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun verdictSat(problem: Problem): Boolean =
        BacktrackSolver(problem).solve(BacktrackParams()) is SolveResult.Sat

    private fun checkRoundTrip(name: String, original: Problem, expectEliminated: Boolean, expectSat: Boolean) {
        val elim = Presolve.eliminateAffineSingletons(original)
        assertEquals(expectEliminated, elim.problem !== original, "$name: elimination expectation wrong")
        assertEquals(expectSat, verdictSat(original), "$name: original verdict unexpected")
        assertEquals(verdictSat(original), verdictSat(elim.problem), "$name: verdict changed by elimination")
        if (verdictSat(elim.problem)) {
            val reduced = BacktrackSolver(elim.problem).solve(BacktrackParams())
            check(reduced is SolveResult.Sat)
            val full = elim.reconstruct(reduced.assignment)
            assertTrue(isFeasible(original, full), "$name: reconstructed sample infeasible in original")
        }
    }

    @Test
    fun `eliminates x = 2y + 1 defined only by its equality`() {
        // x (0) defined by x - 2y = 1; y (1) also bounded y >= 1. x used nowhere else.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.GE, 1),
            ),
        )
        checkRoundTrip("x=2y+1", problem, expectEliminated = true, expectSat = true)
    }

    @Test
    fun `eliminates with a negative unit coefficient`() {
        // -x + 3y = 2  ⇒  x = 3y - 2.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 20), IntDomain(0, 5)),
            factors = listOf(Linear(intArrayOf(-1, 3), intArrayOf(0, 1), LinearOp.EQ, 2)),
        )
        checkRoundTrip("x=3y-2", problem, expectEliminated = true, expectSat = true)
    }

    @Test
    fun `does not eliminate when x appears in another factor`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 8), // x used here too
            ),
        )
        checkRoundTrip("x-used-twice", problem, expectEliminated = false, expectSat = true)
    }

    @Test
    fun `preserves an unsat verdict`() {
        // x = 2y + 1 with x's domain forcing x even-only via tight bounds that y can't meet.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(4, 4), IntDomain(0, 3)), // x pinned to 4, but 2y+1 is odd
            factors = listOf(Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1)),
        )
        checkRoundTrip("unsat", problem, expectEliminated = true, expectSat = false)
    }
}
