package com.eignex.klause.backtrack

import kotlin.time.TimeSource

/**
 * Time-adaptive cadence for polling a cancellation deadline inside a search loop. Checking the
 * deadline on every node is wasteful when nodes are sub-microsecond and too coarse when a node runs
 * ~0.5s of global propagation, so the poll fires every [due]-th call and the gap self-steers toward
 * [CANCEL_CHECK_TARGET_MS] (up to the [CANCEL_CHECK_INTERVAL] ceiling). Termination fires only once
 * the token is set, so this can overshoot `-t` by ~one gap but never undershoots.
 *
 * Usage: `if (poller.due()) { if (token()) …stop…; poller.rearm() }`. Shared by the satisfaction
 * path ([BacktrackSolver] `driveSearch`) and the branch-and-bound engine ([ResumableMinimize]).
 */
internal class DeadlinePoller {
    private var countdown = 0
    private var interval = 1
    private var lastMark: TimeSource.Monotonic.ValueTimeMark? = null

    /** True when a deadline poll is due; decrements the per-node countdown. Starts due on the first call. */
    fun due(): Boolean = countdown-- <= 0

    /**
     * Re-arm after a poll that did not cancel: steer the interval — halve it (down to 1) when the last
     * gap exceeded [CANCEL_CHECK_TARGET_MS], double it (up to [CANCEL_CHECK_INTERVAL]) when polls are
     * cheap — and reset the countdown. Call only when [due] returned true and the token did not fire.
     */
    fun rearm() {
        val now = TimeSource.Monotonic.markNow()
        val prev = lastMark
        lastMark = now
        val elapsedMs = if (prev == null) 0L else (now - prev).inWholeMilliseconds
        if (elapsedMs > CANCEL_CHECK_TARGET_MS) {
            if (interval > 1) interval = maxOf(1, interval / 2)
        } else if (interval < CANCEL_CHECK_INTERVAL) {
            interval = minOf(CANCEL_CHECK_INTERVAL, interval * 2)
        }
        countdown = interval
    }
}
