package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.SymmetricAllDifferent
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.util.IntArrayList

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

/**
 * Lagrangian-augmented LP cut (#23 ↔ #22): the objective-weighted AllDifferent bound. For an
 * AllDifferent over variables `V`, the minimum of `Σ_{i∈V} c_i·x_i` subject to all-different is the
 * exact min-cost assignment of the objective coefficients to distinct values ([MinCostAssignment]) —
 * a stronger statement than the unweighted Hall sum cut whenever the `c_i` differ. Emitting
 * `Σ_{i∈V} c_i·x_i ≥ assignmentMin` as a cut injects that global, integral bound into the LP, which
 * is exactly the synergy the Lagrangian-augmented LP path provides: a plain multiplier→coefficient
 * adjustment buys nothing for an LP relaxation (LP strong duality), but the integral assignment bound
 * does. The cut is emitted only when the LP point violates it.
 */
internal class AssignmentObjectiveCut(private val intCoef: LongArray) : CutSeparator {
    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val cuts = ArrayList<Cut>()
        for (vars in allDifferentGroups(ctx.problem)) {
            if (vars.size < 2) continue
            // Need a column for every variable and at least one nonzero objective coefficient.
            if (vars.any { ctx.relaxation.intColOf[it] < 0 }) continue
            if (vars.none { intCoef.getOrElse(it) { 0L } != 0L }) continue

            val assignmentMin = assignmentMin(vars, ctx.session) ?: continue
            // Cut is over the nonzero-cost columns; zero-cost variables only shaped the assignment.
            val cols = ArrayList<Int>()
            val coeffs = ArrayList<Long>()
            var lpLhs = 0.0
            for (v in vars) {
                val c = intCoef.getOrElse(v) { 0L }
                if (c == 0L) continue
                val col = ctx.relaxation.intColOf[v]
                cols.add(col)
                coeffs.add(c)
                lpLhs += c.toDouble() * ctx.solution.primal(col)
            }
            if (lpLhs < assignmentMin - tol) {
                cuts.add(Cut(cols.toIntArray(), coeffs.toLongArray(), Relation.GE, assignmentMin))
            }
        }
        return cuts
    }

    /**
     * Exact minimum of `Σ_{i∈V} c_i·x_i` over distinct assignments of the live domains, via
     * [MinCostAssignment]. Returns null when the value set is too large to assign over or the
     * arithmetic overflows (then no cut is produced — sound, just no strengthening).
     */
    private fun assignmentMin(vars: IntArray, session: PropagationSession): Long? {
        val valueIndex = HashMap<Int, Int>()
        val values = IntArrayList()
        for (v in vars) {
            session.intDomain(v).forEach { value ->
                if (value !in valueIndex) {
                    valueIndex[value] = values.size
                    values.add(value)
                }
            }
        }
        if (values.size > MAX_VALUES || values.size < vars.size) return null
        return try {
            val assign = MinCostAssignment(vars.size, values.size)
            for (i in vars.indices) {
                val c = intCoef.getOrElse(vars[i]) { 0L }
                session.intDomain(vars[i]).forEach { value ->
                    assign.addOption(i, valueIndex.getValue(value), mulExact(c, value.toLong()))
                }
            }
            val r = assign.solve()
            if (r.feasible) r.cost else null
        } catch (_: LpOverflowException) {
            null
        }
    }

    private companion object {
        const val MAX_VALUES: Int = 512
    }
}

/**
 * GlobalCardinality sum cuts (#22/#248) — the value-multiplicity generalization of the AllDifferent
 * Hall cut. A *closed* GCC pins every `x_i` to a cover value `v_k`, each used within `[low_k, high_k]`
 * times, so `Σ_{i} x_i` is bounded by the cheapest (and dearest) value distribution honouring those
 * occurrence caps: fill the `low_k` forced occurrences, then spread the remaining slots over the
 * smallest-valued (resp. largest-valued) residual capacity. Ignoring each variable's own domain only
 * relaxes the problem, so the greedy min/max stay valid bounds. With every `high_k = 1` this reduces
 * exactly to the AllDifferent Hall sum.
 *
 * Only the fully-present, closed form is separated. An *open* GCC lets `x_i` take values outside the
 * cover (potentially below every cover value), so the cover-only greedy could over-estimate the true
 * minimum — unsound for a `≥` cut — and is skipped. A factor with optional (maybe-absent) variables is
 * skipped too: the present-count is then a range, which the fixed-`n` greedy does not model.
 */
