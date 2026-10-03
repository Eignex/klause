package com.eignex.klause.presolve

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter

/**
 * The presolve phase's remaining work allowance, and the means to hand each pass a slice of it.
 *
 * A single [Cancellation] cannot express this: it is an opaque predicate, so the round engine can ask
 * *whether* the budget is spent but not *how much is left*, and the first expensive pass is free to
 * consume all of it — leaving every pass declared after it to run against an already-fired predicate.
 * [remaining] closes that gap, and [slice] turns it into a per-pass stop.
 *
 * The allowance is counted in deterministic work units that each pass [charge]s where it polls its
 * cancellation, never in elapsed time. A clock-backed budget made what presolve did, and so which route
 * a model took, depend on how loaded the machine was; a charged one gives the same model and flags the
 * same reductions on every run. The tokens [slice] and [orSpent] hand out carry this budget as their
 * [Cancellation.workMeter], so work charged through them lands here without the budget being threaded
 * beside the token.
 *
 * Presolve runs on one thread, and so does every charge.
 */
class PresolveBudget(
    /** Work units the phase may spend in all. */
    val allowance: Long,
) : WorkMeter {
    private var spent = 0L

    /** Work units charged so far; may exceed [allowance] by the last charge before a poll noticed. */
    fun spent(): Long = spent

    /** Work units left in the presolve phase; `0` once it is spent. */
    fun remaining(): Long = (allowance - spent).coerceAtLeast(0L)

    override fun charge(units: Long) {
        if (units <= 0L) return
        spent = if (units > Long.MAX_VALUE - spent) Long.MAX_VALUE else spent + units
    }

    /**
     * A cancellation that fires once [share] units of the currently-remaining budget have been charged, or
     * the whole budget runs out — whichever comes first. A pass that finishes early simply leaves the
     * unspent remainder in the pool, so the next slice is taken from a larger base rather than a fixed
     * quantum.
     */
    fun slice(share: Long): Cancellation {
        if (share <= 0L) return Metered(this) { true }
        val floor = (remaining() - share).coerceAtLeast(0L)
        return Metered(this) { remaining() <= floor }
    }

    /**
     * A cancellation that fires when [outer] does or the whole budget is spent. Work charged through it
     * lands here, whatever meter [outer] carries, and it keeps [outer]'s deadline.
     */
    fun orSpent(outer: Cancellation): Cancellation = Metered(this) { remaining() == 0L } or outer

    private class Metered(private val meter: WorkMeter, private val fired: () -> Boolean) : Cancellation {
        override fun isCancelled(): Boolean = fired()

        override fun workMeter(): WorkMeter = meter
    }
}
