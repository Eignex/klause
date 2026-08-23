package com.eignex.klause.solver.search

import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

/**
 * Time-adaptive cancellation cadence for a resumable shared traversal.
 *
 * The first poll is immediate. Subsequent polls grow toward a small wall-clock window, keeping cheap
 * search from repeatedly calling a token while still observing a deadline promptly around expensive
 * propagation or theory checks.
 */
class SearchCancellationPoller(private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic) {
    private var countdown = 0
    private var interval = 1
    private var lastMark: ComparableTimeMark? = null

    /** True when the caller should inspect its cancellation token. */
    fun due(): Boolean = countdown-- <= 0

    /** Record a non-cancelled poll and schedule the next one. */
    fun rearm() {
        val now = timeSource.markNow()
        val previous = lastMark
        lastMark = now
        val elapsedMillis = if (previous == null) 0L else (now - previous).inWholeMilliseconds
        interval = when {
            elapsedMillis > TARGET_MILLIS -> maxOf(1, interval / 2)
            interval < MAX_INTERVAL -> minOf(MAX_INTERVAL, interval * 2)
            else -> interval
        }
        countdown = interval
    }

    /** Start a fresh traversal cadence without retaining a previous slice's timing. */
    fun reset() {
        countdown = 0
        interval = 1
        lastMark = null
    }

    private companion object {
        const val MAX_INTERVAL = 256
        const val TARGET_MILLIS = 5L
    }
}
