package com.eignex.klause.backtrack.lp

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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The best-bound tree-search subsolver ([lbTreeSearch]) is a primal heuristic: it must return only
 * fully-pinned, propagation-feasible incumbents (the caller records them without re-checking), and on a
 * small problem its best-first dive should reach an optimal one. Checked by brute force.
 */
class LpBoundingLbTreeSearchTest {

    private fun satisfies(f: Linear, x: LongArray): Boolean {
        var s = 0L
        for (i in f.vars.indices) s += f.coeffs[i] * x[f.vars[i]]
        return when (f.op) {
            LinearOp.LE -> s <= f.bound
            LinearOp.GE -> s >= f.bound
            LinearOp.EQ -> s == f.bound
            else -> true
        }
    }

    private fun engine(p: Problem, obj: LinearObjective) =
        LpEngine(p, obj, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink(backend = "lbtree"))

    @Test
    fun `the subsolver returns only feasible incumbents`() {
        val rng = Random(20260625)
        var produced = 0
        repeat(300) { _ ->
            val n = rng.nextInt(3, 7)
            val domains = Array(n) { IntDomain(0, 1) }
            val factors = ArrayList<Factor>()
            repeat(rng.nextInt(1, 4)) { _ ->
                val k = rng.nextInt(2, n + 1)
                val vars = (0 until n).shuffled(rng).take(k).toIntArray()
                val coeffs = IntArray(k) { 1 }
                if (rng.nextBoolean()) {
                    factors.add(Linear(coeffs, vars, LinearOp.GE, 1)) // covering (ones feasible)
                } else {
                    factors.add(Linear(coeffs, vars, LinearOp.LE, k - 1)) // packing (zeros feasible)
                }
            }
            val p = Problem(0, n, domains, factors.toTypedArray())
            val obj = LinearObjective(intCoefficients = LongArray(n) { rng.nextLong(-2, 3) })
            val sample = engine(p, obj).lbTreeSearch(obj, Cancellation.Never) ?: return@repeat
            produced++
            for (f in p.factors.filterIsInstance<Linear>()) {
                assertTrue(satisfies(f, sample.ints), "subsolver returned infeasible ${sample.ints.toList()}")
            }
        }
        assertTrue(produced > 50, "the subsolver produced only $produced incumbents across 300 instances")
    }

    @Test
    fun `the subsolver dives to an optimal incumbent on a small problem`() {
        // Triangle vertex cover: cost = x0+x1+x2 over {0,1}³, pair-covering rows ⇒ optimum cost 2.
        val p = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1, 1, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val sample = engine(p, obj).lbTreeSearch(obj, Cancellation.Never)
        assertTrue(sample != null, "best-bound search should find a feasible incumbent")
        assertEquals(2.0, obj.evaluate(sample), "best-bound search should dive to the optimal cost 2")
    }
}