internal class GccSeparator : CutSeparator {
    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val cuts = ArrayList<Cut>()
        for (factor in ctx.problem.factors) {
            if (factor !is GlobalCardinality || !factor.closed || factor.presents.isNotEmpty()) continue
            val xs = factor.xs
            if (xs.size < 2 || xs.size > MAX_VARS) continue
            val cols = IntArray(xs.size)
            var ok = true
            for (k in xs.indices) {
                val c = ctx.relaxation.intColOf[xs[k]]
                if (c < 0) {
                    ok = false
                    break
                }
                cols[k] = c
            }
            if (!ok) continue

            val bounds = sumBounds(factor, xs.size, ctx.session) ?: continue
            var lpSum = 0.0
            for (c in cols) lpSum += ctx.solution.primal(c)
            if (lpSum < bounds[0] - tol) {
                cuts.add(
                    Cut(cols.copyOf(), LongArray(cols.size) { 1L }, Relation.GE, bounds[0]),
                )
            }
            if (lpSum > bounds[1] + tol) {
                cuts.add(
                    Cut(cols.copyOf(), LongArray(cols.size) { 1L }, Relation.LE, bounds[1]),
                )
            }
        }
        return cuts
    }

    /**
     * `[minSum, maxSum]` of `Σ x_i` over closed distributions of `n` variables honouring each cover
     * value's `[low_k, high_k]` occurrence window. Returns null when the distribution is infeasible
     * (too few/many slots), the cover is too large, or the arithmetic overflows — no cut, sound.
     */
    private fun sumBounds(gcc: GlobalCardinality, n: Int, session: PropagationSession): LongArray? {
        val cover = gcc.cover
        if (cover.isEmpty() || cover.size > MAX_VALUES) return null
        val low = LongArray(cover.size)
        val cap = LongArray(cover.size) // residual capacity high_k - low_k
        var forcedSlots = 0L
        var totalCap = 0L
        val countLow = gcc.countLow
        val countHigh = gcc.countHigh
        val countVars = gcc.countVars
        for (k in cover.indices) {
            val lo: Long
            val hi: Long
            if (countLow != null && countHigh != null) {
                lo = countLow[k].toLong()
                hi = countHigh[k].toLong()
            } else if (countVars != null) {
                val d = session.intDomain(countVars[k])
                lo = d.min.toLong()
                hi = d.max.toLong()
            } else {
                return null
            }
            if (hi < lo || lo < 0L) return null
            low[k] = lo
            cap[k] = hi - lo
            forcedSlots += lo
            totalCap += hi
        }
        if (forcedSlots > n || totalCap < n) return null // distribution infeasible: leave it to propagation

        var forcedSum = 0L
        for (k in cover.indices) forcedSum = addExact(forcedSum, mulExact(low[k], cover[k].toLong()))

        val order = cover.indices.sortedBy { cover[it] }
        return try {
            longArrayOf(
                fill(forcedSum, n - forcedSlots, cover, cap, order),
                fill(forcedSum, n - forcedSlots, cover, cap, order.asReversed()),
            )
        } catch (_: LpOverflowException) {
            null
        }
    }

    /** Spread [remaining] free slots over the residual capacities in [order] (cheapest- or dearest-first). */
    private fun fill(base: Long, remaining: Long, cover: IntArray, cap: LongArray, order: List<Int>): Long {
        var sum = base
        var left = remaining
        for (k in order) {
            if (left == 0L) break
            val take = minOf(cap[k], left)
            sum = addExact(sum, mulExact(take, cover[k].toLong()))
            left -= take
        }
        return sum
    }

    private companion object {
        const val MAX_VARS: Int = 4096
        const val MAX_VALUES: Int = 512
    }
}
