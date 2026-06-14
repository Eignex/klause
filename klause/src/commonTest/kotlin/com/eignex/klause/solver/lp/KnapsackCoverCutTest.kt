package com.eignex.klause.solver.lp

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #286: knapsack cover cuts for `Σ w_i·x_i ≤ b` PseudoBoolean rows. */
class KnapsackCoverCutTest {

    private fun posLits(n: Int) = IntArray(n) { Lit.make(it, true) }

    private fun coverVarsOf(r: LpRelaxation, cut: Cut): Set<Int> = cut.cols.map { r.colVarId[it] }.toSet()

    @Test
    fun `separates a violated cover and the cut is valid`() {
        // 3 items, weights [3,3,2], capacity 4. Maximising x0+x1 drives the LP to x0=x1=2/3, and the
        // pair {0,1} weighs 6 > 4 (a cover), so x0+x1 <= 1 is violated (4/3 > 1).
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(PseudoBoolean(intArrayOf(3, 3, 2), posLits(3), PbOp.LE, 4)),
        )
        val r = CpToLpRelaxation(p, LinearObjective(boolWeights = longArrayOf(-1, -1, 0))).build(PropagationSession(p))
        val sol = DualSimplex(r.model).solve()
        val cuts = KnapsackCoverSeparator().separate(CutContext(p, r, sol, PropagationSession(p)))

        assertTrue(cuts.isNotEmpty(), "a violated cover should be separated")
        val cut = cuts.first()
        assertEquals(Relation.LE, cut.rel)
        assertEquals(setOf(0, 1), coverVarsOf(r, cut))
        assertEquals((cut.cols.size - 1).toLong(), cut.rhs)
    }

    @Test
    fun `extended cover folds in a heavy non-cover item and separates a point the bare cover misses`() {
        // 3 items, weights [3,3,3], capacity 4: at most one item fits. Maximising x0+x1+x2 drives the LP
        // to 4/9 each. The bare cover {0,1} (3+3>4) has Σx* = 8/9 < 1 — NOT violated — but extending it
        // with item 2 (weight 3 >= the cover max 3) gives x0+x1+x2 <= 1, violated at 4/3 (#552).
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(PseudoBoolean(intArrayOf(3, 3, 3), posLits(3), PbOp.LE, 4)),
        )
        val r = CpToLpRelaxation(p, LinearObjective(boolWeights = longArrayOf(-1, -1, -1))).build(PropagationSession(p))
        val sol = DualSimplex(r.model).solve()
        val cuts = KnapsackCoverSeparator().separate(CutContext(p, r, sol, PropagationSession(p)))
        assertTrue(cuts.isNotEmpty(), "the extended cover should separate the all-4/9 point")
        val cut = cuts.first()
        assertEquals(setOf(0, 1, 2), coverVarsOf(r, cut), "all three items are in the extended cover")
        assertEquals(1L, cut.rhs, "rhs stays |C| - 1 = 1")
    }

    @Test
    fun `cover cuts exclude no knapsack-feasible point`() {
        val rng = Random(20260610)
        var separated = 0
        repeat(1500) {
            val n = rng.nextInt(2, 6)
            val weights = IntArray(n) { rng.nextInt(1, 6) }
            val bnd = rng.nextInt(1, weights.sum())
            val p = Problem(
                numBoolVars = n,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(PseudoBoolean(weights, posLits(n), PbOp.LE, bnd)),
            )
            val obj = LinearObjective(boolWeights = LongArray(n) { -rng.nextInt(0, 4).toLong() })
            val r = CpToLpRelaxation(p, obj).build(PropagationSession(p))
            val sol = DualSimplex(r.model).solve()
            if (sol.status != LpStatus.OPTIMAL) return@repeat
            val cuts = KnapsackCoverSeparator().separate(CutContext(p, r, sol, PropagationSession(p)))
            if (cuts.isEmpty()) return@repeat
            separated++
            for (cut in cuts) {
                val coverVars = coverVarsOf(r, cut)
                // Every 0/1 assignment that satisfies the knapsack row must satisfy the cut.
                for (mask in 0 until (1 shl n)) {
                    var w = 0
                    var inCover = 0
                    for (v in 0 until n) {
                        if (mask and (1 shl v) != 0) {
                            w += weights[v]
                            if (v in coverVars) inCover++
                        }
                    }
                    if (w <= bnd) {
                        assertTrue(inCover <= cut.rhs, "cover cut excludes feasible mask $mask")
                    }
                }
            }
        }
        assertTrue(separated > 50, "only $separated instances produced a cover cut")
    }
}
