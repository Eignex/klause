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
 * cancellation. Elapsed cancellation supplied by callers can stop a pass before its work allowance
 * is spent. The tokens [slice] and [orSpent] hand out carry this budget as their
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

    /** Round entries across source and finite schedules, including final empty scans. */
    var rounds: Int = 0
        private set

    /** Calls to each scheduled pass, including calls that changed nothing. */
    val passCalls: Map<String, Int> get() = calls.toMap()
    private val calls = HashMap<String, Int>()

    /** Root-bake propagation probes across reseeds and tiers, including repair calls. */
    var probeCalls: Long = 0
        private set

    internal fun recordRound() { rounds++ }

    internal fun recordPass(pass: PresolvePass) {
        calls[pass.id] = (calls[pass.id] ?: 0) + 1
    }

    internal fun recordProbe() { probeCalls++ }

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
