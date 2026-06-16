package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #655 (Tranche C): the count-variable [GlobalCardinality] one-hot hull. Each variable picks one
 * value (a simplex `Σ_v z_iv = 1`) and the count rows read `Σ_i z_{i,cover(k)} = counts(k)`; the
 * assignment polytope is a product of simplices, hence integral, so the LP optimum of a linear
 * objective over the `xs` and count variables equals the true integer optimum — checked against
 * brute force — and a count in the objective gets an exact bound the bare propagator domain misses.
 */
class GccCountHullTest {

    private val eps = 1e-9

    @Test
    fun `hull captures the joint count sum the per-count propagator misses`() {
        // x0,x1 ∈ {1,2}; cover {1,2}; count1=var2, count2=var3 ∈ [0,2]. Each count's *possible* upper
        // bound is 2 (both vars can take either value), so the propagator allows count1=count2=2
        // independently — but every var takes exactly one value, so count1+count2 = 2. Maximizing
        // count1+count2 (minimizing −count1−count2) exposes the gap: bare reads −4, the hull's
        // `Σ_v counts = n` linkage reads the true −2.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                GlobalCardinality(xs = intArrayOf(0, 1), cover = intArrayOf(1, 2), countVars = intArrayOf(2, 3)),
            ),
        )
        val maximizeTotalCount = LinearObjective(intCoefficients = longArrayOf(0, 0, -1, -1))
        val session = PropagationSession(p)
        val bare = DualSimplex(
            CpToLpRelaxation(p, maximizeTotalCount, gccCountHull = false).build(session).model,
        ).solve()
        val hull = DualSimplex(
            CpToLpRelaxation(p, maximizeTotalCount, gccCountHull = true).build(session).model,
        ).solve()
        assertEquals(LpStatus.OPTIMAL, hull.status)
        assertEquals(-2.0, hull.objectiveValue, eps, "the two vars contribute exactly 2 to the cover counts")
        assertTrue(hull.objectiveValue > bare.objectiveValue + eps, "the hull beats the per-count domain bound")
    }

    @Test
    fun `randomized count-var GCC hull matches the brute-force optimum`() {
        val rng = Random(20260616)
        var checked = 0
        repeat(300) { _ ->
            val n = rng.nextInt(2, 5)
            val maxVal = rng.nextInt(2, 5)
            // Each xs gets a contiguous sub-range of 1..maxVal (a hole would still be sound, but a
            // range keeps the brute-force enumeration simple and already exercises the full model).
            val doms = Array(n) {
                val lo = rng.nextInt(1, maxVal + 1)
                val hi = rng.nextInt(lo, maxVal + 1)
                lo to hi
            }
            val coverSize = rng.nextInt(1, maxVal + 1)
            val cover = IntArray(coverSize) { it + 1 } // distinct values 1..coverSize
            // Var ids: 0..n-1 = xs, n..n+coverSize-1 = count vars (domain [0, n]).
            val intDomains = Array(n + coverSize) {
                if (it < n) IntDomain(doms[it].first, doms[it].second) else IntDomain(0, n)
            }
            val countVars = IntArray(coverSize) { n + it }
            val cx = LongArray(n) { rng.nextInt(-3, 4).toLong() }
            val cc = LongArray(coverSize) { rng.nextInt(-3, 4).toLong() }
            val objCoeffs = LongArray(n + coverSize) { if (it < n) cx[it] else cc[it - n] }
            val p = Problem(
                numBoolVars = 0,
                numIntVars = n + coverSize,
                intDomains = intDomains,
                factors = arrayOf<Factor>(
                    GlobalCardinality(xs = IntArray(n) { it }, cover = cover, countVars = countVars),
                ),
            )
            val obj = LinearObjective(intCoefficients = objCoeffs)

            // Brute force: minimum objective over every assignment of xs within its domain.
            var brute: Long? = null
            val x = IntArray(n)
            fun rec(i: Int) {
                if (i == n) {
                    var o = 0L
                    for (k in 0 until n) o += cx[k] * x[k]
                    for (c in 0 until coverSize) {
                        var cnt = 0L
                        for (k in 0 until n) if (x[k] == cover[c]) cnt++
                        o += cc[c] * cnt
                    }
                    if (brute == null || o < brute!!) brute = o
                    return
                }
                for (v in doms[i].first..doms[i].second) {
                    x[i] = v
                    rec(i + 1)
                }
            }
            rec(0)

            val r = CpToLpRelaxation(p, obj, gccCountHull = true).build(PropagationSession(p))
            val sol = DualSimplex(r.model).solve()
            checked++
            assertEquals(LpStatus.OPTIMAL, sol.status, "feasible assignment exists but LP not optimal")
            assertEquals(
                brute!!.toDouble(),
                sol.objectiveValue + r.objectiveConstant,
                eps,
                "GCC count hull optimum ${sol.objectiveValue + r.objectiveConstant} != brute $brute",
            )
        }
        assertTrue(checked > 100, "only $checked instances checked")
    }
}
