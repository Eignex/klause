package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.cut.CutPool
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.magnitudeBitLength

internal data class LinearAuthorityAggregateRejection(
    val inequality: SourceInequality,
    val owners: Set<Int>,
    val family: CutFamily,
    val activity: Double,
    val normSquared: Double,
    val efficacy: Double,
    val minimumEfficacy: Double = CutPool.MIN_EFFICACY,
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
        val records = retained ?: ArrayList<LinearAuthorityAggregateRejection>(maximumRecords).also { retained = it }
        records += LinearAuthorityAggregateRejection(cut.inequality, cut.owners.toSet(), cut.family,
            activity, normSquared, efficacy)
    }
}
