package com.eignex.klause.formats.mps

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end: an MPS instance with an LP-only continuous column solves through the hybrid engine, and
 *  the continuous variable's value is carried on the solution [com.eignex.klause.solver.Sample.reals]. */
class MpsSolveTest {

    @Test
    fun `an optimum over an open column is certified against the model's own ranges`() {
        // minimize x  s.t.  x >= 3, x integer with no upper bound. The column is genuinely unbounded
        // above — the relaxation maximises it to infinity — so its upper side can only come from the
        // fallback box, and the verdict would carry the clamp caveat. Asking whether anything beats the
        // optimum settles it without the box: `x <= 2` against `x >= 3` has no solution anywhere.
        val m = MpsModel(
            name = "openmin",
            sense = ObjectiveSense.MINIMIZE,
            objective = MpsObjective("obj", intArrayOf(0), doubleArrayOf(1.0), 0.0),
            variables = listOf(MpsVar("x", integer = true, lower = 0.0, upper = null)),
            constraints = listOf(
                MpsConstraint("DEMAND", intArrayOf(0), doubleArrayOf(1.0), lower = 3.0, upper = null),
            ),
        )
        val compiled = m.toProblem()
        val deferred = assertNotNull(compiled.deferredBounds, "an open column defers its bounding")
        assertTrue(deferred.run(Cancellation.Never).clamped, "nothing bounds the column above")
        val objective = assertNotNull(compiled.objective)
        assertTrue(
            deferred.noBetterThan(objective.intCoefficients, objective.constant, compiled.maximize, 3L),
            "nothing anywhere beats 3, so the in-box optimum is the model's",
        )
        assertFalse(
            deferred.noBetterThan(objective.intCoefficients, objective.constant, compiled.maximize, 5L),
            "4 beats 5, so no certificate may be issued there",
        )
    }

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
        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams()))
        assertTrue(sat.assignment.reals.isNotEmpty(), "continuous value missing from the solution")
        val x = sat.assignment.reals[0]
        val y = sat.assignment.ints[0]
        assertTrue(x in 0.0..10.0 && x + y <= 5.0 + 1e-6, "infeasible continuous completion: x=$x y=$y")

        // The optimize path drives the objective (min x) to its true optimum 0, resolved by the leaf LP.
        val opt = BacktrackSolver(compiled.problem.bake()).minimize(compiled.objective!!, BacktrackParams())
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
        assertIs<SolveResult.Unsat>(BacktrackSolver(m.toProblem().problem.bake()).solve(BacktrackParams()))
    }
}
