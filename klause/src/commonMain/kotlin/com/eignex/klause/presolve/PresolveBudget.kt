package com.eignex.klause.presolve

import com.eignex.klause.ir.Factor
import com.eignex.klause.propagation.RootWorkMeter
import com.eignex.klause.solver.result.PresolveInvocationStats
import com.eignex.klause.solver.result.PresolvePreparationStats
import com.eignex.klause.util.ArithmeticWorkSource
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.WorkReservation
import com.eignex.klause.util.withQuota
import com.eignex.util.collections.IntHashSet

private const val ROOT_CONSTRUCTION_WEIGHT = 53L
private const val ROOT_PROPAGATION_WEIGHT = 53L

/**
 * The presolve phase's remaining work allowance, and the means to hand each pass a slice of it.
 *
 * The round engine uses [remaining] to select a per-pass allowance. [slice] binds
 * that allowance's stop, work sink and serial admission to an [ExecutionControl].
 *
 * The allowance is counted in deterministic work units that each pass [charge]s where it polls its
 * cancellation. Elapsed cancellation supplied by callers can stop a pass before its work allowance
 * is spent. The tokens [slice] and [orSpent] hand out carry this budget's native-work adapter as their
 * [ExecutionControl.accounting], so work charged through them lands here without the budget being threaded
 * beside the token.
 *
 * Presolve runs on one thread, and so does every charge.
 */
