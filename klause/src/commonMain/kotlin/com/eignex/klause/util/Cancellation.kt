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
 * Work-metered tokens expose their [workMeter], so an engine that counts its own deterministic work can
 * [charge] it to whatever budget the token stands for without that budget being threaded beside the
 * token. A plain token has no meter and ignores charges; composites surface either side's meter.
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

    /** The deterministic work budget this token stops on, or `null` when it stops on something else. */
    fun workMeter(): WorkMeter? = null

    /** Charge [units] of deterministic work to this token's [workMeter]; a no-op on a token without one. */
    fun charge(units: Long) {
        workMeter()?.charge(units)
    }

    /** Cancel when either side cancels. Short-circuit on the receiver; the composite reports the earlier
     *  of the two [deadline]s (whichever bound bites first) and whichever side's [workMeter] it finds. */
    infix fun or(other: Cancellation): Cancellation {
        val self = this
        val combined = earlierDeadline(self.deadline(), other.deadline())
        val meter = self.workMeter() ?: other.workMeter()
        return OrCancellation(self, other, combined, meter)
    }

    /** Cancel only when both sides cancel — useful for "user requested AND budget
     *  exhausted" two-key escapes that shouldn't fire on either alone. */
    infix fun and(other: Cancellation): Cancellation {
        val self = this
        val meter = self.workMeter() ?: other.workMeter()
        return object : Cancellation {
            override fun isCancelled(): Boolean = self.isCancelled() && other.isCancelled()
            override fun workMeter(): WorkMeter? = meter
        }
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
        fun until(deadline: ComparableTimeMark): Cancellation = object : ClockOnlyCancellation {
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

private interface ClockOnlyCancellation : Cancellation

// Flatten library-owned composites at construction so hot polls visit ordered predicates without recursive calls.
// The deadline and first work meter remain snapshots taken at composition, including for dynamic adapters.
private class OrCancellation(
    self: Cancellation,
    other: Cancellation,
    private val combined: ComparableTimeMark?,
    private val meter: WorkMeter?,
) : Cancellation {
    private val tokens: Array<Cancellation> = buildList<Cancellation> {
        if (self is OrCancellation) {
            for (token in self.tokens) appendOrdered(token)
        } else if (self !== Cancellation.Never) {
            appendOrdered(self)
        }
        if (other is OrCancellation) {
            for (token in other.tokens) appendOrdered(token)
        } else if (other !== Cancellation.Never) {
            appendOrdered(other)
        }
    }.toTypedArray()

    override fun isCancelled(): Boolean {
        for (token in tokens) if (token.isCancelled()) return true
        return false
    }

    override fun deadline(): ComparableTimeMark? = combined
    override fun workMeter(): WorkMeter? = meter
}

private fun MutableList<Cancellation>.appendOrdered(token: Cancellation) {
    val last = lastOrNull()
    if (last is ClockOnlyCancellation && token is ClockOnlyCancellation) {
        val previous = last.deadline()
        val next = token.deadline()
        if (previous is TimeSource.Monotonic.ValueTimeMark && next is TimeSource.Monotonic.ValueTimeMark) {
            // Adjacent monotonic deadlines have no intervening predicate effects.
            if (next < previous) this[lastIndex] = token
            return
        }
    }
    add(token)
}

/**
 * A token that fires when [cancelled] does and states [deadlineOf] as its deadline, read each time it is asked so a
 * deadline re-armed later stays current. It carries no work meter. An adapter that wraps a token in a predicate builds
 * it from this rather than a bare lambda, which [Cancellation.shorten] would read as having no time limit at all.
 */
internal fun cancelledWhen(deadlineOf: () -> ComparableTimeMark?, cancelled: () -> Boolean): Cancellation =
    object : Cancellation {
        override fun isCancelled(): Boolean = cancelled()

        override fun deadline(): ComparableTimeMark? = deadlineOf()
    }
