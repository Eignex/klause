package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength
import kotlin.time.TimeSource

internal data class JointRepairObservedBound(
    val coordinate: SourceCoordinate,
    val upper: Boolean,
    val value: BigFraction?,
)

internal data class JointRepairObservation(
    val proposal: Long,
    val query: Long,
    val phase: String,
    val disposition: String,
    val operator: Int? = null,
    val schedulingRound: Long? = null,
    val proposalRound: Long? = null,
    val requestedCount: Int? = null,
    val effectiveCount: Int? = null,
    val centerKind: String? = null,
    val centers: Map<Int, BigFraction> = emptyMap(),
    val bools: Map<Int, Boolean> = emptyMap(),
    val lower: Map<Int, BigFraction> = emptyMap(),
    val upper: Map<Int, BigFraction> = emptyMap(),
    val freed: List<Int> = emptyList(),
    val refutationRoots: List<Int> = emptyList(),
    val support: List<JointRepairObservedBound> = emptyList(),
    val cutoff: BigFraction? = null,
    val integerCount: Int? = null,
    val realCount: Int? = null,
    val booleanCount: Int? = null,
    val advance: ExactNeighborhoodAdvanceObservation? = null,
)

internal class JointRepairObservationCapture(
    private val budget: SourceLpBudget,
    private val stop: ExecutionControl,
    private val report: (JointRepairObservation) -> Unit,
) : ExactNeighborhoodAdvanceObserver {
    private var records = 0L
    private var entries = 0L
    private var bytes = 0L
    private var dropped = 0L
    private var nanos = 0L
    private var decline: String? = null
    private var advances = 0L
    private var droppedAdvances = 0L
    private var advanceWork = 0L
    private var advanceBytes = 0L
    private var advanceNanos = 0L

    fun observations(): Map<String, Long> = mapOf(
        "jointRepairObservationRecords" to records,
        "jointRepairObservationEntries" to entries,
        "jointRepairObservationBytes" to bytes,
        "jointRepairObservationDropped" to dropped,
        "jointRepairObservationNs" to nanos,
        "jointRepairObservationAdvances" to advances,
        "jointRepairObservationDroppedAdvances" to droppedAdvances,
        "jointRepairObservationAdvanceWork" to advanceWork,
        "jointRepairObservationAdvanceBytes" to advanceBytes,
        "jointRepairObservationAdvanceReportNs" to advanceNanos,
    ) + (decline?.let { mapOf("jointRepairObservationDeclined$it" to 1L) } ?: emptyMap())

    override fun reserveAdvance(): Boolean {
        val funding = budget.funding
        // The driver owns cancellation and the slice grant. Charge the containing ledger only.
        if (advances >= MAX_ADVANCES || funding == null || funding.remainingWork <= ADVANCE_WORK ||
            funding.remainingAllocation <= ADVANCE_BYTES) {
            droppedAdvances++
            return false
        }
        budget.observe(preparation = ADVANCE_WORK)
        funding.charge(0L, ADVANCE_BYTES)
        advanceWork += ADVANCE_WORK
        advanceBytes += ADVANCE_BYTES
        return true
    }

    override fun recordAdvance(observation: ExactNeighborhoodAdvanceObservation) {
        val mark = TimeSource.Monotonic.markNow()
        try {
            report(JointRepairObservation(0L, 0L, "advance", observation.guard.name, advance = observation))
            advances++
        } finally {
            advanceNanos += mark.elapsedNow().inWholeNanoseconds
        }
    }

    private fun pay(work: Long, allocation: Long): Boolean {
        val funding = budget.funding
        decline = when {
            stop() -> "Cancelled"
            bytes + allocation > MAX_BYTES -> "Bytes"
            funding == null || funding.remainingWork <= work || funding.remainingAllocation <= allocation -> "Funding"
            else -> null
        }
        if (decline != null) return false
        budget.observe(preparation = work)
        requireNotNull(funding).charge(0L, allocation)
        bytes += allocation
        return true
    }

    private fun entry(): Boolean {
        if (entries >= MAX_ENTRIES) {
            decline = "Entries"
            return false
        }
        if (!pay(1L, 64L)) return false
        entries++
        return true
    }

    private fun scalar(value: BigFraction?): Boolean {
        if (value == null) return true
        val bits = value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength()
        if (bits > MAX_SCALAR_BITS) {
            decline = "Scalar"
            return false
        }
        return pay(1L + bits, 64L + bits)
    }

    private fun capture(build: () -> JointRepairObservation?) {
        if (decline != null) {
            dropped++
            return
        }
        val mark = TimeSource.Monotonic.markNow()
        try {
            if (records >= MAX_RECORDS) decline = "Records"
            if (decline != null || !pay(1L, 256L)) {
                dropped++
                return
            }
            val observation = build()
            if (observation == null || stop()) {
                if (decline == null) decline = "Cancelled"
                dropped++
                return
            }
            records++
            report(observation)
        } finally {
            nanos += mark.elapsedNow().inWholeNanoseconds
        }
    }

    private fun bounds(values: Map<Int, BigFraction>): Map<Int, BigFraction>? {
        val result = linkedMapOf<Int, BigFraction>()
        for ((id, value) in values) {
            if (!entry() || !scalar(value)) return null
            result[id] = value
        }
        return result
    }

    fun proposal(sequence: Long, operator: Int, schedulingRound: Long, proposalRound: Long,
        requested: Int, effective: Int, kind: String, centers: List<BigFraction>, integers: Int,
        eligible: IntArray?, roots: List<Int>, condition: ExactNeighborhoodCondition) = capture {
        val copied = linkedMapOf<Int, BigFraction>()
        val freed = mutableListOf<Int>()
        for (id in eligible?.asIterable() ?: (0 until integers)) {
            if (!entry() || !scalar(centers[id])) return@capture null
            copied[id] = centers[id]
            if (id !in condition.lower && id !in condition.upper) freed += id
        }
        val bools = linkedMapOf<Int, Boolean>()
        for ((id, value) in condition.bools) {
            if (!entry()) return@capture null
            bools[id] = value
        }
        val lower = bounds(condition.lower) ?: return@capture null
        val upper = bounds(condition.upper) ?: return@capture null
        val refutationRoots = mutableListOf<Int>()
        for (root in roots) {
            if (!entry()) return@capture null
            refutationRoots += root
        }
        if (!scalar(condition.cutoff)) return@capture null
        JointRepairObservation(sequence, 0L, "proposal", "constructed", operator, schedulingRound,
            proposalRound, requested, effective, kind, copied, bools, lower, upper, freed, refutationRoots,
            cutoff = condition.cutoff, integerCount = integers, realCount = centers.size - integers,
            booleanCount = condition.bools.size)
    }

    fun feedback(proposal: Long, query: Long, disposition: String,
        assumptions: Set<SourceDerivation.Assumption>?, condition: ExactNeighborhoodCondition,
        integers: Int) = capture {
        val support = mutableListOf<JointRepairObservedBound>()
        for (assumption in assumptions.orEmpty()) {
            if (!entry()) return@capture null
            val coordinate = assumption.coordinate
            val id = when (coordinate.kind) {
                SourceCoordinate.Kind.Integer -> coordinate.id
                SourceCoordinate.Kind.Real -> integers + coordinate.id
                SourceCoordinate.Kind.Boolean -> null
            }
            val value = id?.let { if (assumption.upper) condition.upper[it] else condition.lower[it] }
            if (!scalar(value)) return@capture null
            support += JointRepairObservedBound(coordinate, assumption.upper, value)
        }
        JointRepairObservation(proposal, query, "feedback", disposition, support = support)
    }

    fun event(proposal: Long, query: Long, phase: String, disposition: String) = capture {
        JointRepairObservation(proposal, query, phase, disposition)
    }
}

private const val MAX_RECORDS = 64
private const val MAX_ADVANCES = 128L
private const val ADVANCE_WORK = 16L
private const val ADVANCE_BYTES = 512L
private const val MAX_ENTRIES = 16_384
private const val MAX_BYTES = 1_048_576L
private const val MAX_SCALAR_BITS = 4096
