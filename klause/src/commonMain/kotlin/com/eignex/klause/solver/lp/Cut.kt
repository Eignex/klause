package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.propagation.PropagationSession

/**
 * A linear inequality `Σ coeffs[k]·x_{cols[k]} rel rhs` over LP columns, added to the relaxation to
 * cut off a fractional LP point (#22). Columns index the relaxation's structural columns; the cut
 * must be valid — satisfied by every integer-feasible point — so it never removes a real solution.
 */
internal class Cut(val cols: IntArray, val coeffs: LongArray, val rel: Relation, val rhs: Long) {
    /** A stable key for deduplicating cuts across separation rounds (ignores column order). */
    fun key(): String {
        val terms = cols.indices.sortedBy { cols[it] }.joinToString(",") { "${cols[it]}:${coeffs[it]}" }
        return "$terms|$rel|$rhs"
    }
}

/** Everything a separator needs: the problem, the current relaxation, its LP solution, the session. */
internal class CutContext(
    val problem: Problem,
    val relaxation: LpRelaxation,
    val solution: LpSolution,
    val session: PropagationSession,
)

/**
 * Separates violated cuts from a fractional LP solution (#22). Implementations inspect the LP point
 * and the problem structure and return cuts the point violates. Returning only violated cuts (rather
 * than all valid ones) keeps the LP from growing with constraints it does not need.
 */
internal interface CutSeparator {
    fun separate(ctx: CutContext): List<Cut>
}

/**
 * AllDifferent cuts (#22). AllDifferent is skipped by the base relaxation (#19); this re-introduces
 * its strength linearly. For a set S of all-different variables, any assignment uses |S| distinct
 * values, so `Σ_{i∈S} x_i` is bounded below by the sum of the |S| smallest distinct values available
 * across their domains, and above by the sum of the |S| largest — Hall-set bounds. Treating each
 * domain as its `[min, max]` interval (ignoring holes) only widens the value pool, so the bounds stay
 * valid (a sound under-/over-estimate). The full variable set is the |S| = n Hall set; this first
 * implementation separates that set (the dominant cut) when the LP point violates it.
 */
internal class AllDifferentSeparator : CutSeparator {
    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val cuts = ArrayList<Cut>()
        for (factor in ctx.problem.factors) {
            if (factor !is AllDifferent) continue
            val vars = factor.vars
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
            var lpSum = 0.0
            for (c in cols) lpSum += ctx.solution.primal(c)
            val ones = LongArray(cols.size) { 1L }
            if (lpSum < minSum - tol) cuts.add(Cut(cols.copyOf(), ones, Relation.GE, minSum))
            if (lpSum > maxSum + tol) cuts.add(Cut(cols.copyOf(), LongArray(cols.size) { 1L }, Relation.LE, maxSum))
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
            d.min.toLong() to d.max.toLong()
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
