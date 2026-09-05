package com.eignex.klause.lp.cut

import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.addExact

/**
 * Knapsack cover cuts for a `Σ w_i·x_i ≤ b` PseudoBoolean row with positive weights over
 * 0/1 variables — the shape the dropped `Knapsack` factor decomposes to, so these recover its
 * strength. A *cover* `C` is a set of items with `Σ_{C} w_i > b`: no feasible 0/1 point can set all of
 * `C`, so `Σ_{i∈C} x_i ≤ |C| − 1` is a valid inequality. Separation finds a violated cover greedily by
 * fractional value: take the highest-`x*` items until their weight exceeds `b`; if the resulting
 * cover's `Σ x*` exceeds `|C| − 1` the cut is violated and emitted. The cover is then minimised and
 * **lifted**: every non-cover variable is up-lifted by sequential lifting — its coefficient is
 * `αₖ = (|C| − 1) − max{ Σ aᵢxᵢ : Σ wᵢxᵢ ≤ b − wₖ }` over the already-lifted items, where the max is a
 * small DP. When an at-most-one clique graph is present (binary clauses / AMO factors), the max is
 * solved as a GUB knapsack (at most one item per clique), shrinking it and so strengthening the lift —
 * such cuts are valid in conjunction with the clique rows, which are global and in the relaxation.
 * Mixed-sign rows (negated literals or non-positive weights) are skipped — their cover form needs
 * complementing, deferred.
 */
