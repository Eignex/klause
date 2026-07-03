package com.eignex.klause.cli

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The presolve↔LP-harvest fixpoint loop in [Solvable.presolved]: reconstruct composition across the
 *  loop, the no-op short-circuit, and that the harvested result is left at a joint fixpoint. */
class PresolveLoopTest {

    private fun solvableOf(
        problem: Problem,
        objective: LinearObjective? = null,
        params: BacktrackParams? = null,
        probe: (Sample) -> Long = { 0L },
    ) = Solvable(
        problem = problem,
        optimize = objective != null,
        maximize = false,
        lsObjective = null,
        linearObjective = objective,
        objVarId = null,
        definitionalSweep = null,
        render = { "" },
        objectiveValue = probe,
        annotatedBacktrackParams = params,
    )

    @Test
    fun `the presolved solution reconstructs an eliminated variable`() {
        // x - 2y = 1 lets affine elimination drop x, rebuilding it as x = 2y + 1 on reconstruct.
        // objectiveValue (wrapped as ov(reconstruct(sample))) reads x back, so it must report 2y + 1.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 10)), // y = var 0, x = var 1
            arrayOf<Factor>(Linear(intArrayOf(-2, 1), intArrayOf(0, 1), LinearOp.EQ, 1)),
        )
        val solvable = solvableOf(problem, probe = { it.ints[1].toLong() })
        val presolved = solvable.presolved(PresolveConfig.DEFAULT, false)
        assertTrue(presolved !== solvable, "affine elimination should have transformed the problem")
        assertTrue(presolved.problem.factors.isEmpty(), "the lone defining equality should be eliminated")
        // y = 2 in the presolved problem (x left free) must reconstruct to x = 5.
        assertEquals(5L, presolved.objectiveValue!!(Sample(BooleanArray(0), intArrayOf(2, 0))))
    }

    @Test
    fun `a presolve that changes nothing returns the same Solvable`() {
        // Presolve disabled and no LP harvest configured: the loop makes no change, so the short-circuit
        // returns the receiver rather than wrapping a fresh Solvable with an identity reconstruct.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf<Factor>(Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 5)),
        )
        val solvable = solvableOf(problem)
        assertSame(solvable, solvable.presolved(PresolveConfig.NONE, false), "an unchanged presolve must return this")
    }

    @Test
    fun `the harvested problem is left at a joint presolve-harvest fixpoint`() {
        // Covering objective: minimise z = x0 + x1 + x2 with the three pairwise covers; the LP-harvest
        // objective shave proves z >= 2. The harvest is gated by the `lp-harvest` presolve pass, enabled
        // here as a delta. After presolving once, re-presolving must be a no-op — the loop leaves no
        // further harvest or reduction on the table.
        val config = PresolveConfig.parse("default,+lp-harvest")
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, -1, -1, -1), intArrayOf(3, 0, 1, 2), LinearOp.GE, 0),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 0L, 1L))
        val once = solvableOf(problem, obj).presolved(config, false)
        assertTrue(once.problem.intDomains[3].min >= 2, "the harvest should have raised the objective floor")
        val again = solvableOf(once.problem, obj).presolved(config, false)
        assertSame(again.problem, once.problem, "the harvested problem must already be at the joint fixpoint")
    }
}
