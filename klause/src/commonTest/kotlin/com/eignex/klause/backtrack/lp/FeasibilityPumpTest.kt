package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [lpFeasibilityPump] must return only feasible incumbents — every returned assignment satisfies all
 * Linear factors — since the caller records it without re-checking feasibility. Checked by brute force
 * over random 0/1 covering/packing problems.
 */
class FeasibilityPumpTest {

    private fun satisfies(f: Linear, x: IntArray): Boolean {
        var s = 0L
        for (i in f.vars.indices) s += f.coeffs[i].toLong() * x[f.vars[i]]
        return when (f.op) {
            LinearOp.LE -> s <= f.bound
            LinearOp.GE -> s >= f.bound
            LinearOp.EQ -> s == f.bound.toLong()
            else -> true
        }
    }

    @Test
    fun `the pump returns only feasible incumbents`() {
        val rng = Random(20260623)
        var produced = 0
        repeat(300) {
            val n = rng.nextInt(3, 7)
            val domains = Array(n) { IntDomain(0, 1) }
            val factors = ArrayList<Factor>()
            repeat(rng.nextInt(1, 4)) {
                val k = rng.nextInt(2, n + 1)
                val vars = (0 until n).shuffled(rng).take(k).toIntArray()
                val coeffs = IntArray(k) { 1 }
                // Covering (≥1, satisfiable by ones) or packing (≤k-1, satisfiable by zeros).
                if (rng.nextBoolean()) {
                    factors.add(Linear(coeffs, vars, LinearOp.GE, 1))
                } else {
                    factors.add(Linear(coeffs, vars, LinearOp.LE, k - 1))
                }
            }
            val p = Problem(0, n, domains, factors.toTypedArray())
            val obj = LinearObjective(intCoefficients = LongArray(n) { rng.nextLong(-2, 3) })
            val engine = LpEngine(
                p,
                obj,
                BacktrackParams(lpPlan = LpPlan(bounding = true, probe = true)),
                SolveStatsSink(backend = "pump-test"),
            )
            val sample = engine.lpFeasibilityPump(obj, Cancellation.Never) ?: return@repeat // infeasible/failed: skip
            produced++
            for (f in p.factors.filterIsInstance<Linear>()) {
                assertTrue(satisfies(f, sample.ints), "pump returned an infeasible incumbent ${sample.ints.toList()}")
            }
        }
        assertTrue(produced > 50, "the pump produced only $produced incumbents across 300 instances")
    }
}
