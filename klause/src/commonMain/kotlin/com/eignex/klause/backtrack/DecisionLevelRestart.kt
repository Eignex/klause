package com.eignex.klause.backtrack

// Restart when a recent window of conflict decision levels runs higher than the long-run average: the search
// dives deep before conflicting, a sign the region is unproductive. Complements GlucoseRestart's LBD signal.
// Restart when `globalAvg < ratio · recentAvg`, only once the window is full; the window is cleared on restart,
// so it must refill before it can trigger again.
internal class DecisionLevelRestart(private val window: Int = 50, private val ratio: Double = 1.0) {
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

    fun recordConflict(decisionLevel: Int): Boolean {
        conflicts++
        globalSum += decisionLevel
        push(decisionLevel)
        if (count < window) return false
        val recentAvg = windowSum.toDouble() / window
        val globalAvg = globalSum.toDouble() / conflicts
        return globalAvg < ratio * recentAvg
    }

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
