package com.eignex.klause.presolve

import com.eignex.klause.solver.Cancellation

/**
 * The presolve phase's remaining wall-clock allowance, and the means to hand each pass a slice of it.
 *
 * A single [Cancellation] cannot express this: it is an opaque predicate, so the round engine can ask
 * *whether* the budget is spent but not *how much is left*, and the first expensive pass is free to
 * consume all of it — leaving every pass declared after it to run against an already-fired predicate.
 * [remaining] closes that gap, and [slice] turns it into a per-pass deadline.
 *
 * Deliberately clock-free: a slice is defined as "the point at which [remaining] has fallen by my
 * share", so this stays in common code with no platform time source of its own.
 */
class PresolveBudget(private val remaining: () -> Long) {

    /** Milliseconds left in the presolve phase; `0` once it is spent. */
    fun remaining(): Long = remaining.invoke().coerceAtLeast(0L)

    /**
     * A cancellation that fires once [shareMs] of the currently-remaining budget has been spent, or the
     * whole budget runs out — whichever comes first. A pass that finishes early simply leaves the unspent
     * remainder in the pool, so the next slice is taken from a larger base rather than a fixed quantum.
     */
    fun slice(shareMs: Long): Cancellation {
        if (shareMs <= 0L) return Cancellation { true }
        val floor = (remaining() - shareMs).coerceAtLeast(0L)
        return Cancellation { remaining() <= floor }
    }
}
