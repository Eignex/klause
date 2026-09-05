package com.eignex.klause.lp.cut

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.factor.global.SymmetricAllDifferent
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.addExact

/**
 * Variable groups that are pairwise all-different, harvested from the LP-relevant globals so the
 * Hall-set sum cut ([AllDifferentSeparator]) and the assignment-objective cut
 * ([AssignmentObjectiveCut]) — both valid over any all-different set — reach beyond plain
 * [AllDifferent]:
 *  - [AllDifferent] itself: its variables.
 *  - [SymmetricAllDifferent]: `xs` is a self-inverse permutation, hence all-different.
 *  - [Inverse]: each side (`f`, `g`) is injective (a channelled bijection), so each is its own
 *    all-different set; the two are returned separately.
 */
internal fun allDifferentGroups(problem: Problem): List<IntArray> {
    val groups = ArrayList<IntArray>()
    for (factor in problem.factors) {
        when (factor) {
            is AllDifferent -> groups.add(factor.vars)

            is SymmetricAllDifferent -> groups.add(factor.xs)

            is Inverse -> {
                groups.add(factor.f)
                groups.add(factor.g)
            }

            else -> Unit
        }
    }
    return groups
}

/**
 * AllDifferent cuts. AllDifferent is skipped by the base relaxation; this re-introduces
 * its strength linearly. For a set S of all-different variables, any assignment uses |S| distinct
 * values, so `Σ_{i∈S} x_i` is bounded below by the sum of the |S| smallest distinct values available
 * across their domains, and above by the sum of the |S| largest — Hall-set bounds. Treating each
 * domain as its `[min, max]` interval (ignoring holes) only widens the value pool, so the bounds stay
 * valid (a sound under-/over-estimate). The full variable set is the |S| = n Hall set; this first
 * implementation separates that set (the dominant cut) when the LP point violates it.
 *
 * The set S is any group from [allDifferentGroups], so this also covers [SymmetricAllDifferent] and
 * each side of [Inverse] — all injective, hence all-different.
 */
internal class AllDifferentSeparator : CutSeparator {
    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val cuts = ArrayList<Cut>()
        for (vars in allDifferentGroups(ctx.problem)) {
            if (vars.size < 2) continue
            val cols = IntArray(vars.size)
            var ok = true
            for (k in vars.indices) {
                val c = ctx.relaxation.intColOf[vars[k]]
                if (c < 0) {
                    ok = false
                    break
                }
                cols[k] = c
            }
            if (!ok) continue

            val (minSum, maxSum) = distinctSumBounds(vars, ctx.session)
            // The Hall bounds read only the live [min, max] intervals, so the cut is global exactly
            // when those are still the declared intervals (always at the root).
            val global = liveIntervalsAreDeclared(ctx, vars)
            var lpSum = 0.0
            for (c in cols) lpSum += ctx.primalOf(c)
            val ones = LongArray(cols.size) { 1L }
            if (lpSum < minSum - tol) cuts.add(Cut(cols.copyOf(), ones, Relation.GE, minSum, global))
            if (lpSum > maxSum + tol) {
                cuts.add(Cut(cols.copyOf(), LongArray(cols.size) { 1L }, Relation.LE, maxSum, global))
            }
        }
        return cuts
    }

    /**
     * Sum of the [vars].size smallest, and largest, distinct values across the union of the live
     * `[min, max]` domain intervals — a valid lower/upper bound on `Σ x_i` under all-different.
     */
    private fun distinctSumBounds(vars: IntArray, session: PropagationSession): Pair<Long, Long> {
        // Merge domain intervals into disjoint ascending ranges.
        val ranges = vars.map {
            val d = session.intDomain(it)
            d.min to d.max
        }
            .sortedBy { it.first }
        val merged = ArrayList<LongArray>() // [lo, hi]
        for ((lo, hi) in ranges) {
            val last = merged.lastOrNull()
            if (last != null && lo <= last[1] + 1) {
                last[1] = maxOf(last[1], hi)
            } else {
                merged.add(longArrayOf(lo, hi))
            }
        }
        val n = vars.size
        var minSum = 0L
        var taken = 0
        for (r in merged) {
            var v = r[0]
            while (v <= r[1] && taken < n) {
                minSum = addExact(minSum, v)
                taken++
                v++
            }
            if (taken == n) break
        }
        var maxSum = 0L
        taken = 0
        for (i in merged.indices.reversed()) {
            val r = merged[i]
            var v = r[1]
            while (v >= r[0] && taken < n) {
                maxSum = addExact(maxSum, v)
                taken++
                v--
            }
            if (taken == n) break
        }
        return minSum to maxSum
    }
}
