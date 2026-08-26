package com.eignex.klause.solver.mps

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.mps.MpsConstraint
import com.eignex.klause.formats.mps.MpsModel
import com.eignex.klause.formats.mps.MpsObjective
import com.eignex.klause.formats.mps.MpsVar
import com.eignex.klause.formats.mps.problem
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.eignex.klause.ir.ObjectiveSense as ObjectiveDirection

class MpsSolveTest {
    @Test
    fun `solves a mixed integer-continuous model and reports the continuous value`() {
        val model = MpsModel(
            name = "m",
            sense = ObjectiveDirection.MINIMIZE,
            objective = MpsObjective("obj", intArrayOf(0), doubleArrayOf(1.0), 0.0),
            variables = listOf(
                MpsVar("x", integer = false, lower = 0.0, upper = 10.0),
                MpsVar("y", integer = true, lower = 0.0, upper = 3.0),
            ),
            constraints = listOf(
                MpsConstraint("C1", intArrayOf(0, 1), doubleArrayOf(1.0, 1.0), lower = null, upper = 5.0),
            ),
        )
        val compiled = model.toProblem()
        assertEquals(1, compiled.problem.numRealVars)
        assertEquals(1, compiled.problem.numIntVars)

        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams()))
        assertTrue(sat.assignment.reals.isNotEmpty())
        val x = sat.assignment.reals[0]
        val y = sat.assignment.ints[0]
        assertTrue(x in 0.0..10.0 && x + y <= 5.0 + 1e-6)

        val best = assertIs<MinimizeResult.Optimal>(
            BacktrackSolver(compiled.problem.bake()).minimize(compiled.objective!!, BacktrackParams()),
        )
        assertEquals(0.0, best.sample.reals[0], 1e-6)
        assertEquals(0.0, best.objective, 1e-6)
    }

    @Test
    fun `refutes a model whose only infeasibility is stated in coefficients finer than a millionth`() {
        // `1e-7·x >= 1e-7` is `x >= 1`, which the `x <= 0` row contradicts. Rounding the row onto a
        // coarser grid empties it into `0 >= 0`, and the model reads as satisfiable at x = 0.
        val model = MpsModel(
            name = "m",
            sense = ObjectiveDirection.MINIMIZE,
            objective = MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            variables = listOf(MpsVar("x", integer = true, lower = 0.0, upper = 10.0)),
            constraints = listOf(
                MpsConstraint("C1", intArrayOf(0), doubleArrayOf(1e-7), lower = 1e-7, upper = null),
                MpsConstraint("C2", intArrayOf(0), doubleArrayOf(1.0), lower = null, upper = 0.0),
            ),
        )

        assertIs<SolveResult.Unsat>(BacktrackSolver(model.toProblem().problem.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `proves an infeasible continuous model unsat`() {
        val model = MpsModel(
            name = "m",
            sense = ObjectiveDirection.MINIMIZE,
            objective = MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            variables = listOf(MpsVar("x", integer = false, lower = 0.0, upper = 1.0)),
            constraints = listOf(MpsConstraint("C1", intArrayOf(0), doubleArrayOf(1.0), lower = 5.0, upper = null)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(model.toProblem().problem.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a declared empty integer domain is unsat`() {
        val model = MpsModel(
            name = "emptyclosed",
            sense = ObjectiveDirection.MINIMIZE,
            objective = MpsObjective("obj", intArrayOf(0), doubleArrayOf(1.0), 0.0),
            variables = listOf(
                MpsVar("x", integer = true, lower = 5.0, upper = 3.0),
                MpsVar("y", integer = true, lower = 0.0, upper = 8.0),
            ),
            constraints = listOf(MpsConstraint("C1", intArrayOf(1), doubleArrayOf(1.0), lower = null, upper = 10.0)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(model.toProblem().problem.bake()).solve(BacktrackParams()))
    }
}
