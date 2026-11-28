package com.eignex.klause.lp.cut

import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.mulExact

// Generalizes the all-different Hall sum: a closed GCC bounds `Σ x_i` by the cheapest and dearest distributions
// honouring each value's `[low_k, high_k]` window. An open GCC admits values below every cover value, and
// optional positions make the present count a range, so both are skipped.
internal class GccSeparator : CutSeparator {
    override val family: CutFamily = CutFamily.GCC

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
            // Constant windows make the cut global; count variables only while their live intervals are declared.
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
                    ctx.withIntervalPremises(
                        Cut(cols.copyOf(), LongArray(cols.size) { 1L }, Relation.GE, bounds[0], global),
                        countVars ?: intArrayOf(),
                    ),
                )
            }
            if (lpSum > bounds[1] + tol) {
                cuts.add(
                    ctx.withIntervalPremises(
                        Cut(cols.copyOf(), LongArray(cols.size) { 1L }, Relation.LE, bounds[1], global),
                        countVars ?: intArrayOf(),
                    ),
                )
            }
        }
        return cuts
    }

    // `[minSum, maxSum]`, or null when the distribution is infeasible, the cover too large, or arithmetic overflows.
    private fun sumBounds(gcc: GlobalCardinality, n: Int, session: PropagationSession): LongArray? {
        val cover = gcc.cover
        if (cover.isEmpty() || cover.size > MAX_VALUES) return null
        val low = LongArray(cover.size)
        val cap = LongArray(cover.size)
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
        if (forcedSlots > n || totalCap < n) return null

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