class PresolveBudget(
    /** Work units the phase may spend in all. */
    val allowance: Long,
) : WorkMeter {
    private var spent = 0L
    private var reserved = 0L
    internal var activeInvocation: PresolveInvocation? = null
    private val observations = ArrayList<PresolveInvocationStats>()
    private val preparation = ArrayList<PresolvePreparationStats>()
    private var entryFactors: Array<Factor>? = null
    private var cachedEntryUnits = 0L
    internal var entryBooleanPins = IntHashSet()
        private set

    /** Invocation observations in completion order, including nested construction stages. */
    val invocations: List<PresolveInvocationStats> get() = observations.toList()

    /** Preparation operations outside the elective pass catalogue. */
    val preparationStages: List<PresolvePreparationStats> get() = preparation.toList()

    /** Factors visited solely to compute aggregate entry size, across model revisions. */
    var entrySizeVisits: Long = 0L
        private set

    internal fun entryUnits(factors: Array<Factor>): Long {
        if (entryFactors !== factors) {
            entrySizeVisits = saturatedSum(entrySizeVisits, factors.size.toLong())
            entryBooleanPins = IntHashSet()
            cachedEntryUnits = passBaseUnits(factors, entryBooleanPins)
            entryFactors = factors
            charge(factors.size.toLong())
        }
        return cachedEntryUnits
    }

    internal fun recordInvocation(observation: PresolveInvocationStats) {
        observations.add(observation)
    }

    internal val lastInvocation: PresolveInvocationStats? get() = observations.lastOrNull()

    internal fun recordPreparation(observation: PresolvePreparationStats) {
        preparation.add(observation)
    }

    internal fun preparationCancellation(outer: ExecutionControl): ExecutionControl {
        val meter = outer.accounting
        if (meter === nativeMeter || (meter is PreparationWorkMeter && meter.budget === this)) {
            return observeStops(outer)
        }
        val recording = PreparationWorkMeter(this, meter)
        return observeStops(object : ExecutionControl(outer) {
            override fun isCancelled(): Boolean = outer()
            override fun deadline() = outer.deadline()
            override val accounting: WorkMeter? get() = if (activeInvocation != null) recording else outer.accounting
        })
    }

    internal fun recordPreparationWork(units: Long) {
        activeInvocation?.let { it.nativeWork = saturatedSum(it.nativeWork, units) }
    }

    private val nativeMeter = PresolveRootWorkMeter(this)

    /** Model entries used to construct root and probe propagation states across rebuilds. */
    var rootConstructionEntries: Long = 0L
        private set

    /** Native propagation work across root bakes, probes and repairs. */
    var rootPropagationWork: Long = 0L
        private set

    internal fun recordConstruction(entries: Long) {
        rootConstructionEntries = saturatedSum(rootConstructionEntries, entries)
        val units = weighted(entries, ROOT_CONSTRUCTION_WEIGHT)
        activeInvocation?.let { it.rebuildWork = saturatedSum(it.rebuildWork, minOf(units, Long.MAX_VALUE - spent)) }
        charge(units)
    }

    internal fun recordPropagation(units: Long) {
        rootPropagationWork = saturatedSum(rootPropagationWork, units)
        val work = weighted(units, ROOT_PROPAGATION_WEIGHT)
        activeInvocation?.let { it.rebuildWork = saturatedSum(it.rebuildWork, minOf(work, Long.MAX_VALUE - spent)) }
        charge(work)
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

    internal fun recordRound() {
        rounds++
    }

    internal fun recordPass(pass: PresolvePass) {
        calls[pass.id] = (calls[pass.id] ?: 0) + 1
    }

    internal fun recordProbe() {
        probeCalls++
    }

    /** Work units charged so far; may exceed [allowance] by the last charge before a poll noticed. */
    fun spent(): Long = spent

    /** Work units left in the presolve phase; `0` once it is spent. */
    fun remaining(): Long = (allowance - spent).coerceAtLeast(0L)

    override fun charge(units: Long) {
        if (units <= 0L) return
        val charged = minOf(units, Long.MAX_VALUE - spent)
        spent += charged
        activeInvocation?.let { it.work = saturatedSum(it.work, charged) }
    }

    /**
     * A cancellation that fires once [share] units of the currently-remaining budget have been charged, or
     * the whole budget runs out — whichever comes first. A pass that finishes early simply leaves the
     * unspent remainder in the pool, so the next slice is taken from a larger base rather than a fixed
     * quantum.
     */
    fun slice(share: Long): ExecutionControl {
        if (share <= 0L) return observeStops(Metered(remaining()))
        val floor = (remaining() - share).coerceAtLeast(0L)
        return observeStops(Metered(floor))
    }

    /**
     * A cancellation that fires when [outer] does or the whole budget is spent. Work charged through it
     * lands here, whatever meter [outer] carries, and it keeps [outer]'s deadline.
     */
    fun orSpent(outer: ExecutionControl): ExecutionControl {
        if (outer is StopObserver && outer.budget === this &&
            (outer.token as? Metered)?.budget === this
        ) return outer
        return observeStops(Metered(0L, outer))
    }

    internal fun observeStops(token: ExecutionControl): ExecutionControl =
        if (token is StopObserver && token.budget === this) token else StopObserver(this, token)

    private class StopObserver(val budget: PresolveBudget, val token: ExecutionControl) : ExecutionControl(token) {
        override val containingStop get() = token
        override fun isCancelled(): Boolean = token().also { stopped ->
            if (stopped) budget.activeInvocation?.interrupted = true
        }

        override fun deadline() = token.deadline()
        override val accounting get() = token.accounting
        override val admission get() = admissionFrom(token)
    }

    private inner class Metered(private val floor: Long, private val outer: ExecutionControl = ExecutionControl.Never) :
        ExecutionControl(outer) {
        val budget get() = this@PresolveBudget
        override val containingStop get() = outer
        override fun isCancelled(): Boolean = remaining() <= floor || outer()
        override fun deadline() = outer.deadline()

        override val accounting: WorkMeter get() = nativeMeter
        private val source: ArithmeticWorkSource = object : ArithmeticWorkSource {
            override val quotaOwner: Any get() = this@PresolveBudget
            override val quotaRemaining get() = (remaining() - floor).coerceAtLeast(0L)
            override val quotaAvailable get() = (quotaRemaining - reserved).coerceAtLeast(0L)
            override val exhausted get() = remaining() <= floor
            override fun reserve(maximum: Int): WorkReservation? {
                require(maximum > 0)
                val available = quotaAvailable
                val granted = minOf(maximum.toLong(), available).toInt()
                if (granted == 0) return null
                reserved += granted
                return WorkReservation(granted) { used ->
                    reserved -= granted
                    charge(used.toLong())
                }
            }
        }
        override val admission: ArithmeticWorkSource get() {
            val containing = outer.admission
            return if (containing?.quotaOwner === this@PresolveBudget) source.withQuota(containing) else source
        }
    }
}

private class PresolveRootWorkMeter(val budget: PresolveBudget) : RootWorkMeter {
    override fun charge(units: Long) = budget.charge(units)
    override fun construction(entries: Long) = budget.recordConstruction(entries)
    override fun propagation(units: Long) = budget.recordPropagation(units)
}

private class PreparationWorkMeter(val budget: PresolveBudget, private val outer: WorkMeter?) : RootWorkMeter {
    override fun charge(units: Long) {
        budget.recordPreparationWork(units)
        outer?.charge(units)
    }

    override fun construction(entries: Long) {
        budget.recordPreparationWork(entries)
        (outer as? RootWorkMeter)?.construction(entries)
    }

    override fun propagation(units: Long) {
        budget.recordPreparationWork(units)
        (outer as? RootWorkMeter)?.propagation(units)
    }
}

internal fun ExecutionControl.recordPresolveProbe() {
    presolveBudget()?.recordProbe()
}

private fun saturatedSum(total: Long, units: Long): Long = if (units <= 0L) {
    total
} else if (units > Long.MAX_VALUE - total) {
    Long.MAX_VALUE
} else {
    total + units
}

private fun weighted(units: Long, weight: Long): Long =
    if (units > Long.MAX_VALUE / weight) Long.MAX_VALUE else units * weight

internal fun ExecutionControl.presolveBudget(): PresolveBudget? = when (val meter = accounting) {
    is PresolveRootWorkMeter -> meter.budget
    is PreparationWorkMeter -> meter.budget
    else -> null
}
