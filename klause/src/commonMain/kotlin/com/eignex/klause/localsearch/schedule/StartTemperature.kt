package com.eignex.klause.localsearch.schedule

import kotlin.math.ln

/**
 * Calibrates the start temperature of an annealing [Schedule] from a short warm-up sample of
 * candidate move-cost deltas, instead of a hand-picked constant.
 *
 * The Metropolis rule accepts a worsening move of size `δ` with probability `exp(-δ / T)`. To make
 * an *average* worsening move accept with a target probability, solve for the temperature:
 *
 *   `T0 = -meanPositiveDelta / ln(targetAcceptance)`
 *
 * so a high target (e.g. 0.8) yields a hot start that accepts almost anything, and the schedule then
 * cools from there. Driving T0 off the observed deltas makes it scale with the instance
 * automatically — a problem whose moves swing the cost by larger amounts gets a proportionally
 * hotter start, no explicit size term needed.
 *
 * When the search is **warm-started** from a shared incumbent, the seeded assignment is already good,
 * so a full-hot start would immediately scramble it; a warm-start factor (`< 1`) scales T0 down to
 * protect the seed through the first cool phase.
 */
object StartTemperature {
    /**
     * @param deltas candidate move-cost deltas (new − incumbent) sampled during warm-up; only the
     *   strictly-positive (worsening) entries inform the calibration
     * @param targetAcceptance desired acceptance probability for an average worsening move, in
     *   `(0, 1)` — higher = hotter start
     * @param warmStart whether the run is seeded from an existing incumbent
     * @param warmStartFactor scale applied to T0 when [warmStart] is true, in `(0, 1]`
     * @param minTemperature floor returned when no worsening deltas were sampled (a flat or
     *   all-improving warm-up gives no signal), and a lower bound on the result
     * @return the calibrated start temperature, never below [minTemperature]
     */
    fun calibrate(
        deltas: DoubleArray,
        targetAcceptance: Double = 0.8,
        warmStart: Boolean = false,
        warmStartFactor: Double = 0.5,
        minTemperature: Double = 1e-3,
    ): Double {
        require(targetAcceptance > 0.0 && targetAcceptance < 1.0) {
            "targetAcceptance must be in (0, 1), got $targetAcceptance"
        }
        require(warmStartFactor > 0.0 && warmStartFactor <= 1.0) {
            "warmStartFactor must be in (0, 1], got $warmStartFactor"
        }
        require(minTemperature > 0.0) { "minTemperature must be positive, got $minTemperature" }

        var sum = 0.0
        var n = 0
        for (d in deltas) {
            if (d > 0.0) {
                sum += d
                n++
            }
        }
        if (n == 0) return minTemperature
        val meanPositive = sum / n
        // ln(targetAcceptance) < 0 since targetAcceptance ∈ (0, 1), so T0 > 0.
        val t0 = -meanPositive / ln(targetAcceptance)
        val scaled = if (warmStart) t0 * warmStartFactor else t0
        return scaled.coerceAtLeast(minTemperature)
    }
}
