package com.eignex.klause.solver.lp

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #632: the 0/1 multi-knapsack subgradient Lagrangian bound (one knapsack solved exactly by DP,
 *  the rest dualized). The bound must never exceed the true optimum, for any multipliers. */
class KnapsackLagrangianBoundTest {

    private fun ceil(a: Long, b: Long): Long = if (a % b > 0L) a / b + 1 else a / b

    private fun pb(weights: IntArray, vars: IntArray, op: PbOp, bound: Int): PseudoBoolean =
        PseudoBoolean(weights, IntArray(vars.size) { Lit.make(vars[it], true) }, op, bound)

    @Test
    fun `exact knapsack bound on a forced cover-style instance`() {
        // 4 bools, minimize -(3 x0 + 2 x1 + 2 x2 + x3) (i.e. maximize a profit) under capacity
        // 4 x0 + 3 x1 + 3 x2 + 2 x3 <= 6, plus a second capacity 2 x0 + 2 x1 + x2 + x3 <= 3.
        val p = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = arrayOf(),
            factors = arrayOf<Factor>(
                pb(intArrayOf(4, 3, 3, 2), intArrayOf(0, 1, 2, 3), PbOp.LE, 6),
                pb(intArrayOf(2, 2, 1, 1), intArrayOf(0, 1, 2, 3), PbOp.LE, 3),
            ),
        )
        val obj = LinearObjective(boolWeights = longArrayOf(-3, -2, -2, -1))
        val lb = KnapsackLagrangianBound(p, obj)
        assertTrue(lb.applicable)
        val r = lb.computeBound(PropagationSession(p), 100.0, LongArray(lb.multiplierCount), 30)
        requireNotNull(r)
        // Brute-force optimum over both capacities: best feasible profit is x0,x3 -> 4+2=6 cap, 2+1=3
        // cap, profit 3+1=4 -> objective -4. The bound is a lower bound, so <= -4.
        assertTrue(!r.prune)
        assertTrue(ceil(r.boundNumerator, r.denominator) <= -4L, "bound ${r.boundNumerator}/${r.denominator} > -4")
    }

    @Test
    fun `randomized multi-knapsack bound never exceeds the true optimum`() {
        val rng = Random(20260615)
        var feasibleChecked = 0
        repeat(400) { _ ->
            val n = rng.nextInt(4, 8)
            val numKnap = rng.nextInt(1, 4)
            val factors = ArrayList<Factor>()
            data class Knap(val w: IntArray, val cap: Int)
            val knaps = ArrayList<Knap>()
            repeat(numKnap) {
                val w = IntArray(n) { rng.nextInt(1, 5) }
                val cap = rng.nextInt(n, n * 4)
                knaps.add(Knap(w, cap))
                factors.add(pb(w, IntArray(n) { it }, PbOp.LE, cap))
            }
            val c = LongArray(n) { rng.nextInt(-4, 3).toLong() } // some negative ⇒ selection is nontrivial
            val prob = Problem(
                numBoolVars = n,
                numIntVars = 0,
                intDomains = arrayOf(),
                factors = factors.toTypedArray(),
            )
            val obj = LinearObjective(boolWeights = c)
            val lb = KnapsackLagrangianBound(prob, obj)
            if (!lb.applicable) return@repeat

            // Brute force: every assignment satisfying all capacity rows, minimum objective.
            var trueOpt: Long? = null
            for (mask in 0 until (1 shl n)) {
                var ok = true
                for (k in knaps) {
                    var s = 0
                    for (b in 0 until n) if ((mask shr b) and 1 == 1) s += k.w[b]
                    if (s > k.cap) {
                        ok = false
                        break
                    }
                }
                if (!ok) continue
                var o = 0L
                for (b in 0 until n) if ((mask shr b) and 1 == 1) o += c[b]
                if (trueOpt == null || o < trueOpt!!) trueOpt = o
            }
            val opt = trueOpt ?: return@repeat // x=0 always feasible, so this never triggers
            val incumbent = opt.toDouble() + 1000.0
            val r = lb.computeBound(
                PropagationSession(prob),
                incumbent,
                LongArray(lb.multiplierCount),
                20,
            ) ?: return@repeat
            if (!r.prune) {
                feasibleChecked++
                assertTrue(
                    ceil(r.boundNumerator, r.denominator) <= opt,
                    "knapsack Lagrangian bound ${ceil(r.boundNumerator, r.denominator)} > true opt $opt",
                )
            }
        }
        assertTrue(feasibleChecked > 200, "only $feasibleChecked instances checked")
    }

    @Test
    fun `knapsack lagrangian keeps the optimum correct end to end`() {
        val p = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = arrayOf(),
            factors = arrayOf<Factor>(
                pb(intArrayOf(4, 3, 3, 2), intArrayOf(0, 1, 2, 3), PbOp.LE, 6),
                pb(intArrayOf(2, 2, 1, 1), intArrayOf(0, 1, 2, 3), PbOp.LE, 3),
            ),
        )
        val obj = LinearObjective(boolWeights = longArrayOf(-3, -2, -2, -1))
        val base = BacktrackParams(randomSeed = 1L)
        val noBound = BacktrackSolver(p).minimize(obj, base)
        val knap = BacktrackSolver(p).minimize(obj, base.copy(lpKnapsackLagrangian = true))
        assertTrue(noBound is MinimizeResult.Optimal, "baseline should solve, got $noBound")
        assertTrue(knap is MinimizeResult.Optimal, "knapsack-Lagrangian solve should be optimal, got $knap")
        assertEquals(-4.0, knap.objective, 1e-9, "best profit selection is -4")
        assertEquals(noBound.objective, knap.objective, 1e-9, "the Lagrangian bound must not change the optimum")
    }
}
