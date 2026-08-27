package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Regression guard for #47: the full CDCL config (VSIDS + Luby restarts + LBD) must prove
 * optimality on branch-and-bound problems, not just find the optimum and then burn the budget
 * re-traversing the bound-pruned tree across restarts. [BacktrackSolver.improvements] suppresses
 * Luby restarts for the optimization path, so a config carrying `lubyRestartBase` still
 * terminates with [MinimizeResult.Optimal] at the true optimum. Were restarts left active and
 * the proof not to fit the decision budget, the terminal verdict would be `BestFound`/`Unknown`.
 */
class CdclOptimizationTest {

    @Test
    fun `full CDCL config proves the knapsack optimum`() {
        // 7 binary items; maximise value s.t. total weight ≤ cap, as minimise Σ(−value)·x.
        val weights = intArrayOf(3, 4, 5, 2, 6, 1, 4)
        val values = intArrayOf(5, 6, 8, 3, 9, 2, 5)
        val cap = 12
        val n = weights.size

        // Brute-force optimum (max value within cap) → objective is its negation.
        var bestValue = 0
        for (mask in 0 until (1 shl n)) {
            var w = 0
            var v = 0
            for (i in 0 until n) {
                if ((mask shr i) and 1 == 1) {
                    w += weights[i]
                    v += values[i]
                }
            }
            if (w <= cap && v > bestValue) bestValue = v
        }

        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(
                Linear(coeffs = weights, vars = IntArray(n) { it }, op = LinearOp.LE, bound = cap),
            ),
        )
        val obj = LinearObjective(intCoefficients = LongArray(n) { -values[it].toLong() })
        val params = BacktrackParams(
            randomSeed = 1L,
            variableSelector = Vsids(),
            lubyRestartBase = 4L,
            maxLearnedClauses = 1_000,
            maxDecisions = 200_000L,
        )
        val terminal = BacktrackSolver(problem.bake()).minimize(obj, params)
        val optimal = assertIs<MinimizeResult.Optimal>(terminal)
        assertEquals(-bestValue.toDouble(), optimal.objective, "must prove the true knapsack optimum")
    }
}
