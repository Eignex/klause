package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.LpStatus
import com.eignex.klause.lp.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #655 (Tranche C): the per-axis cumulative energetic / area bound for [Diffn]. A constant-size diffn
 * projects onto each axis as a cumulative (capacity = the maximum perpendicular extent), so the
 * energetic makespan row lower-bounds a strip-length variable `L ≥ xs(i) + widths(i)`. Its `t1 = min-est`
 * case is the area bound `Σ wᵢ·hᵢ ≤ W·H`. The row is a sound relaxation, so the LP bound never exceeds
 * the true minimum extent — checked against brute force.
 */
class CpToLpRelaxationDiffnTest {

    private val eps = 1e-6

    /** `min L` LP bound with the diffn projection [diffn] on/off. Var layout: `0..n-1` = xs,
     *  `n..2n-1` = ys, `2n` = the strip-length makespan `L`, linked by `L ≥ xs(i) + widths(i)`. */
    private fun stripLengthBound(p: Problem, lVar: Int, diffn: Boolean): Double {
        val obj = LinearObjective(intCoefficients = LongArray(p.numIntVars) { if (it == lVar) 1L else 0L })
        val r = CpToLpRelaxation(p, obj, diffn = diffn).build(PropagationSession(p))
        val sol = solveLp(r.model)
        assertEquals(LpStatus.OPTIMAL, sol.status)
        return sol.objectiveValue
    }

    @Test
    fun `area bound lower-bounds the strip length`() {
        // 3 rectangles 2x2 in a strip of height 4 (y ∈ [0,2] ⇒ extent (2+2)−0 = 4). Total area 12 over
        // capacity 4 ⇒ L ≥ 3 — sound (the true minimum is 4) and strictly past the bare M ≥ xs+2 = 2.
        val n = 3
        val lVar = 2 * n // = 6
        val domains = Array(2 * n + 1) { i ->
            when {
                i < n -> IntDomain(0, 10)

                // xs
                i < 2 * n -> IntDomain(0, 2)

                // ys
                else -> IntDomain(0, 20) // L
            }
        }
        val factors = ArrayList<Factor>()
        for (i in 0 until n) factors.add(Linear(intArrayOf(1, -1), intArrayOf(lVar, i), LinearOp.GE, 2))
        factors.add(Diffn(IntArray(n) { it }, IntArray(n) { n + it }, longArrayOf(2, 2, 2), longArrayOf(2, 2, 2)))
        val p = Problem(0, 2 * n + 1, domains, factors.toTypedArray())

        assertEquals(2.0, stripLengthBound(p, lVar, diffn = false), eps, "bare LP sees only L ≥ xs+2")
        assertEquals(3.0, stripLengthBound(p, lVar, diffn = true), eps, "area bound: 12 / height 4 = 3")
    }

    @Test
    fun `randomized diffn area bound never exceeds the true minimum strip length`() {
        val rng = Random(20260616)
        var checked = 0
        var nontrivial = 0
        repeat(400) { _ ->
            val n = rng.nextInt(2, 4)
            val w = LongArray(n) { rng.nextInt(1, 4).toLong() }
            val h = LongArray(n) { rng.nextInt(1, 4).toLong() }
            val xHi = rng.nextInt(3, 7)
            val yHi = rng.nextInt(2, 5)
            val lVar = 2 * n
            val horizon = xHi + 4
            val domains = Array(2 * n + 1) { i ->
                when {
                    i < n -> IntDomain(0, xHi.toLong())
                    i < 2 * n -> IntDomain(0, yHi.toLong())
                    else -> IntDomain(0, horizon.toLong())
                }
            }
            val factors = ArrayList<Factor>()
            for (i in 0 until n) factors.add(Linear(longArrayOf(1, -1), intArrayOf(lVar, i), LinearOp.GE, w[i]))
            factors.add(Diffn(IntArray(n) { it }, IntArray(n) { n + it }, w, h))
            val p = Problem(0, 2 * n + 1, domains, factors.toTypedArray())

            // Brute force: the minimum L = max(xᵢ + wᵢ) over every non-overlapping placement.
            val px = IntArray(n)
            val py = IntArray(n)
            fun overlaps(a: Int, b: Int): Boolean {
                val xSep = px[a] + w[a] <= px[b] || px[b] + w[b] <= px[a]
                val ySep = py[a] + h[a] <= py[b] || py[b] + h[b] <= py[a]
                return !xSep && !ySep
            }
            var brute: Long? = null
            fun rec(i: Int) {
                if (i == n) {
                    for (a in 0 until n) for (b in a + 1 until n) if (overlaps(a, b)) return
                    var l = 0L
                    for (k in 0 until n) l = maxOf(l, px[k] + w[k])
                    if (brute == null || l < brute!!) brute = l
                    return
                }
                for (xv in 0..xHi) {
                    for (yv in 0..yHi) {
                        px[i] = xv
                        py[i] = yv
                        rec(i + 1)
                    }
                }
            }
            rec(0)
            val trueMin = brute ?: return@repeat // no feasible packing: nothing to bound against
            checked++

            val bound = stripLengthBound(p, lVar, diffn = true)
            assertTrue(
                bound <= trueMin + eps,
                "diffn LP bound $bound exceeds the true minimum strip length $trueMin (unsound)",
            )
            if (bound > w.max() + eps) nontrivial++ // the bound beat the trivial single-rectangle L ≥ max wᵢ
        }
        assertTrue(checked > 200, "only $checked feasible instances checked")
        assertTrue(nontrivial > 0, "the area bound never beat the trivial bound — test exercises nothing")
    }
}
