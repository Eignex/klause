package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.cut.CutPool
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.magnitudeBitLength

internal enum class LinearAuthorityAggregatePhase {
    Aggregation, ProofCombination, BoundRounding, PointScreen, Continuation, IntegerHalf;

    val workEvent = "AggregateCost${name}Work"
    val allocationEvent = "AggregateCost${name}Allocation"
    val spansEvent = "AggregateCost${name}Spans"
}

internal data class LinearAuthorityAggregatePhaseCost(val work: Long, val allocation: Long, val spans: Long)

internal data class LinearAuthorityAggregateCostPrefix(
    val attempt: Long,
    val phases: Map<LinearAuthorityAggregatePhase, LinearAuthorityAggregatePhaseCost>,
)

internal data class LinearAuthorityAggregateRejection(
    val inequality: SourceInequality,
    val owners: Set<Int>,
    val family: CutFamily,
    val activity: Double,
    val normSquared: Double,
    val efficacy: Double,
    val minimumEfficacy: Double = CutPool.MIN_EFFICACY,
    val cost: LinearAuthorityAggregateCostPrefix? = null,
)

internal class LinearAuthorityAggregateRejections(
    private val budget: SourceLpBudget,
    private val stop: Cancellation,
    private val maximumRecords: Int = 16,
    private val maximumWork: Long = Long.MAX_VALUE,
    private val maximumAllocation: Long = Long.MAX_VALUE,
) {
    private var retained: MutableList<LinearAuthorityAggregateRejection>? = null
    val records: List<LinearAuthorityAggregateRejection> get() = retained.orEmpty()
    var attempted: Long = 0L
        private set
    var limited: Boolean = false
        private set
    var work: Long = 0L
        private set
    var allocation: Long = 0L
        private set
    private val eventCounts = linkedMapOf<String, Long>()
    val events: Map<String, Long> get() = eventCounts.toMap()
    private var phaseStarts: LongArray? = null
    private var attempt = 0L

    init {
        require(maximumRecords in 0..16)
        require(maximumWork >= 0 && maximumAllocation >= 0)
    }

    private fun charge(work: Long, allocation: Long): Boolean {
        val funding = checkNotNull(budget.funding)
        if (stop() || work > funding.availableWork || allocation > funding.remainingAllocation ||
            work > maximumWork - this.work || allocation > maximumAllocation - this.allocation) return false
        budget.observe(preparation = work)
        funding.charge(0L, allocation)
        this.work += work
        this.allocation += allocation
        return !stop() && !funding.exhausted
    }

    fun observe(name: String, amount: Long) {
        require(amount >= 0)
        if (limited) return
        val extra = name !in eventCounts
        if ((extra && eventCounts.size >= 64) ||
            !charge(1L, if (extra) 96L + name.length * 2L else 0L)) {
            limited = true
            return
        }
        val previous = eventCounts[name] ?: 0L
        eventCounts[name] = previous + minOf(amount, Long.MAX_VALUE - previous)
        if (name == "AggregateCostAttempt") beginAttempt()
    }

    private fun beginAttempt() {
        val entries = LinearAuthorityAggregatePhase.entries
        if (!charge(entries.size * 3L, if (phaseStarts == null) 64L + entries.size * 24L else 0L)) {
            limited = true
            return
        }
        val starts = phaseStarts ?: LongArray(entries.size * 3).also { phaseStarts = it }
        for (phase in entries) {
            val index = phase.ordinal * 3
            starts[index] = eventCounts[phase.workEvent] ?: 0L
            starts[index + 1] = eventCounts[phase.allocationEvent] ?: 0L
            starts[index + 2] = eventCounts[phase.spansEvent] ?: 0L
        }
        attempt = eventCounts.getValue("AggregateCostAttempt")
    }

    private fun costPrefix(): LinearAuthorityAggregateCostPrefix? {
        val starts = phaseStarts ?: return null
        val phases = LinearAuthorityAggregatePhase.entries.associateWith { phase ->
            val index = phase.ordinal * 3
            LinearAuthorityAggregatePhaseCost(
                (eventCounts[phase.workEvent] ?: 0L) - starts[index],
                (eventCounts[phase.allocationEvent] ?: 0L) - starts[index + 1],
                (eventCounts[phase.spansEvent] ?: 0L) - starts[index + 2])
        }
        return LinearAuthorityAggregateCostPrefix(attempt, phases)
    }

    fun record(cut: LinearAuthorityCut, activity: Double, normSquared: Double, efficacy: Double) {
        attempted++
        if (!charge(1L, 0L) || limited || records.size >= maximumRecords) {
            limited = true
            return
        }
        val support = cut.inequality.supportSize.toLong()
        if (support > 128L || cut.owners.size > 128) {
            limited = true
            return
        }
        // Immutable inequalities retain no numerical owner or source proof context.
        if (!charge(1L + support * 16L + cut.owners.size, 512L + support * 256L + cut.owners.size * 32L)) {
            limited = true
            return
        }
        fun bounded(value: BigFraction): Boolean =
            value.num.magnitudeBitLength() <= 256 && value.den.magnitudeBitLength() <= 256
        if (!bounded(cut.inequality.rhs) || !cut.inequality.forEachCoefficient { _, value -> bounded(value) }) {
            limited = true
            return
        }
        if (phaseStarts != null && !charge(1L + LinearAuthorityAggregatePhase.entries.size * 3L,
                128L + LinearAuthorityAggregatePhase.entries.size * 128L)) {
            limited = true
            return
        }
        val records = retained ?: ArrayList<LinearAuthorityAggregateRejection>(maximumRecords).also { retained = it }
        records += LinearAuthorityAggregateRejection(cut.inequality, cut.owners.toSet(), cut.family,
            activity, normSquared, efficacy, cost = costPrefix())
    }
}
