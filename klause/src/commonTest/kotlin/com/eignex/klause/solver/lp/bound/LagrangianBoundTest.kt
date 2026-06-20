package com.eignex.klause.solver.lp.bound

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** #23: the subgradient Lagrangian bound over an AllDifferent global. */
class LagrangianBoundTest {

    @Test
    fun `bound is a valid lower bound on a weighted all-different`() {
        // min 1·x0 + 2·x1 + 3·x2, AllDifferent over [0,4]. Cheapest distinct assignment by the
        // assignment problem: the largest weight takes value 0, etc. -> exact via Hungarian.
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 4) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 5)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 2, 3))
        val lb = LagrangianBound(p, obj)
        assertTrue(lb.applicable)
        val r = lb.computeBound(PropagationSession(p), Double.POSITIVE_INFINITY, LongArray(lb.multiplierCount), 1)
        requireNotNull(r)
        // True optimum: x2=0,x1=1,x0=2 -> 2 + 2 + 0 = 4. The bound must not exceed it.
        assertTrue(ceil(r.boundNumerator, r.denominator) <= 4L, "bound ${r.boundNumerator}/${r.denominator} > 4")
    }

    @Test
    fun `infeasible all-different is pruned`() {
        // 3 distinct variables but only 2 values -> no assignment.
        val p = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 2)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val lb = LagrangianBound(p, obj)
        val r = lb.computeBound(PropagationSession(p), Double.POSITIVE_INFINITY, LongArray(lb.multiplierCount), 1)
        requireNotNull(r)
        assertTrue(r.prune)
    }

    @Test
    fun `randomized bound never exceeds the true optimum`() {
        val rng = Random(20260608)
        var feasibleChecked = 0
        repeat(400) { _ ->
            val n = 3
            val hi = rng.nextInt(2, 6)
            val doms = Array(n) { IntDomain(0, hi) }
            val factors = ArrayList<Factor>()
            factors.add(AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = hi + 1))
            // 0–2 linear linking constraints over the all-different variables.
            data class Lin(val coeffs: LongArray, val op: LinearOp, val b: Long)
            val links = ArrayList<Lin>()
            repeat(rng.nextInt(0, 3)) { _ ->
                val coeffs = LongArray(n) { rng.nextInt(-2, 3).toLong() }
                if (coeffs.all { it == 0L }) return@repeat
                val op = when (rng.nextInt(3)) {
                    0 -> LinearOp.LE
                    1 -> LinearOp.GE
                    else -> LinearOp.EQ
                }
                val b = rng.nextInt(-3, hi * n + 1).toLong()
                links.add(Lin(coeffs, op, b))
                factors.add(Linear(IntArray(n) { coeffs[it].toInt() }, IntArray(n) { it }, op, b.toInt()))
            }
            val c = LongArray(n) { rng.nextInt(-4, 5).toLong() }
            val p = Problem(0, n, doms, factors.toTypedArray())
            val obj = LinearObjective(intCoefficients = c)
            val lb = LagrangianBound(p, obj)
            if (!lb.applicable) return@repeat

            // Brute-force true optimum over distinct assignments satisfying every linking constraint.
            var trueOpt: Long? = null
            val x = IntArray(n)
            fun rec(i: Int) {
                if (i == n) {
                    if (x.toSet().size != n) return // all-different
                    for (l in links) {
                        var s = 0L
                        for (k in 0 until n) s += l.coeffs[k] * x[k]
                        val ok = when (l.op) {
                            LinearOp.LE -> s <= l.b
                            LinearOp.GE -> s >= l.b
                            LinearOp.EQ -> s == l.b
                            LinearOp.NE -> s != l.b
                        }
                        if (!ok) return
                    }
                    var o = 0L
                    for (k in 0 until n) o += c[k] * x[k]
                    if (trueOpt == null || o < trueOpt!!) trueOpt = o
                    return
                }
                for (v in 0..hi) {
                    x[i] = v
                    rec(i + 1)
                }
            }
            rec(0)

            // Use a large finite incumbent so the subgradient runs but never prunes; the returned
            // bound must still be a valid lower bound on the true optimum.
            val incumbent = (trueOpt ?: 0L).toDouble() + 1000.0
            val r = lb.computeBound(
                PropagationSession(p),
                incumbent,
                LongArray(lb.multiplierCount),
                15,
            ) ?: return@repeat
            val opt = trueOpt
            if (opt != null && !r.prune) {
                feasibleChecked++
                assertTrue(
                    ceil(r.boundNumerator, r.denominator) <= opt,
                    "Lagrangian bound ${ceil(r.boundNumerator, r.denominator)} > true opt $opt",
                )
            }
        }
        assertTrue(feasibleChecked > 100, "only $feasibleChecked feasible instances checked")
    }

    @Test
    fun `two coupled all-different blocks give a valid bound`() {
        // Two disjoint AllDifferents (x0..x2 and x3..x5) over [0,4], coupled by x0 + x3 >= 6.
        // Each block is solved as its own assignment; the coupling is priced via a multiplier.
        val p = Problem(
            0,
            6,
            Array(6) { IntDomain(0, 4) },
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 5),
                AllDifferent(intArrayOf(3, 4, 5), domainMin = 0, domainSize = 5),
                Linear(intArrayOf(1, 1), intArrayOf(0, 3), LinearOp.GE, 6),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1, 1, 1, 1))
        val lb = LagrangianBound(p, obj)
        assertTrue(lb.applicable)
        assertTrue(lb.multiplierCount == 1, "the spanning linear constraint is the one dualized link")
        val r = lb.computeBound(PropagationSession(p), 100.0, LongArray(lb.multiplierCount), 20)
        requireNotNull(r)
        // True optimum: unconstrained each block is 0+1+2 = 3, but x0+x3>=6 forces one anchor up — the
        // cheapest is {2,0,1} and {4,0,1} (2+4=6), total 8. The bound is a lower bound, so ≤ 8.
        assertTrue(!r.prune)
        assertTrue(ceil(r.boundNumerator, r.denominator) <= 8L, "bound ${r.boundNumerator}/${r.denominator} > 8")
    }

    @Test
    fun `randomized two-block bound never exceeds the true optimum`() {
        val rng = Random(20260615)
        var feasibleChecked = 0
        repeat(300) { _ ->
            val hi = rng.nextInt(2, 5)
            val doms = Array(6) { IntDomain(0, hi) }
            val factors = ArrayList<Factor>()
            factors.add(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = hi + 1))
            factors.add(AllDifferent(intArrayOf(3, 4, 5), domainMin = 0, domainSize = hi + 1))
            data class Lin(val coeffs: LongArray, val op: LinearOp, val b: Long)
            val links = ArrayList<Lin>()
            repeat(rng.nextInt(0, 3)) { _ ->
                // Coefficients over all six variables, so a link can couple the two blocks.
                val coeffs = LongArray(6) { rng.nextInt(-2, 3).toLong() }
                if (coeffs.all { it == 0L }) return@repeat
                val op = when (rng.nextInt(3)) {
                    0 -> LinearOp.LE
                    1 -> LinearOp.GE
                    else -> LinearOp.EQ
                }
                val b = rng.nextInt(-3, hi * 6 + 1).toLong()
                links.add(Lin(coeffs, op, b))
                factors.add(Linear(IntArray(6) { coeffs[it].toInt() }, IntArray(6) { it }, op, b.toInt()))
            }
            val c = LongArray(6) { rng.nextInt(-4, 5).toLong() }
            val prob = Problem(0, 6, doms, factors.toTypedArray())
            val obj = LinearObjective(intCoefficients = c)
            val lb = LagrangianBound(prob, obj)
            if (!lb.applicable) return@repeat

            // Brute force: distinct-within-each-block assignments satisfying every link.
            var trueOpt: Long? = null
            val x = IntArray(6)
            fun rec(i: Int) {
                if (i == 6) {
                    if (x[0] == x[1] || x[0] == x[2] || x[1] == x[2]) return
                    if (x[3] == x[4] || x[3] == x[5] || x[4] == x[5]) return
                    for (l in links) {
                        var s = 0L
                        for (k in 0 until 6) s += l.coeffs[k] * x[k]
                        val ok = when (l.op) {
                            LinearOp.LE -> s <= l.b
                            LinearOp.GE -> s >= l.b
                            LinearOp.EQ -> s == l.b
                            LinearOp.NE -> s != l.b
                        }
                        if (!ok) return
                    }
                    var o = 0L
                    for (k in 0 until 6) o += c[k] * x[k]
                    if (trueOpt == null || o < trueOpt!!) trueOpt = o
                    return
                }
                for (v in 0..hi) {
                    x[i] = v
                    rec(i + 1)
                }
            }
            rec(0)

            val incumbent = (trueOpt ?: 0L).toDouble() + 1000.0
            val r = lb.computeBound(PropagationSession(prob), incumbent, LongArray(lb.multiplierCount), 15)
                ?: return@repeat
            val opt = trueOpt
            if (opt != null && !r.prune) {
                feasibleChecked++
                assertTrue(
                    ceil(r.boundNumerator, r.denominator) <= opt,
                    "two-block Lagrangian bound ${ceil(r.boundNumerator, r.denominator)} > true opt $opt",
                )
            }
        }
        assertTrue(feasibleChecked > 80, "only $feasibleChecked feasible instances checked")
    }

    private fun ceil(a: Long, b: Long): Long {
        val qd = a / b
        return if (a % b > 0L) qd + 1 else qd
    }
}
