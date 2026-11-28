package com.eignex.klause.lp.cut

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.factor.global.SymmetricAllDifferent
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.util.numbers.addExact

// Pairwise all-different variable groups: plain AllDifferent, SymmetricAllDifferent, and each side of Inverse,
// all injective. Both Hall-set and assignment-objective cuts are valid over any such group.
internal fun allDifferentGroups(problem: Problem): List<IntArray> {
    val groups = ArrayList<IntArray>()
    for (factor in problem.factors) {
        when (factor) {
            is AllDifferent -> if (factor.presents.isEmpty() && factor.exceptSet.isEmpty()) groups.add(factor.vars)

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

// The base relaxation skips all-different. For a group S, `Σ_{i∈S} x_i` lies between the sums of the |S| smallest
// and largest distinct values in the union of the `[min, max]` intervals; ignoring holes only widens the pool.
// Only the full-group Hall set is separated. Williams and Yan, "Representations of the all_different predicate
// of constraint satisfaction in integer programming", INFORMS J. Comput. 2001,
// https://doi.org/10.1287/ijoc.13.2.96.10515.
internal class AllDifferentSeparator : CutSeparator {
    override val family: CutFamily = CutFamily.ALL_DIFFERENT

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

            val (minSum, maxSum) = try {
                distinctSumBounds(vars, ctx.session)
            } catch (_: ArithmeticException) {
                continue
            }
            // The Hall bounds read only the live [min, max] intervals, so the cut is global exactly
            // when those are still the declared intervals (always at the root).
            val global = liveIntervalsAreDeclared(ctx, vars)
            var lpSum = 0.0
            for (c in cols) lpSum += ctx.primalOf(c)
            val ones = LongArray(cols.size) { 1L }
            if (lpSum < minSum - tol) {
                cuts.add(ctx.withIntervalPremises(Cut(cols.copyOf(), ones, Relation.GE, minSum, global), vars))
            }
            if (lpSum > maxSum + tol) {
                cuts.add(ctx.withIntervalPremises(
                    Cut(cols.copyOf(), LongArray(cols.size) { 1L }, Relation.LE, maxSum, global), vars,
                ))
            }
        }
        return cuts
    }

    private fun distinctSumBounds(vars: IntArray, session: PropagationSession): Pair<Long, Long> {
        val ranges = vars.map {
            val d = session.intDomain(it)
            d.min to d.max
        }
            .sortedBy { it.first }
        val merged = ArrayList<LongArray>()
        for ((lo, hi) in ranges) {
            val last = merged.lastOrNull()
            if (last != null && (last[1] == Long.MAX_VALUE || lo <= last[1] + 1)) {
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
                if (v == r[1]) break
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
                if (v == r[0]) break
                v--
            }
            if (taken == n) break
        }
        return minSum to maxSum
    }
}
