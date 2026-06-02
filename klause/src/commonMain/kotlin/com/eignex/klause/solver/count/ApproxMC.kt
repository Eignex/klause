package com.eignex.klause.solver.count

import com.eignex.klause.solver.Problem
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.random.Random

/**
 * Approximate model counting (ApproxMC2; Chakraborty, Meel & Vardi) over a Boolean sampling set.
 *
 * Native path: XOR hashes are appended as `Xor` factors and solved on the same backend via
 * [Solver.deriveFor]. Returns a count within a multiplicative `(1 ± ε)` factor with probability
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
        val estimates = ArrayList<Long>(t)
        for (i in 0 until t) {
            val est = core(ctx, thresh, seed = baseSeed + i.toLong())
            if (est != null) estimates.add(est)
        }
        val estimate = if (estimates.isEmpty()) base.count.toLong() else median(estimates)
        // The (ε, δ) guarantee: the true count lies in [estimate/(1+ε), estimate·(1+ε)] w.p. ≥ 1-δ.
        val lower = floor(estimate / (1.0 + eps)).toLong()
        val upper = ceil(estimate * (1.0 + eps)).toLong()
        return Count(estimate = estimate, lower = lower, upper = upper, exact = false, confidence = 1.0 - config.delta)
    }

    /** One ApproxMC iteration: returns the cell estimate, or `null` for a failed (empty-cell) run. */
    private fun core(ctx: CellContext, thresh: Int, seed: Long): Long? {
        val n = ctx.hashDomain.size
        // A single nested hash sequence; prefix of length m gives the m-hash cell (H_1 ⊂ … ⊂ H_n).
        val allHashes = XorHashFamily(ctx.hashDomain, seed).draw(n)
        var m = 1
        while (m <= n) {
            val cell = cellCount(ctx, allHashes.subList(0, m), cap = thresh)
            if (!cell.capped) { // cell.count ≤ thresh: small enough
                if (cell.count == 0) return null // unlucky split emptied the cell — failed run
                return cell.count.toLong() shl m
            }
            m++
        }
        return null
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

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
