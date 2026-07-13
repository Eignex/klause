package com.eignex.klause.backtrack

import com.eignex.kumulant.stat.decay.EwmaMeanStat

/**
 * EMA-based adaptive restart: the exponential-moving-average counterpart of [GlucoseRestart]'s bounded
 * LBD/trail windows.
 * Two bias-corrected EWMAs of learned-clause LBD stand in for the recent/global averages: a fast one
 * tracking the last handful of conflicts and a slow one tracking the long run. When the fast average
 * runs hotter than the slow one the recent clauses are low-quality and the search should re-pick. A
 * slow EWMA of the trail size gives the complementary blocking — a trail well above its average means
 * the solver is driving deep toward a model, so the restart is deferred.
 *
 *  - [fastAlpha] / [slowAlpha] — EWMA smoothing factors; the effective window is `~1/alpha` conflicts.
 *    Defaults `2^-5` and `2^-14`.
 *  - [restartMargin] `K` — restart when `recentLbd * K > globalLbd` (recent LBD `~1/K` above the
 *    long-run average). Matches [GlucoseRestart]'s 0.8.
 *  - [blockingFactor] `R` — block the restart when `trailSize > R * globalTrail`. Matches
 *    [GlucoseRestart]'s 1.4.
 *  - [warmup] — conflicts to accumulate before a restart can fire, so a cold average doesn't restart
 *    on the first few conflicts.
 *
 * Unlike [GlucoseRestart], the averages are never cleared on a restart: the EWMA decays a spent run out
 * on its own, so a restart genuinely lowers the recent average rather than resetting the detector.
 */
internal class EmaRestart(
    fastAlpha: Double = 1.0 / 32,
    slowAlpha: Double = 1.0 / 16_384,
    private val restartMargin: Double = 0.8,
    private val blockingFactor: Double = 1.4,
    private val warmup: Int = 50,
) {
    init {
        require(restartMargin > 0.0) { "restartMargin must be positive, got $restartMargin" }
        require(blockingFactor > 0.0) { "blockingFactor must be positive, got $blockingFactor" }
        require(warmup >= 0) { "warmup must be non-negative, got $warmup" }
    }

    private val recentLbd = EwmaMeanStat(alpha = fastAlpha)
    private val globalLbd = EwmaMeanStat(alpha = slowAlpha)
    private val globalTrail = EwmaMeanStat(alpha = slowAlpha)
    private var conflicts = 0L

    /**
     * Record one learned conflict and decide whether to restart now. [lbd] is the learned clause's
     * literal-block distance; [trailSize] is the search depth at the conflict. Returns true iff the
     * engine should pop to root and restart.
     */
    fun recordConflict(lbd: Int, trailSize: Int): Boolean {
        conflicts++
        recentLbd.update(lbd.toDouble(), timestampNanos = 0L, weight = 1.0)
        globalLbd.update(lbd.toDouble(), timestampNanos = 0L, weight = 1.0)
        globalTrail.update(trailSize.toDouble(), timestampNanos = 0L, weight = 1.0)
        if (conflicts < warmup) return false

        // Blocking: a trail far above its average means the solver is driving deep toward a model —
        // defer the restart.
        if (trailSize > blockingFactor * globalTrail.read(0L).mean) return false

        // Restart when the recent LBD average runs hotter than the long-run average.
        return recentLbd.read(0L).mean * restartMargin > globalLbd.read(0L).mean
    }
}
