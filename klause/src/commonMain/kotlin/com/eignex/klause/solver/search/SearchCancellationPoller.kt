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

    /**
     * Poll on a fixed cadence instead of a time-adaptive one.
     *
     * A search can only stop where it polls. While the cadence is tuned by elapsed time, *where* a
     * counted budget stops the search is a function of machine speed — so two identical invocations
     * pause at different nodes and every counter downstream of the search inherits that. A caller
     * running the search against a counted budget sets this so the stopping point is a property of the
     * search alone. Off by default: the adaptive cadence is what keeps cheap search from calling its
     * token every step, and that is worth more than reproducibility when nothing is counting.
     */
    var fixedCadence: Boolean = false

    /** True when the caller should inspect its cancellation token. */
    fun due(): Boolean = countdown-- <= 0

    /** Record a non-cancelled poll and schedule the next one. */
    fun rearm() {
        if (fixedCadence) {
            countdown = FIXED_INTERVAL
            return
        }
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

        /** Poll cadence under [fixedCadence]: frequent enough to stop near a counted budget, coarse
         *  enough not to call the token every step. */
        const val FIXED_INTERVAL = 16
        const val TARGET_MILLIS = 5L
    }
}
