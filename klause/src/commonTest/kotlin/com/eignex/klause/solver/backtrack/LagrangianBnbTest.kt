package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.factor.linear.Linear
import com.eignex.klause.solver.factor.linear.LinearOp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #23: Lagrangian bounding wired into branch-and-bound. */
class LagrangianBnbTest {

    @Test
    fun `weighted all-different prunes with the assignment bound and keeps the optimum`() {
        // min Σ (i+1)·x_i over AllDifferent(4) in [0,5]. Cheapest: largest weight takes 0, etc. ->
        // 4·0 + 3·1 + 2·2 + 1·3 = 10. The assignment bound is exact for this, so it prunes hard.
        val p = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 5) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 6)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 2, 3, 4))
        val off = BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = 1L))
        val on = BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = 1L, lagrangian = true))

        assertTrue(off is MinimizeResult.Optimal && on is MinimizeResult.Optimal)
        assertEquals(10.0, off.objectiveValue)
        assertEquals(10.0, on.objectiveValue)
        assertTrue(
            on.stats.lagrangianPruned.sum > 0.0,
            "expected Lagrangian prunes, got ${on.stats.lagrangianPruned.sum}",
        )
        assertTrue(
            on.stats.nodes.sum <= off.stats.nodes.sum,
            "Lagrangian explored more nodes: ${on.stats.nodes.sum} vs ${off.stats.nodes.sum}",
        )
    }

    @Test
    fun `linking constraint is dualized soundly`() {
        // min Σ x_i over AllDifferent(3) in [0,4] with the linking constraint x0 + x1 >= 5.
        // Distinct values with x0+x1>=5 and x2 smallest -> {2,3} or {1,4} plus 0 = total 5.
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 4) },
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 5),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 5),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val off = BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = 3L))
        val on = BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = 3L, lagrangian = true))

        assertTrue(off is MinimizeResult.Optimal && on is MinimizeResult.Optimal)
        assertEquals(off.objectiveValue, on.objectiveValue, "Lagrangian changed the optimum")
        assertEquals(5.0, on.objectiveValue)
    }

    @Test
    fun `no-op when there is no all-different`() {
        val p = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 5) },
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val result = BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = 1L, lagrangian = true))
        assertTrue(result is MinimizeResult.Optimal)
        assertEquals(3.0, result.objectiveValue)
        assertEquals(0.0, result.stats.lagrangianPruned.sum)
    }
}
