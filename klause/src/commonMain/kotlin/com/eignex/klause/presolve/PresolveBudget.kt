package com.eignex.klause.presolve

import com.eignex.klause.propagation.RootWorkMeter
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter

private const val ROOT_CONSTRUCTION_WEIGHT = 53L
private const val ROOT_PROPAGATION_WEIGHT = 53L

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
 * is spent. The tokens [slice] and [orSpent] hand out carry this budget's native-work adapter as their
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
    private val nativeMeter = PresolveRootWorkMeter(this)

    /** Model entries used to construct root and probe propagation states across rebuilds. */
    var rootConstructionEntries: Long = 0L
        private set

    /** Native propagation work across root bakes, probes and repairs. */
    var rootPropagationWork: Long = 0L
        private set

    internal fun recordConstruction(entries: Long) {
        rootConstructionEntries = saturatedSum(rootConstructionEntries, entries)
        charge(weighted(entries, ROOT_CONSTRUCTION_WEIGHT))
    }

    internal fun recordPropagation(units: Long) {
        rootPropagationWork = saturatedSum(rootPropagationWork, units)
        charge(weighted(units, ROOT_PROPAGATION_WEIGHT))
    }

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
        spent = saturatedSum(spent, units)
    }

    /**
     * A cancellation that fires once [share] units of the currently-remaining budget have been charged, or
     * the whole budget runs out — whichever comes first. A pass that finishes early simply leaves the
     * unspent remainder in the pool, so the next slice is taken from a larger base rather than a fixed
     * quantum.
     */
    fun slice(share: Long): Cancellation {
        if (share <= 0L) return Metered(nativeMeter) { true }
        val floor = (remaining() - share).coerceAtLeast(0L)
        return Metered(nativeMeter) { remaining() <= floor }
    }

    /**
     * A cancellation that fires when [outer] does or the whole budget is spent. Work charged through it
     * lands here, whatever meter [outer] carries, and it keeps [outer]'s deadline.
     */
    fun orSpent(outer: Cancellation): Cancellation = Metered(nativeMeter) { remaining() == 0L } or outer

    private class Metered(private val meter: WorkMeter, private val fired: () -> Boolean) : Cancellation {
        override fun isCancelled(): Boolean = fired()

        override fun workMeter(): WorkMeter = meter
    }
}

private class PresolveRootWorkMeter(val budget: PresolveBudget) : RootWorkMeter {
    override fun charge(units: Long) = budget.charge(units)
    override fun construction(entries: Long) = budget.recordConstruction(entries)
    override fun propagation(units: Long) = budget.recordPropagation(units)
}

internal fun Cancellation.recordPresolveProbe() {
    (workMeter() as? PresolveRootWorkMeter)?.budget?.recordProbe()
}

private fun saturatedSum(total: Long, units: Long): Long =
    if (units <= 0L) total else if (units > Long.MAX_VALUE - total) Long.MAX_VALUE else total + units

private fun weighted(units: Long, weight: Long): Long =
    if (units > Long.MAX_VALUE / weight) Long.MAX_VALUE else units * weight
