package com.eignex.klause.formats.mps

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.eignex.klause.ir.ObjectiveSense as ObjectiveDirection

private val MpsCompiled.problem: Problem
    get() = model.bake()

class MpsCompilerSolveTest {
    @Test
    fun `a marker integer row requiring a value above one without bounds should be unsat`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X R 1\n" +
                " M1 'MARKER' 'INTEND'\nRHS\n RHS R 2\nENDATA",
        ).toProblem()

        val result = BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams())

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `a mixed integer-continuous model should solve and report the continuous value`() {
        val model = MpsModel(
            name = "m",
            sense = ObjectiveDirection.MINIMIZE,
            objective = MpsObjective("obj", intArrayOf(0), doubleArrayOf(1.0), 0.0),
            variables = listOf(
                MpsVar("x", integer = false, lower = 1.5, upper = 10.0),
                MpsVar("y", integer = true, lower = 0.0, upper = 3.0),
            ),
            constraints = listOf(
                MpsConstraint("C1", intArrayOf(0, 1), doubleArrayOf(1.0, 1.0), lower = null, upper = 5.0),
            ),
        )
        val compiled = model.toProblem()

        val best = assertIs<MinimizeResult.Optimal>(
            BacktrackSolver(compiled.problem.bake()).minimize(
                compiled.objective!!.toLinearObjective(),
                BacktrackParams(),
            ),
        )
        assertEquals(1.5, best.sample.reals[0], 1e-6)
        assertEquals(1.5, best.objective, 1e-6)
    }

    @Test
    fun `a model infeasible only in coefficients finer than a millionth should be unsat`() {
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
    fun `an infeasible continuous model should be unsat`() {
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
    fun `a declared empty integer domain should be unsat`() {
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
