package com.eignex.klause.localsearch

import kotlin.random.Random

/**
 * A frequency-restoring sampler over move sizes — how many variables a structural move should touch.
 * Sizes `minSize, minSize+1, …` are drawn in proportion to [weights], but *without replacement within
 * a cycle*: each size is handed out its weighted share before any size repeats, so a short run of
 * draws matches the target mix instead of drifting the way independent weighted draws can. When the
 * cycle empties it refills.
 *
 * Shared, engine-side, and deterministic — all randomness comes from the caller's [Random]. A source
 * that wants a tunable move-size mix (rather than a fixed count) draws from one of these.
 */
class MoveSizeDistribution(
    /** Weight of each size, starting at [minSize]; `weights[k]` is the share of size `minSize + k`. */
    private val weights: IntArray,
    /** The size that `weights[0]` refers to. */
    private val minSize: Int = 1,
) {
    init {
        require(weights.isNotEmpty()) { "weights must be non-empty" }
        require(weights.all { it >= 0 }) { "weights must be non-negative" }
        require(weights.any { it > 0 }) { "at least one weight must be positive" }
        require(minSize >= 1) { "minSize >= 1, got $minSize" }
    }

    private val total: Int = weights.sum()
    private val residual: IntArray = weights.copyOf()
    private var remaining: Int = total

    /** The next move size, consuming one slot of the current cycle (refilling when the cycle empties).
     *  Consumes one [Random] draw. */
    fun nextSize(rng: Random): Int {
        if (remaining == 0) {
            weights.copyInto(residual)
            remaining = total
        }
        var r = rng.nextInt(remaining)
        for (k in residual.indices) {
            if (r < residual[k]) {
                residual[k]--
                remaining--
                return minSize + k
            }
            r -= residual[k]
        }
        error("unreachable: r < remaining always lands in a bucket")
    }
}
