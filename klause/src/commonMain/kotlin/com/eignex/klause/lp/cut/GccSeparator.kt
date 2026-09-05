package com.eignex.klause.lp.cut

import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact

/**
 * GlobalCardinality sum cuts — the value-multiplicity generalization of the AllDifferent
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
            // The greedy distribution reads only the occurrence windows: factor constants make the
            // cut global outright; count variables make it global while their live intervals are
            // still the declared ones.
            val countVars = factor.countVars
            val global = if (factor.countLow != null && factor.countHigh != null) {
                true
            } else {
                countVars != null && liveIntervalsAreDeclared(ctx, countVars)
            }
            var lpSum = 0.0
            for (c in cols) lpSum += ctx.primalOf(c)
            if (lpSum < bounds[0] - tol) {
                cuts.add(
                    Cut(cols.copyOf(), LongArray(cols.size) { 1L }, Relation.GE, bounds[0], global),
                )
            }
            if (lpSum > bounds[1] + tol) {
                cuts.add(
                    Cut(cols.copyOf(), LongArray(cols.size) { 1L }, Relation.LE, bounds[1], global),
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
                lo = d.min
                hi = d.max
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
        for (k in cover.indices) forcedSum = addExact(forcedSum, mulExact(low[k], cover[k]))

        val order = cover.indices.sortedBy { cover[it] }
        return try {
            longArrayOf(
                fill(forcedSum, n - forcedSlots, cover, cap, order),
                fill(forcedSum, n - forcedSlots, cover, cap, order.asReversed()),
            )
        } catch (_: CheckedLongOverflowException) {
            null
        }
    }

    /** Spread [remaining] free slots over the residual capacities in [order] (cheapest- or dearest-first). */
    private fun fill(base: Long, remaining: Long, cover: LongArray, cap: LongArray, order: List<Int>): Long {
        var sum = base
        var left = remaining
        for (k in order) {
            if (left == 0L) break
            val take = minOf(cap[k], left)
            sum = addExact(sum, mulExact(take, cover[k]))
            left -= take
        }
        return sum
    }

    private companion object {
        const val MAX_VARS: Int = 4096
        const val MAX_VALUES: Int = 512
    }
}
