package com.eignex.klause.lp.cut

import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.internals.permitsLongPseudoBoolean
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.model.PbOp
import com.eignex.util.arrays.argsortBy
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.MutableIntObjectMap
import com.eignex.util.numbers.addExact

// A cover `C` with `Σ_C w_i > b` gives `Σ_{i∈C} x_i ≤ |C| − 1`. The violated cover is found greedily by fractional
// value, minimised, then sequentially up-lifted (Crowder, Johnson and Padberg, "Solving large-scale zero-one
// linear programming problems", Oper. Res. 1983, https://doi.org/10.1287/opre.31.5.803). The lifting max is a
// GUB knapsack over at-most-one cliques, which strengthens the lift. Mixed-sign rows would need complementing
// and are skipped.
internal class KnapsackCoverSeparator : CutSeparator {
    override val family: CutFamily = CutFamily.KNAPSACK_COVER

    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val cuts = ArrayList<Cut>()
        val conflict = conflictGraph(ctx.problem).adjacency
        for (factor in ctx.problem.factors) {
            if (factor !is PseudoBoolean || !permitsLongPseudoBoolean(factor.constants) ||
                factor.op != PbOp.LE
            ) continue
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
            val order = argsortBy(k) { left, right -> xstar[right].compareTo(xstar[left]) }
            val inCover = BooleanArray(k)
            var coverCount = 0
            var wsum = 0L
            for (i in order) {
                inCover[i] = true
                coverCount++
                wsum = addExact(wsum, factor.weights[i])
                if (wsum > b) break
            }
            if (wsum <= b) continue
            // Drop the lightest members while the sum still exceeds the bound.
            run {
                var cw = wsum
                val cover = IntArrayList(coverCount)
                for (i in 0 until k) if (inCover[i]) cover.add(i)
                val coverOrder = argsortBy(cover.size) { left, right ->
                    factor.weights[cover[left]].compareTo(factor.weights[cover[right]])
                }
                for (position in coverOrder) {
                    val i = cover[position]
                    if (cw - factor.weights[i] > b) {
                        inCover[i] = false
                        coverCount--
                        cw -= factor.weights[i]
                    }
                }
            }
            val r = (coverCount - 1).toLong()
            // αₖ = r − max{ Σ aᵢxᵢ : Σ wᵢxᵢ ≤ b − wₖ over lifted items, at most one per clique }. A capacity too
            // large for the DP emits the bare minimal cover.
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
                val nonCover = IntArrayList(k - coverCount)
                for (i in 0 until k) if (!inCover[i]) nonCover.add(i)
                val nonCoverOrder = argsortBy(nonCover.size) { left, right ->
                    factor.weights[nonCover[right]].compareTo(factor.weights[nonCover[left]])
                }
                for (position in nonCoverOrder) {
                    val kk = nonCover[position]
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
                cuts.add(Cut(cutCols, cutCoeff, Relation.LE, r, global = true))
            }
        }
        return cuts
    }

    // Ignoring cross-group conflicts keeps the lifting max an over-estimate, so the coefficients stay valid.
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

    // A DP over capacity, one clique group at a time so each group contributes at most one item.
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
        val next = LongArray(cap + 1)
        byGroup.forEach { _, idxs ->
            dp.copyInto(next)
            idxs.forEach { t ->
                val w = weights[lifted[t]]
                if (w > cap) return@forEach
                val wi = w.toInt()
                val v = coeff[t]
                // dp[c - w] is the pre-group value, so at most one item from the group is taken.
                for (c in cap downTo wi) {
                    val cand = dp[c - wi] + v
                    if (cand > next[c]) next[c] = cand
                }
            }
            next.copyInto(dp)
        }
        return dp[cap]
    }

    private companion object {
        const val MAX_LIFT_CAP: Long = 4096L
    }
}
