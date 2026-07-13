package com.eignex.klause.backtrack

/**
 * Decision-level moving-average restart (CP-SAT `DL_MOVING_AVERAGE_RESTART`): restart when a recent
 * window of conflict decision levels runs higher than the long-run average — the search is diving deep
 * before conflicting, a sign the current region is unproductive and it should re-pick. The complement of
 * [GlucoseRestart]'s LBD signal: LBD measures learned-clause quality, decision level measures how deep
 * the search drove to hit the conflict. The decision level is the trail depth at the conflict (klause's
 * [DfsEngine] passes the decision-node count).
 *
 *  - [window] — recent-window capacity; the restart check is gated on the window being full, so tiny
 *    searches never restart.
 *  - [ratio] `K` — restart when `globalAvg < K · recentAvg` (recent decision levels running above the
 *    long-run average). CP-SAT default 1.0.
 *
 * The recent window is a circular buffer, fast-cleared on restart so it must refill before it can
 * trigger again (no restart storms).
 */
internal class DecisionLevelRestart(
    private val window: Int = 50,
    private val ratio: Double = 1.0,
) {
    init {
        require(window > 0) { "window must be positive, got $window" }
        require(ratio > 0.0) { "ratio must be positive, got $ratio" }
    }

    private val buf = IntArray(window)
    private var count = 0
    private var head = 0
    private var windowSum = 0L
    private var globalSum = 0L
    private var conflicts = 0L

    /** Record one conflict at [decisionLevel] and decide whether to restart now. */
    fun recordConflict(decisionLevel: Int): Boolean {
        conflicts++
        globalSum += decisionLevel
        push(decisionLevel)
        if (count < window) return false
        val recentAvg = windowSum.toDouble() / window
        val globalAvg = globalSum.toDouble() / conflicts
        return globalAvg < ratio * recentAvg
    }

    /** Clear the recent window so it must refill before the next restart can fire. */
    fun clearWindow() {
        count = 0
        head = 0
        windowSum = 0L
    }

    private fun push(v: Int) {
        if (count < window) {
            buf[(head + count) % window] = v
            windowSum += v
            count++
        } else {
            windowSum += v - buf[head]
            buf[head] = v
            head = (head + 1) % window
        }
    }
}
