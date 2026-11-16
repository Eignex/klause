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
        fun until(deadline: ComparableTimeMark): Cancellation {
            if (deadline !is TimeSource.Monotonic.ValueTimeMark) {
                return object : Cancellation {
                    override fun isCancelled(): Boolean = deadline.hasPassedNow()
                    override fun deadline(): ComparableTimeMark = deadline
                }
            }
            val remaining = -deadline.elapsedNow()
            return when {
                remaining <= Duration.ZERO -> ConstantDeadline(deadline, true)
                remaining == Duration.INFINITE -> ConstantDeadline(deadline, false)
                else -> MonotonicDeadline(deadline, remaining.inWholeNanoseconds)
            }
        }
    }
}

internal class MonotonicDeadline(private val end: ComparableTimeMark, nanos: Long) : Cancellation {
    private val atNanos = monotonicNanos() + nanos
    override fun deadline(): ComparableTimeMark = end
    override fun isCancelled(): Boolean = monotonicNanos() - atNanos >= 0L
}

private class ConstantDeadline(private val end: ComparableTimeMark, private val stopped: Boolean) : Cancellation {
    override fun deadline(): ComparableTimeMark = end
    override fun isCancelled(): Boolean = stopped
}
