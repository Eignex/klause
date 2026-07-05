package com.eignex.klause.count

import com.eignex.klause.solver.Problem
import com.eignex.klause.util.LongArrayList
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.random.Random

/**
 * Approximate model counting (ApproxMC2; Chakraborty, Meel & Vardi) over a Boolean sampling set.
 *
 * Native path: XOR hashes are appended as `Xor` factors and solved on the same backend via
 * `Solver.deriveFor`. Returns a count within a multiplicative `(1 ± ε)` factor with probability
 * at least `1 - δ`.
 *
 * Outer loop runs `t = ⌈17·log2(3/δ)⌉` (odd) independent iterations and reports the median.
 * Each iteration draws a nested sequence of hashes and finds the smallest prefix length `m`
 * whose cell holds at most `thresh = 1 + 9.84·(1 + ε/(1+ε))·(1 + 1/ε)²` projected models, then
 * estimates `cellCount · 2^m`.
 */
internal object ApproxMC {

    fun run(problem: Problem, config: ApproxCountConfig): Count {
        val ctx = CellContext.resolve(problem, config.samplingSet, config.intSamplingSet)
        val eps = config.epsilon
        val thresh = threshold(eps)

        // Cheap short-circuit: if the whole problem has ≤ thresh projected models, count exactly.
        val base = cellCount(ctx, hashes = emptyList(), cap = thresh)
        if (!base.capped || ctx.hashDomain.isEmpty()) {
            val c = base.count.toLong()
            return Count(estimate = c, lower = c, upper = c, exact = true, confidence = 1.0)
        }

        val t = iterationCount(config.delta)
        val baseSeed = config.seed ?: Random.Default.nextLong()
        val estimates = LongArrayList(t)
        // Seed each iteration's m-search from the previous iteration's transition point (ApproxMC2).
        var prevM = 1
        for (i in 0 until t) {
            val res = core(ctx, thresh, seed = baseSeed + i.toLong(), startM = prevM)
            if (res != null) {
                estimates.add(res.estimate)
                prevM = res.mStar
            }
        }
        if (estimates.isEmpty()) {
            // No usable cell in any run (e.g. every run hit the per-cell decision budget). Surface
            // "unknown" rather than fabricate base.count (≈thresh) as a point estimate (#79).
            val lo = base.count.toLong()
            return Count(estimate = lo, lower = lo, upper = Long.MAX_VALUE, exact = false, confidence = 0.0)
        }
        val estimate = median(estimates)
        // The (ε, δ) guarantee: the true count lies in [estimate/(1+ε), estimate·(1+ε)] w.p. ≥ 1-δ.
        val lower = floor(estimate / (1.0 + eps)).toLong()
        val upper = ceil(estimate * (1.0 + eps)).toLong()
        return Count(estimate = estimate, lower = lower, upper = upper, exact = false, confidence = 1.0 - config.delta)
    }

    /** A finished iteration: its cell [estimate] and the transition prefix length [mStar] (to seed the next). */
    private data class CoreResult(val estimate: Long, val mStar: Int)

    /**
     * One ApproxMC2 iteration over a nested hash sequence (`H_1 ⊂ … ⊂ H_n`). The cell count is
     * non-increasing in `m`, so "cell ≤ thresh" flips false→true exactly once; a galloping +
     * bisection search seeded from [startM] (the previous iteration's transition) finds the smallest
     * fitting `m` in `O(log n)` solves instead of the `O(n)` linear scan (#92).
     *
     * Returns `count · 2^m` at that `m`; on an empty (over-split) cell it recovers with
     * `thresh · 2^(m-1)` from the level below rather than discarding the run, which would bias the
     * median upward (#79). `null` only when no prefix in `[1, n]` is sub-threshold.
     */
    private fun core(ctx: CellContext, thresh: Int, seed: Long, startM: Int): CoreResult? {
        val n = ctx.hashDomain.size
        val allHashes = XorHashFamily(ctx.hashDomain, seed).draw(n)
        val cache = HashMap<Int, CellResult>()
        fun cellAt(m: Int): CellResult = cache.getOrPut(m) { cellCount(ctx, allHashes.subList(0, m), cap = thresh) }

        // fits(m): the m-hash cell holds ≤ thresh projections; fits(0) is false (caller checked base).
        fun fits(m: Int): Boolean = !cellAt(m).capped

        // Gallop out from the pivot to bracket lo (largest known non-fitting) < hi (smallest fitting).
        val pivot = startM.coerceIn(1, n)
        val lo: Int
        var hi: Int
        if (fits(pivot)) {
            hi = pivot
            var step = 1
            var probe = pivot - 1
            while (probe >= 1 && fits(probe)) {
                hi = probe
                step *= 2
                probe -= step
            }
            lo = probe.coerceAtLeast(0)
        } else {
            var probe = pivot
            var step = 1
            hi = pivot + 1
            while (hi <= n && !fits(hi)) {
                probe = hi
                step *= 2
                hi += step
            }
            if (hi > n) return null // finest cell still over thresh
            lo = probe
        }
        // Bisect (lo, hi]: !fits(lo), fits(hi).
        var low = lo
        var high = hi
        while (high - low > 1) {
            val mid = low + (high - low) / 2
            if (fits(mid)) high = mid else low = mid
        }
        val mStar = high

        val cell = cellAt(mStar)
        if (cell.count > 0) return CoreResult(cell.count.toLong() shl mStar, mStar)
        return CoreResult(thresh.toLong() shl (mStar - 1), mStar) // empty cell: recover from mStar-1
    }

    /** `thresh = 1 + 9.84·(1 + ε/(1+ε))·(1 + 1/ε)²`, rounded up. */
    private fun threshold(epsilon: Double): Int {
        val factor = (1.0 + epsilon / (1.0 + epsilon)) * (1.0 + 1.0 / epsilon) * (1.0 + 1.0 / epsilon)
        return ceil(1.0 + 9.84 * factor).toInt()
    }

    /** `t = ⌈17·log2(3/δ)⌉`, forced odd so the median is unambiguous. */
    private fun iterationCount(delta: Double): Int {
        val t = ceil(17.0 * (ln(3.0 / delta) / ln(2.0))).toInt().coerceAtLeast(1)
        return if (t % 2 == 0) t + 1 else t
    }

    private fun median(values: LongArrayList): Long {
        values.sort()
        return values[values.size / 2]
    }
}
