package com.eignex.klause.localsearch.schedule

import kotlin.math.ln

/**
 * Calibrates the start temperature of an annealing [Schedule] from warm-up move-cost deltas so an average
 * worsening move is accepted with a target probability under the Metropolis rule:
 * `T0 = -meanPositiveDelta / ln(targetAcceptance)`. A warm start scales T0 down so a full-hot start does not
 * scramble the seeded incumbent.
 */
object StartTemperature {
    /**
     * @param deltas candidate move-cost deltas (new − incumbent) sampled during warm-up; only the
     *   strictly-positive (worsening) entries inform the calibration
     * @param targetAcceptance desired acceptance probability for an average worsening move, in
     *   `(0, 1)` — higher = hotter start
     * @param warmStart whether the run is seeded from an existing incumbent
     * @param warmStartFactor scale applied to T0 when [warmStart] is true, in `(0, 1]`
     * @param minTemperature floor returned when no worsening deltas were sampled, and a lower bound on
     *   the result
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
        val t0 = -meanPositive / ln(targetAcceptance)
        val scaled = if (warmStart) t0 * warmStartFactor else t0
        return scaled.coerceAtLeast(minTemperature)
    }
}
