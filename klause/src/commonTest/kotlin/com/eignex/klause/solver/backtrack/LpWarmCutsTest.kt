package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #21: warm-starting the cut-round dual re-solve from the previous round's extended basis must not
 * change the proven optimum — it only changes the pivot path. Each instance is solved with
 * [BacktrackParams.lpWarmCuts] both on and off; the results must agree.
 */
class LpWarmCutsTest {

    private fun solve(problem: Problem, obj: LinearObjective, warm: Boolean): MinimizeResult =
        BacktrackSolver(problem).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpBounding = true, lpCuts = true, lpWarmCuts = warm),
        )

    private fun assertSameOptimum(problem: Problem, obj: LinearObjective, label: String) {
        val cold = solve(problem, obj, warm = false)
        val warm = solve(problem, obj, warm = true)
        assertTrue(cold is MinimizeResult.Optimal, "$label: cold should prove optimality")
        assertTrue(warm is MinimizeResult.Optimal, "$label: warm should prove optimality")
        assertEquals(cold.objectiveValue, warm.objectiveValue, "$label: warm and cold optima differ")
    }

    @Test
    fun `warm cut re-solve preserves the optimum on a covering problem`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 2),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 2),
            ),
        )
        assertSameOptimum(problem, LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L)), "triangle")
    }

    @Test
    fun `warm and cold agree across a randomized battery`() {
        val rng = Random(20260610)
        var solved = 0
        repeat(120) {
            val n = rng.nextInt(2, 4)
            val hi = rng.nextInt(2, 5)
            val factors = ArrayList<Factor>()
            // A few covering / packing rows so cut rounds actually fire on a fractional LP.
            repeat(rng.nextInt(2, 5)) { _ ->
                val k = rng.nextInt(2, n + 1)
                val vars = (0 until n).shuffled(rng).take(k).toIntArray()
                val coeffs = IntArray(vars.size) { rng.nextInt(1, 4) }
                val rel = if (rng.nextBoolean()) LinearOp.GE else LinearOp.LE
                val rhs = rng.nextInt(1, hi * k + 1)
                factors.add(Linear(coeffs, vars, rel, rhs))
            }
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, hi) },
                factors = factors.toTypedArray(),
            )
            val obj = LinearObjective(intCoefficients = LongArray(n) { rng.nextInt(-2, 3).toLong() })
            val cold = solve(problem, obj, warm = false)
            val warm = solve(problem, obj, warm = true)
            // Whatever the cold solve concludes (optimal / infeasible / unbounded), warm must match.
            assertEquals(cold::class, warm::class, "status diverged on instance with factors $factors")
            if (cold is MinimizeResult.Optimal && warm is MinimizeResult.Optimal) {
                assertEquals(cold.objectiveValue, warm.objectiveValue, "optimum diverged on $factors")
                solved++
            }
        }
        assertTrue(solved > 40, "covered only $solved optimal instances")
    }
}
