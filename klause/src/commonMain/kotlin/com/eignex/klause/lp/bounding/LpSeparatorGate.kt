package com.eignex.klause.lp.bounding

// Per-family analogue of LpEffortLadder: a separator family that returns no violated cut over a whole
// window is disabled, then re-probed on exponential backoff and re-enabled by a productive round. Skipping
// a separator loses tightening, never a solution. State is indexed by position in the separator list.
internal class LpSeparatorGate(
    count: Int,
    private val warmup: Int = DEFAULT_WARMUP,
    private val window: Int = DEFAULT_WINDOW,
    private val reprobeBase: Int = LpEffortLadder.DEFAULT_REPROBE_BASE,
    private val reprobeMax: Int = DEFAULT_REPROBE_MAX,
) {
    companion object {
        const val DEFAULT_WARMUP: Int = 64
        const val DEFAULT_WINDOW: Int = 64
        const val DEFAULT_REPROBE_MAX: Int = 8192
    }

    private val enabled = BooleanArray(count) { true }
    private val totalRounds = IntArray(count)
    private val windowRounds = IntArray(count)
    private val windowProductive = IntArray(count)
    private val sinceProbe = IntArray(count)
    private val reprobeDelay = IntArray(count) { reprobeBase }
    private val probing = BooleanArray(count)

    // Advances a disabled family's re-probe clock, so call exactly once per round.
    fun shouldRun(i: Int): Boolean {
        if (enabled[i]) {
            probing[i] = false
            return true
        }
        if (reprobeDelay[i] == Int.MAX_VALUE) return false
        if (sinceProbe[i] < Int.MAX_VALUE) sinceProbe[i]++
        if (sinceProbe[i] >= reprobeDelay[i]) {
            probing[i] = true
            return true
        }
        return false
    }

    fun record(i: Int, productive: Boolean) {
        totalRounds[i]++
        if (probing[i]) {
            probing[i] = false
            sinceProbe[i] = 0
            if (productive) {
                enabled[i] = true
                windowRounds[i] = 0
                windowProductive[i] = 0
                reprobeDelay[i] = reprobeBase
            } else {
                val current = reprobeDelay[i]
                reprobeDelay[i] = if (current >= reprobeMax || current > reprobeMax / 2) {
                    reprobeMax
                } else {
                    current * 2
                }
            }
            return
        }
        windowRounds[i]++
        if (productive) windowProductive[i]++
        if (totalRounds[i] >= warmup && windowRounds[i] >= window) {
            if (windowProductive[i] == 0) {
                enabled[i] = false
                sinceProbe[i] = 0
                reprobeDelay[i] = reprobeBase
            }
            windowRounds[i] = 0
            windowProductive[i] = 0
        }
    }

    // Excludes the transient re-probe round.
    fun isEnabled(i: Int): Boolean = enabled[i]
}
