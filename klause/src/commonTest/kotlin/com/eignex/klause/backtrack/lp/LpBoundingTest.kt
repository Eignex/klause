package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.RandomVariable
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.roundUpToResidue
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #20: LP-relaxation bounding wired into BacktrackSolver branch-and-bound. */
class LpBoundingTest {

    /**
     * Triangle covering: minimize x0+x1+x2 with x0+x1≥2, x1+x2≥2, x0+x2≥2 over [0,5]. Summing the
     * rows gives 2·Σx ≥ 6, so the optimum is 3 at (1,1,1). The separable per-term bound sees only
     * each variable's propagated minimum (0 here), so it is useless — the LP bound (exactly 3) is
     * what isolates LP's contribution to pruning.
     */
    private fun triangle(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 2),
        ),
    )

    private val sumObjective = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))

    @Test
    fun `lp bounding preserves the optimum`() {
        val problem = triangle()
        val off = BacktrackSolver(problem.bake()).minimize(sumObjective, BacktrackParams(randomSeed = 1L))
        val on = BacktrackSolver(problem.bake()).minimize(
            sumObjective,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)),
        )

        assertTrue(off is MinimizeResult.Optimal, "baseline should prove optimality")
        assertTrue(on is MinimizeResult.Optimal, "lp-bounded should prove optimality")
        assertEquals(3.0, off.objectiveValue)
        assertEquals(3.0, on.objectiveValue)
    }

    @Test
    fun `lp bounding prunes nodes the separable bound cannot`() {
        val problem = triangle()
        // Keep this regression independent of evolving global defaults.
        val base = BacktrackParams(randomSeed = 1L, variableSelector = RandomVariable)
        val off = BacktrackSolver(problem.bake()).minimize(sumObjective, base)
        val on = BacktrackSolver(problem.bake()).minimize(
            sumObjective,
            base.copy(lpPlan = base.lpPlan.copy(bounding = true)),
        )

        // The LP bound fires (telemetry records it) and never explores more nodes than the baseline.
        assertTrue(on.stats.lp.pruned.sum > 0.0, "expected LP-bound prunes, got ${on.stats.lp.pruned.sum}")
        assertTrue(
            on.stats.search.nodes.sum <= off.stats.search.nodes.sum,
            "LP bounding explored more nodes: ${on.stats.search.nodes.sum} vs ${off.stats.search.nodes.sum}",
        )
    }

    @Test
    fun `root cut harvest preserves the optimum`() {
        // The root cut harvest (global pool reused at every node) must keep the proven optimum.
        val problem = triangle()
        val result = BacktrackSolver(problem.bake()).minimize(
            sumObjective,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, cuts = true)),
        )
        assertTrue(result is MinimizeResult.Optimal)
        assertEquals(3.0, result.objectiveValue)
    }

    @Test
    fun `frequency policy still preserves the optimum`() {
        // Solving the LP only every 3rd checked node must not change the proven optimum.
        val problem = triangle()
        val result = BacktrackSolver(problem.bake()).minimize(
            sumObjective,
            BacktrackParams(randomSeed = 7L, lpPlan = LpPlan(bounding = true, boundEvery = 3)),
        )
        assertTrue(result is MinimizeResult.Optimal)
        assertEquals(3.0, result.objectiveValue)
    }

    @Test
    fun `lp bounding leaves an unconstrained objective optimum intact`() {
        // No constraints, just an objective column: the LP bound is trivial and prunes nothing
        // unsound — the optimum is still the objective's floor.
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 4)), arrayOf<Factor>())
        val obj = LinearObjective(intCoefficients = longArrayOf(1L))
        val result = BacktrackSolver(problem.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)),
        )
        assertTrue(result is MinimizeResult.Optimal)
        assertEquals(0.0, result.objectiveValue)
    }

    @Test
    fun `objective-variable divisor rounding preserves the optimum`() {
        // v = 2(a+b+c) so v is always even; the triangle forces a+b+c >= 2, so the optimum is v = 4.
        // The continuous LP relaxes a+b+c to 1.5 (v = 3.0); rounding 3 up to the next even value gives
        // the exact bound 4 at the root. The proven optimum must equal the baseline's.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 12), IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, -2, -2, -2), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 1),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 0L, 0L, 0L))
        val off = BacktrackSolver(problem.bake()).minimize(obj, BacktrackParams(randomSeed = 1L))
        val on = BacktrackSolver(problem.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)),
        )
        assertTrue(off is MinimizeResult.Optimal, "baseline should prove optimality")
        assertTrue(on is MinimizeResult.Optimal, "lp-bounded should prove optimality")
        assertEquals(4.0, off.objectiveValue)
        assertEquals(4.0, on.objectiveValue, "divisor rounding must not change the optimum")
    }

    @Test
    fun `roundUpToResidue lifts to the next congruent value`() {
        assertEquals(4L, roundUpToResidue(3L, 2L, 0L)) // 3 -> next even
        assertEquals(4L, roundUpToResidue(4L, 2L, 0L)) // already even, unchanged
        assertEquals(5L, roundUpToResidue(3L, 3L, 2L)) // next value congruent to 2 mod 3
        assertEquals(3L, roundUpToResidue(3L, 3L, 0L)) // 3 is 0 mod 3, unchanged
        assertEquals(-2L, roundUpToResidue(-3L, 2L, 0L)) // negative lower bound -> next even
    }
}
