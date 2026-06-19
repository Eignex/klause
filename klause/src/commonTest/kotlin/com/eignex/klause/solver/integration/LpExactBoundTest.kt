package com.eignex.klause.solver.integration

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.lp.LpPlan
import com.eignex.klause.solver.factor.linear.Linear
import com.eignex.klause.solver.factor.linear.LinearOp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end: the float + exact-certify LP bound (#567) must never corrupt the optimum, including
 *  with large coefficients that stress the exact `BigInt` basis certification. */
class LpExactBoundTest {

    @Test
    fun `minimize with the LP bound preserves the optimum on large coefficients`() {
        val rng = Random(20260618)
        var optimal = 0
        repeat(120) { _ ->
            val n = rng.nextInt(3, 6)
            val ub = IntArray(n) { rng.nextInt(2, 6) }
            val cost = LongArray(n) { rng.nextLong(-9, 10) }
            val vars = IntArray(n) { it }
            // A few `≤` constraints with large coefficients (to stress the exact determinant).
            val cons = ArrayList<Triple<LongArray, IntArray, Long>>()
            repeat(rng.nextInt(1, 4)) { _ ->
                val coeffs = LongArray(n) { rng.nextLong(-40_000, 40_001) }
                cons.add(Triple(coeffs, vars, rng.nextLong(0, 60_000)))
            }

            val brute = bruteMin(n, ub, cost, cons)
            val domains = Array(n) { IntDomain(0, ub[it]) }
            val factors = cons.map { (c, v, r) -> Linear(c.map { it.toInt() }.toIntArray(), v, LinearOp.LE, r.toInt()) }
            val problem = Problem(0, n, domains, factors.toTypedArray<Factor>())
            val obj = LinearObjective(intCoefficients = cost)
            val params = BacktrackParams(randomSeed = 5L, lpPlan = LpPlan(bounding = true))

            when (val res = BacktrackSolver(problem).minimize(obj, params)) {
                is MinimizeResult.Optimal -> {
                    val opt = brute ?: error("solver Optimal but brute infeasible")
                    assertEquals(opt.toDouble(), res.objective, 1e-9, "wrong optimum")
                    optimal++
                }

                is MinimizeResult.Infeasible -> assertTrue(brute == null, "solver Infeasible but brute feasible")

                else -> error("unexpected $res")
            }
        }
        assertTrue(optimal > 40, "covered only $optimal optimal instances")
    }

    private fun bruteMin(n: Int, ub: IntArray, cost: LongArray, cons: List<Triple<LongArray, IntArray, Long>>): Long? {
        val x = IntArray(n)
        var best: Long? = null
        fun feasible(): Boolean = cons.all { (c, _, r) ->
            var s = 0L
            for (j in 0 until n) s += c[j] * x[j]
            s <= r
        }
        fun rec(i: Int) {
            if (i == n) {
                if (feasible()) {
                    var s = 0L
                    for (j in 0 until n) s += cost[j] * x[j]
                    if (best == null || s < best!!) best = s
                }
                return
            }
            for (v in 0..ub[i]) {
                x[i] = v
                rec(i + 1)
            }
        }
        rec(0)
        return best
    }
}
