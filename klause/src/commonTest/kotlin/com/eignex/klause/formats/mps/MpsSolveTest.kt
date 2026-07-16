package com.eignex.klause.formats.mps

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** End-to-end: an MPS instance with an LP-only continuous column solves through the hybrid engine, and
 *  the continuous variable's value is carried on the solution [com.eignex.klause.solver.Sample.reals]. */
class MpsSolveTest {

    @Test
    fun `solves a mixed integer-continuous MPS instance and reports the continuous value`() {
        // minimize x  s.t.  x + y <= 5,  x in [0,10] real, y in [0,3] integer.  Optimum x = 0.
        val m = MpsModel(
            name = "m",
            sense = ObjectiveSense.MINIMIZE,
            objective = MpsObjective("obj", intArrayOf(0), doubleArrayOf(1.0), 0.0),
            variables = listOf(
                MpsVar("x", integer = false, lower = 0.0, upper = 10.0),
                MpsVar("y", integer = true, lower = 0.0, upper = 3.0),
            ),
            constraints = listOf(
                MpsConstraint("C1", intArrayOf(0, 1), doubleArrayOf(1.0, 1.0), lower = null, upper = 5.0),
            ),
        )
        val compiled = m.toProblem()
        assertEquals(1, compiled.problem.numRealVars)
        assertEquals(1, compiled.problem.numIntVars)

        // The satisfaction path returns a feasible completion; its continuous part rides on reals.
        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(compiled.problem).solve(BacktrackParams()))
        assertTrue(sat.assignment.reals.isNotEmpty(), "continuous value missing from the solution")
        val x = sat.assignment.reals[0]
        val y = sat.assignment.ints[0]
        assertTrue(x in 0.0..10.0 && x + y <= 5.0 + 1e-6, "infeasible continuous completion: x=$x y=$y")

        // The optimize path drives the objective (min x) to its true optimum 0, resolved by the leaf LP.
        val opt = BacktrackSolver(compiled.problem).minimize(compiled.objective!!, BacktrackParams())
        val best = assertIs<MinimizeResult.Optimal>(opt)
        assertEquals(0.0, best.sample.reals[0], 1e-6)
        assertEquals(0.0, best.objective, 1e-6)
    }

    @Test
    fun `proves an infeasible continuous MPS instance UNSAT`() {
        // x >= 5 with x in [0,1] real has no feasible point (exact Farkas certifies it).
        val m = MpsModel(
            name = "m",
            sense = ObjectiveSense.MINIMIZE,
            objective = MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            variables = listOf(MpsVar("x", integer = false, lower = 0.0, upper = 1.0)),
            constraints = listOf(MpsConstraint("C1", intArrayOf(0), doubleArrayOf(1.0), lower = 5.0, upper = null)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(m.toProblem().problem).solve(BacktrackParams()))
    }
}
