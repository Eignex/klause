package com.eignex.klause.util

import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Caller-supplied cooperative-cancellation token. Engines call it periodically and stop
 * their search promptly when [isCancelled] returns `true`. The default token is
 * [Cancellation.Never].
 *
 * The interface is a `fun interface` so a bare `() -> Boolean` lambda SAM-converts to a
 * [Cancellation], and `params.cancellation()` reads naturally (the [invoke] operator
 * forwards to [isCancelled]).
 *
 * Compose tokens with [or] / [and]:
 * ```
 * val flag = AtomicBoolean(false)
 * val token = Cancellation { flag.get() } or Cancellation.after(5.seconds)
 * solver.solve(params.copy(cancellation = token))
 * ```
 *
 * Bridge to coroutine cancellation in one line:
 * ```
 * solver.solve(params.copy(cancellation = Cancellation { !coroutineContext.isActive }))
 * ```
 *
 * Deadline-backed tokens ([after] / [until]) also expose their [deadline], so time-relative budgets can
 * be *computed* from the token itself rather than threaded alongside it — see [shorten]. Composites
 * ([or] / [and]) surface the earliest deadline of their sides.
 *
 * Cancellation is **cooperative**: engines check it between work units (per flip in
 * local search, per decision-block in backtrack). A request to cancel is observed within
 * a few hundred operations, not instantly.
 *
 * Cancelled `solve` returns `SolveResult.Unknown(TerminationReason.Cancelled)`. Cancelled
 * `samples` / `enumerate` sequences stop yielding (the consumer sees an early end of
 * stream).
 */
fun interface Cancellation {
    /** True iff cancellation has been requested. */
    fun isCancelled(): Boolean

    /** Makes the token callable as `cancellation()` — natural shorthand for a
     *  predicate-shaped value. Forwards to [isCancelled]. */
    operator fun invoke(): Boolean = isCancelled()

    /** The wall-clock instant this token fires at when it is deadline-backed ([after] / [until]); `null`
     *  for a plain predicate (including [Never]). Lets a token's remaining budget be read without a
     *  separate numeric channel, so budgets like [shorten] can be derived from the token itself. */
    fun deadline(): ComparableTimeMark? = null

    /** Cancel when either side cancels. Short-circuit on the receiver; the composite reports the earlier
     *  of the two [deadline]s (whichever bound bites first). */
    infix fun or(other: Cancellation): Cancellation {
        val self = this
        val combined = earlierDeadline(self.deadline(), other.deadline())
        return object : Cancellation {
            override fun isCancelled(): Boolean = self.isCancelled() || other.isCancelled()
            override fun deadline(): ComparableTimeMark? = combined
        }
    }

    /** Cancel only when both sides cancel — useful for "user requested AND budget
     *  exhausted" two-key escapes that shouldn't fire on either alone. */
    infix fun and(other: Cancellation): Cancellation {
        val self = this
        return Cancellation { self.isCancelled() && other.isCancelled() }
    }

    /**
     * A token that fires after [fraction] of the time remaining to this token's [deadline] — for giving a
     * phase a slice of the overall budget — and also when this token itself fires (so an external cancel
     * still stops it). Returns this token **unchanged** when it carries no [deadline] (nothing to compute
     * against): the caller pairs it with a work-based safeguard for that case. [fraction] in `[0, 1]`.
     */
    fun shorten(fraction: Double): Cancellation {
        require(fraction in 0.0..1.0) { "fraction must be in [0, 1], got $fraction" }
        val deadline = deadline() ?: return this
        val now = TimeSource.Monotonic.markNow()
        val remaining = deadline - now
        if (remaining <= Duration.ZERO) return this
        return until(now + remaining * fraction) or this
    }

    /** Standard [Cancellation] tokens. */
    companion object {
        /** Default token: never cancels. */
        val Never: Cancellation = Cancellation { false }

        /**
         * Cancel once [deadline] has passed. Deadline-backed (exposes [Cancellation.deadline]), so it
         * composes with [shorten]. Backed by [TimeSource.Monotonic] via the caller's mark.
         */
        fun until(deadline: ComparableTimeMark): Cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = deadline.hasPassedNow()
            override fun deadline(): ComparableTimeMark = deadline
        }

        /**
         * Cancel after [duration] has elapsed from this call. Backed by KMP-safe
         * [TimeSource.Monotonic], so it works on both JVM and Native without
         * a clock-source per platform. The deadline is captured eagerly here; calling
         * this twice yields two independent tokens.
         */
        fun after(duration: Duration): Cancellation = until(TimeSource.Monotonic.markNow() + duration)

        private fun earlierDeadline(a: ComparableTimeMark?, b: ComparableTimeMark?): ComparableTimeMark? = when {
            a == null -> b
            b == null -> a
            else -> minOf(a, b)
        }
    }
}