internal class KnapsackCoverSeparator : CutSeparator {
    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val cuts = ArrayList<Cut>()
        // At-most-one conflict graph (binary clauses + AMO factors), shared across all knapsacks. Used
        // for GUB lifting: within a clique at most one item is 1, which strengthens the lift.
        val conflict = conflictGraph(ctx.problem).adjacency
        for (factor in ctx.problem.factors) {
            if (factor !is PseudoBoolean || factor.op != PbOp.LE) continue
            if (factor.weights.any { it <= 0 } || factor.literals.any { !Lit.isPositive(it) }) continue
            val k = factor.literals.size
            if (k < 2) continue
            val b = factor.bound
            val cols = IntArray(k)
            val xstar = DoubleArray(k)
            var ok = true
            for (i in 0 until k) {
                val col = ctx.relaxation.boolColOf[Lit.variable(factor.literals[i])]
                if (col < 0) {
                    ok = false
                    break
                }
                cols[i] = col
                xstar[i] = ctx.primalOf(col)
            }
            if (!ok) continue
            // Greedy cover: highest fractional value first, until the weight sum exceeds the bound.
            val order = (0 until k).sortedByDescending { xstar[it] }
            val inCover = BooleanArray(k)
            var coverCount = 0
            var wsum = 0L
            for (i in order) {
                inCover[i] = true
                coverCount++
                wsum = addExact(wsum, factor.weights[i])
                if (wsum > b) break
            }
            if (wsum <= b) continue // whole set fits under the bound — no cover, no cut
            // Minimise the cover: drop the lightest members while the sum still exceeds the bound, so
            // the base inequality `Σ_C x ≤ |C| − 1` is as strong as possible before lifting.
            run {
                var cw = wsum
                for (i in (0 until k).filter { inCover[it] }.sortedBy { factor.weights[it] }) {
                    if (cw - factor.weights[i] > b) {
                        inCover[i] = false
                        coverCount--
                        cw -= factor.weights[i]
                    }
                }
            }
            val r = (coverCount - 1).toLong()
            // Sequential up-lifting: start from the cover (coefficient 1) and lift each non-cover
            // variable k with the exact coefficient αₖ = r − max{ Σ aᵢxᵢ : Σ wᵢxᵢ ≤ b − wₖ over lifted
            // items, at most one per AMO clique }. The clique cap (GUB lifting) shrinks that max, giving
            // a larger — still valid — αₖ. The max is a small GUB-knapsack solved by DP; skip lifting
            // when the capacity would make the DP too large (emit the bare minimal cover then).
            val liftedPos = IntArrayList(coverCount)
            val liftedCoeff = LongArrayList(coverCount)
            for (i in 0 until k) {
                if (inCover[i]) {
                    liftedPos.add(i)
                    liftedCoeff.add(1L)
                }
            }
            if (b <= MAX_LIFT_CAP) {
                val groupOf = cliquePartition(k, factor.literals, conflict)
                val nonCover = (0 until k).filter { !inCover[it] }.sortedByDescending { factor.weights[it] }
                for (kk in nonCover) {
                    val cap = b - factor.weights[kk]
                    val maxv = if (cap < 0) {
                        0L
                    } else {
                        gubKnapsackMax(
                            liftedPos,
                            liftedCoeff,
                            factor.weights,
                            groupOf,
                            cap.toInt(),
                        )
                    }
                    val alpha = r - maxv
                    if (alpha > 0) {
                        liftedPos.add(kk)
                        liftedCoeff.add(alpha)
                    }
                }
            }
            var lhs = 0.0
            for (t in 0 until liftedPos.size) lhs += liftedCoeff[t] * xstar[liftedPos[t]]
            if (lhs > r + tol) {
                val cutCols = IntArray(liftedPos.size) { cols[liftedPos[it]] }
                val cutCoeff = LongArray(liftedPos.size) { liftedCoeff[it] }
                // Read off the row's weights, bound, and the (global) clique graph — global by construction.
                cuts.add(Cut(cutCols, cutCoeff, Relation.LE, r, global = true))
            }
        }
        return cuts
    }

    /** Greedy clique partition of the `k` knapsack positions over the [conflict] graph: each group is a
     *  set of pairwise mutually-exclusive items. Used as the GUB structure for [gubKnapsackMax]. Using
     *  only a partition's worth of edges (cross-group conflicts are ignored) keeps the lifting max an
     *  over-estimate, so the derived coefficients stay valid. */
    private fun cliquePartition(k: Int, literals: IntArray, conflict: MutableIntObjectMap<IntHashSet>): IntArray {
        val vars = IntArray(k) { Lit.variable(literals[it]) }
        fun adjacent(i: Int, j: Int): Boolean = conflict[vars[i]]?.contains(vars[j]) == true
        val group = IntArray(k) { -1 }
        var g = 0
        for (i in 0 until k) {
            if (group[i] != -1) continue
            group[i] = g
            val members = arrayListOf(i)
            for (j in i + 1 until k) {
                if (group[j] == -1 && members.all { adjacent(it, j) }) {
                    group[j] = g
                    members.add(j)
                }
            }
            g++
        }
        return group
    }

    /** Max `Σ coeffᵢ·xᵢ` over the [lifted] items with `Σ weightᵢ·xᵢ ≤ cap` and at most one item taken
     *  per clique group ([groupOf] over the items' positions). A GUB (generalised-upper-bound) knapsack
     *  solved by DP over the capacity, processing one clique group at a time so each contributes ≤ 1. */
    private fun gubKnapsackMax(
        lifted: IntArrayList,
        coeff: LongArrayList,
        weights: LongArray,
        groupOf: IntArray,
        cap: Int,
    ): Long {
        val byGroup = MutableIntObjectMap<IntArrayList>()
        for (t in 0 until lifted.size) byGroup.getOrPut(groupOf[lifted[t]]) { IntArrayList() }.add(t)
        val dp = LongArray(cap + 1)
        byGroup.forEach { _, idxs ->
            val next = dp.copyOf() // "take none from this group"
            idxs.forEach { t ->
                val w = weights[lifted[t]]
                if (w > cap) return@forEach // an item heavier than the capacity is never taken
                val wi = w.toInt() // w <= cap here, so the DP index stays in range
                val v = coeff[t]
                // dp[c - w] is the pre-group value, so at most one item from the group is taken.
                for (c in cap downTo wi) {
                    val cand = dp[c - wi] + v
                    if (cand > next[c]) next[c] = cand
                }
            }
            for (c in 0..cap) dp[c] = next[c]
        }
        return dp[cap]
    }

    private companion object {
        /** Capacity ceiling for the lifting DP (array size `cap + 1`); above it, emit the bare cover. */
        const val MAX_LIFT_CAP: Long = 4096L
    }
}
