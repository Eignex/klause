package com.eignex.klause.solver

import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Caller-supplied cooperative-cancellation token. Engines call it periodically and stop
 * their search promptly when [isCancelled] returns `true`. The default token is
 * [Cancellation.Never].
 *
 * The interface is a `fun interface` so a bare `() -> Boolean` lambda still SAM-converts
 * to a [Cancellation], and `params.cancellation()` keeps working at every legacy call
 * site (the [invoke] operator forwards to [isCancelled]).
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
 * Cancellation is **cooperative**: engines check it between work units (per flip in
 * local search, per decision-block in backtrack). A request to cancel is observed within
 * a few hundred operations, not instantly.
 *
 * Cancelled `solve` returns `SolveResult.Unknown(TerminationReason.Cancelled)`. Cancelled
 * `samples` / `enumerate` sequences stop yielding (the consumer sees an early end of
 * stream).
 */
fun interface Cancellation {
    fun isCancelled(): Boolean

    /** Preserved for backward-compat: every legacy site invokes the token via
     *  `cancellation()`. Forwards to [isCancelled]. */
    operator fun invoke(): Boolean = isCancelled()

    /** Cancel when either side cancels. Short-circuit on the receiver. */
    infix fun or(other: Cancellation): Cancellation =
        Cancellation { this.isCancelled() || other.isCancelled() }

    /** Cancel only when both sides cancel — useful for "user requested AND budget
     *  exhausted" two-key escapes that shouldn't fire on either alone. */
    infix fun and(other: Cancellation): Cancellation =
        Cancellation { this.isCancelled() && other.isCancelled() }

    companion object {
        /** Default token: never cancels. */
        val Never: Cancellation = Cancellation { false }

        /**
         * Cancel after [duration] has elapsed from this call. Backed by KMP-safe
         * [TimeSource.Monotonic], so it works on JVM, JS, Native, and Wasm without
         * a clock-source per platform. The deadline is captured eagerly here; calling
         * this twice yields two independent tokens.
         */
        fun after(duration: Duration): Cancellation {
            val deadline = TimeSource.Monotonic.markNow() + duration
            return Cancellation { deadline.hasPassedNow() }
        }
    }
}

