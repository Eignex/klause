package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
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
        val off = BacktrackSolver(problem).minimize(sumObjective, BacktrackParams(randomSeed = 1L))
        val on = BacktrackSolver(problem).minimize(
            sumObjective,
            BacktrackParams(randomSeed = 1L, lpBounding = true),
        )

        assertTrue(off is MinimizeResult.Optimal, "baseline should prove optimality")
        assertTrue(on is MinimizeResult.Optimal, "lp-bounded should prove optimality")
        assertEquals(3.0, off.objectiveValue)
        assertEquals(3.0, on.objectiveValue)
    }

    @Test
    fun `lp bounding prunes nodes the separable bound cannot`() {
        val problem = triangle()
        val off = BacktrackSolver(problem).minimize(sumObjective, BacktrackParams(randomSeed = 1L))
        val on = BacktrackSolver(problem).minimize(
            sumObjective,
            BacktrackParams(randomSeed = 1L, lpBounding = true),
        )

        // The LP bound fires (telemetry records it) and never explores more nodes than the baseline.
        assertTrue(on.stats.lpPruned.sum > 0.0, "expected LP-bound prunes, got ${on.stats.lpPruned.sum}")
        assertTrue(
            on.stats.nodes.sum <= off.stats.nodes.sum,
            "LP bounding explored more nodes: ${on.stats.nodes.sum} vs ${off.stats.nodes.sum}",
        )
    }

    @Test
    fun `lp rounding probe seeds an incumbent and preserves the optimum`() {
        // #287: the root LP of the triangle is integral at (1,1,1), so the probe rounds it into a
        // feasible incumbent (objective 3) before search. The proven optimum must be unchanged.
        val problem = triangle()
        val off = BacktrackSolver(problem).minimize(sumObjective, BacktrackParams(randomSeed = 1L, lpBounding = true))
        val on = BacktrackSolver(problem).minimize(
            sumObjective,
            BacktrackParams(randomSeed = 1L, lpBounding = true, lpProbe = true),
        )
        assertTrue(off is MinimizeResult.Optimal && on is MinimizeResult.Optimal)
        assertEquals(3.0, off.objectiveValue)
        assertEquals(3.0, on.objectiveValue)
    }

    @Test
    fun `root cut loop preserves the optimum and never explores more nodes`() {
        // #285: closing the root relaxation harder (more separation rounds at level 0) must keep the
        // proven optimum and never enlarge the tree versus a single root round.
        val problem = triangle()
        val deep = BacktrackSolver(problem).minimize(
            sumObjective,
            BacktrackParams(randomSeed = 1L, lpBounding = true, lpCuts = true, lpRootCutRounds = 16),
        )
        val shallow = BacktrackSolver(problem).minimize(
            sumObjective,
            BacktrackParams(randomSeed = 1L, lpBounding = true, lpCuts = true, lpRootCutRounds = 1, lpCutRounds = 1),
        )
        assertTrue(deep is MinimizeResult.Optimal && shallow is MinimizeResult.Optimal)
        assertEquals(3.0, deep.objectiveValue)
        assertEquals(3.0, shallow.objectiveValue)
        assertTrue(
            deep.stats.nodes.sum <= shallow.stats.nodes.sum,
            "root cut loop explored more nodes: ${deep.stats.nodes.sum} vs ${shallow.stats.nodes.sum}",
        )
    }

    @Test
    fun `frequency policy still preserves the optimum`() {
        // Solving the LP only every 3rd checked node must not change the proven optimum.
        val problem = triangle()
        val result = BacktrackSolver(problem).minimize(
            sumObjective,
            BacktrackParams(randomSeed = 7L, lpBounding = true, lpBoundEvery = 3),
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
        val result = BacktrackSolver(problem).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpBounding = true),
        )
        assertTrue(result is MinimizeResult.Optimal)
        assertEquals(0.0, result.objectiveValue)
    }
}
