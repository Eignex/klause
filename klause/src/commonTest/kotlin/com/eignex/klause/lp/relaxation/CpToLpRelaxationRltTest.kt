package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.lp.RevisedSimplex
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Boolean RLT is a relaxation *strengthening*: every integer-feasible point sets its product
 * variables `wₖᵢ = xₖ·xᵢ` to satisfy every McCormick + RLT row, so the augmented relaxation excludes no
 * feasible point and its LP optimum stays a valid lower bound on the integer optimum. Checked by brute
 * force: over random 0/1 knapsack problems the RLT LP bound never exceeds the true integer optimum, and
 * it is at least as tight as the plain relaxation.
 */
class CpToLpRelaxationRltTest {

    private fun lpOptimum(p: Problem, obj: LinearObjective, rlt: Boolean): Double {
        val relaxation = CpToLpRelaxation(p, obj, booleanRlt = rlt).build(PropagationSession(p))
        return RevisedSimplex(relaxation.model).solve()?.objective ?: Double.NaN
    }

    @Test
    fun `the RLT relaxation bound never exceeds the integer optimum and is no weaker than plain`() {
        val rng = Random(20260626)
        var compared = 0
        repeat(400) { _ ->
            val n = rng.nextInt(3, 6)
            val domains = Array(n) { IntDomain(0, 1) }
            val factors = ArrayList<Factor>()
            // One or two 0/1 knapsack rows Σ aₖ xₖ ≤ b (the RLT source) + a covering row for feasibility.
            repeat(rng.nextInt(1, 3)) { _ ->
                val coeffs = IntArray(n) { rng.nextInt(1, 4) }
                val b = rng.nextInt(coeffs.min(), coeffs.sum()) // binds but is satisfiable by zeros
                factors.add(Linear(coeffs, IntArray(n) { it }, LinearOp.LE, b))
            }
            val cov = (0 until n).shuffled(rng).take(rng.nextInt(2, n + 1)).toIntArray()
            factors.add(Linear(IntArray(cov.size) { 1 }, cov, LinearOp.GE, 1)) // ≥1 one, so zeros infeasible
            val p = Problem(0, n, domains, factors.toTypedArray())
            // Maximize a random profit (minimize its negation) so the knapsack rows bind.
            val profit = LongArray(n) { rng.nextLong(1, 5) }
            val obj = LinearObjective(intCoefficients = LongArray(n) { -profit[it] })

            val rltOpt = lpOptimum(p, obj, rlt = true)
            if (rltOpt.isNaN()) return@repeat
            val plainOpt = lpOptimum(p, obj, rlt = false)

            // Brute-force the true integer optimum over feasible 0/1 points.
            var best = Double.POSITIVE_INFINITY
            val x = IntArray(n)
            fun feasible(): Boolean = factors.filterIsInstance<Linear>().all { f ->
                var s = 0L
                for (i in f.vars.indices) s += f.coeffs[i].toLong() * x[f.vars[i]]
                if (f.op == LinearOp.LE) s <= f.bound else s >= f.bound
            }
            for (mask in 0 until (1 shl n)) {
                for (i in 0 until n) x[i] = (mask shr i) and 1
                if (!feasible()) continue
                val obj0 = (0 until n).sumOf { -profit[it] * x[it] }.toDouble()
                if (obj0 < best) best = obj0
            }
            if (best == Double.POSITIVE_INFINITY) return@repeat // infeasible instance
            compared++
            // Sound: the RLT LP optimum is a valid lower bound — it never exceeds the integer optimum.
            assertTrue(rltOpt <= best + 1e-6, "UNSOUND: RLT LP bound $rltOpt > integer optimum $best")
            // RLT only tightens: its bound is ≥ the plain relaxation's (within float tolerance).
            if (!plainOpt.isNaN()) {
                assertTrue(rltOpt >= plainOpt - 1e-6, "RLT bound $rltOpt weaker than plain $plainOpt")
            }
        }
        assertTrue(compared > 100, "covered only $compared instances")
    }
}
