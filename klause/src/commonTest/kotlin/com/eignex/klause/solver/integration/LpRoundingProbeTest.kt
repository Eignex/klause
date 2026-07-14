package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #287: the LP-rounding primal heuristic (`lpRoundingProbe`) that seeds an incumbent before search. */
class LpRoundingProbeTest {

    /** Triangle covering: minimize x0+x1+x2 with each pair summing to >= 2 over [0,5]; optimum 3 at (1,1,1). */
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
    fun `lp rounding probe seeds an incumbent and preserves the optimum`() {
        // The root LP of the triangle is integral at (1,1,1), so the probe rounds it into a
        // feasible incumbent (objective 3) before search. The proven optimum must be unchanged.
        val problem = triangle()
        val off = BacktrackSolver(
            problem,
        ).minimize(sumObjective, BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)))
        val on = BacktrackSolver(problem).minimize(
            sumObjective,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, probe = true)),
        )
        assertTrue(off is MinimizeResult.Optimal && on is MinimizeResult.Optimal)
        assertEquals(3.0, off.objectiveValue)
        assertEquals(3.0, on.objectiveValue)
    }

    @Test
    fun `lp rounding probe falls back when the rounded value conflicts`() {
        // maximize x0 (minimize -x0) over [0,2] with 5·x0 ≤ 8. The LP optimum is x0 = 1.6, which rounds
        // to 2 — infeasible (5·2 = 10 > 8). The probe must fall back to the other side (x0 = 1) rather
        // than seed an infeasible incumbent; the proven optimum is -1.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 2)),
            factors = arrayOf<Factor>(Linear(intArrayOf(5), intArrayOf(0), LinearOp.LE, 8)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(-1L))
        val result = BacktrackSolver(problem).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, probe = true)),
        )
        assertTrue(result is MinimizeResult.Optimal, "should prove optimality")
        assertEquals(-1.0, result.objectiveValue)
    }

    @Test
    fun `lp rounding probe preserves the optimum on a fractional relaxation`() {
        // Odd-cycle packing: maximize x0+x1+x2 (minimize the negation) with each pair summing to ≤ 1
        // over [0,1]. The LP relaxation is fractional at (0.5,0.5,0.5); whichever way the probe rounds,
        // the seeded incumbent must stay feasible and the proven optimum (-1, one variable set) holds.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.LE, 1),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(-1L, -1L, -1L))
        val result = BacktrackSolver(problem).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, probe = true)),
        )
        assertTrue(result is MinimizeResult.Optimal, "should prove optimality")
        assertEquals(-1.0, result.objectiveValue)
    }
}
