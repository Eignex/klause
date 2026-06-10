package com.eignex.klause.solver

import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertTrue

class DecomposingSolverTest {

    private data class Case(val name: String, val problem: Problem, val sat: Boolean)

    /** Feasibility oracle: pin every variable to its sampled value and propagate. */
    private fun isFeasible(problem: Problem, sample: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, sample.bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, sample.ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    @Test
    fun `decomposed verdict matches the monolithic solver and samples are feasible`() {
        for (case in cases()) {
            val decomposed = DecomposingSolver(case.problem).solve(BacktrackParams())
            val monolithic = BacktrackSolver(case.problem).solve(BacktrackParams())
            assertTrue(
                (decomposed is SolveResult.Sat) == (monolithic is SolveResult.Sat),
                "${case.name}: verdict diverged (decomposed=$decomposed monolithic=$monolithic)",
            )
            assertTrue((decomposed is SolveResult.Sat) == case.sat, "${case.name}: unexpected verdict $decomposed")
            if (decomposed is SolveResult.Sat) {
                assertTrue(isFeasible(case.problem, decomposed.assignment), "${case.name}: stitched sample infeasible")
            }
        }
    }

    private fun lit(v: Int, positive: Boolean) = Lit.make(v, positive)

    private fun cases(): List<Case> = listOf(
        Case(
            "twoIndependentBoolComponents",
            Problem(
                numBoolVars = 4,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = listOf(
                    Clause(intArrayOf(lit(0, true), lit(1, true))),
                    Clause(intArrayOf(lit(0, false), lit(1, false))),
                    Clause(intArrayOf(lit(2, true), lit(3, true))),
                    Clause(intArrayOf(lit(2, false), lit(3, false))),
                ),
            ),
            sat = true,
        ),
        Case(
            "boolAndIntComponents",
            Problem(
                numBoolVars = 2,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
                factors = listOf(
                    Clause(intArrayOf(lit(0, true), lit(1, true))),
                    Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
                ),
            ),
            sat = true,
        ),
        Case(
            "oneComponentUnsat",
            Problem(
                numBoolVars = 3,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = listOf(
                    Clause(intArrayOf(lit(0, true), lit(1, true))), // satisfiable component
                    Clause(intArrayOf(lit(2, true))), // contradictory component over var 2
                    Clause(intArrayOf(lit(2, false))),
                ),
            ),
            sat = false,
        ),
        Case(
            "singleComponentChain",
            Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
                factors = listOf(
                    Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4),
                    Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 2),
                ),
            ),
            sat = true,
        ),
    )
}
