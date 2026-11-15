package com.eignex.klause.util

import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/** A cooperative stop predicate, independent of work accounting and admission. */
fun interface Cancellation {
    /** True when the caller requests execution to stop. */
    fun isCancelled(): Boolean

    /** Evaluate this stop predicate. */
    operator fun invoke(): Boolean = isCancelled()

    /** The current deadline, when this predicate has one. */
    fun deadline(): ComparableTimeMark? = null

    /** Stop when either predicate stops, preserving predicate order. */
    infix fun or(other: Cancellation): Cancellation = object : Cancellation {
        override fun isCancelled(): Boolean = this@Cancellation() || other()
        override fun deadline(): ComparableTimeMark? = listOfNotNull(
            this@Cancellation.deadline(),
            other.deadline(),
        ).minOrNull()
    }

    /** Stop when both predicates stop. */
    infix fun and(other: Cancellation): Cancellation = Cancellation { this() && other() }

    /** Standard stop predicates. */
    companion object {
        /** A predicate that never requests a stop. */
        val Never: Cancellation = Cancellation { false }

        /** Stop after [duration]. */
        fun after(duration: Duration): Cancellation = until(TimeSource.Monotonic.markNow() + duration)

        /** Stop at [deadline]. */
        fun until(deadline: ComparableTimeMark): Cancellation = if (deadline is TimeSource.Monotonic.ValueTimeMark) {
            MonotonicDeadline(deadline)
        } else {
            object : Cancellation {
                override fun isCancelled(): Boolean = deadline.hasPassedNow()
                override fun deadline(): ComparableTimeMark = deadline
            }
        }
    }
}

private class MonotonicDeadline(private val end: ComparableTimeMark) : Cancellation {
    private val remaining = -end.elapsedNow()
    private val started = monotonicNanos()
    private val nanos = remaining.inWholeNanoseconds
    private val expired = remaining <= Duration.ZERO
    private val unbounded = remaining == Duration.INFINITE
    override fun deadline(): ComparableTimeMark = end
    override fun isCancelled(): Boolean = expired || (!unbounded && monotonicNanos() - started >= nanos)
}
