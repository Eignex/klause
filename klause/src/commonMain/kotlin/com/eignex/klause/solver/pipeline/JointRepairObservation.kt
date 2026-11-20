package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength
import kotlin.time.TimeSource

internal data class JointRepairObservedBound(
    val coordinate: SourceCoordinate,
    val upper: Boolean,
    val value: BigFraction?,
)

internal data class JointRepairObservedSelection(
    val pass: Long,
    val rootIndex: Int,
    val rootCoordinate: Int,
    val expanded: Boolean,
    val visits: Long,
    val closed: Boolean?,
    val row: Int?,
    val column: Int?,
    val peer: Int?,
    val sourceRevision: String,
    val sourceSemantics: String,
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
    val selection: JointRepairObservedSelection? = null,
    val realCenters: Map<Int, Double> = emptyMap(),
    val realCentersComplete: Boolean? = null,
)

internal class JointRepairObservationCapture(
    private val budget: SourceLpBudget,
    private val stop: ExecutionControl,
    private val report: (JointRepairObservation) -> Unit,
) {
    private var records = 0L
    private var entries = 0L
    private var bytes = 0L
    private var dropped = 0L
    private var nanos = 0L
    private var decline: String? = null

    fun observations(): Map<String, Long> = mapOf(
        "jointRepairObservationRecords" to records,
        "jointRepairObservationEntries" to entries,
        "jointRepairObservationBytes" to bytes,
        "jointRepairObservationDropped" to dropped,
        "jointRepairObservationNs" to nanos,
    ) + (decline?.let { mapOf("jointRepairObservationDeclined$it" to 1L) } ?: emptyMap())

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

    private fun capture(build: () -> JointRepairObservation?): JointRepairObservation? {
        if (decline != null) {
            dropped++
            return null
        }
        val mark = TimeSource.Monotonic.markNow()
        try {
            if (records >= MAX_RECORDS) decline = "Records"
            if (decline != null || !pay(1L, 512L)) {
                dropped++
                return null
            }
            val observation = build()
            if (observation == null || stop()) {
                if (decline == null) decline = "Cancelled"
                dropped++
                return null
            }
            records++
            report(observation)
            return observation
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
        eligible: IntArray?, roots: List<Int>, condition: ExactNeighborhoodCondition,
        reals: Int = centers.size - integers, selection: JointRepairObservedSelection? = null) = capture {
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
            cutoff = condition.cutoff, integerCount = integers, realCount = reals,
            booleanCount = condition.bools.size, selection = selection)
    }

    fun advisorySelection(pass: Long, root: Int, coordinate: Int, expanded: Boolean,
        centers: List<BigFraction>, sample: Sample?, reals: Int, cutoff: BigFraction, revision: SourceRevision,
        result: JointRepairNeighborhood.AdvisorySelection, visits: Long, kind: String): JointRepairObservedSelection? =
        capture {
            val text = revision.identity.length.toLong() + revision.semantics.length
            if (!pay(1L + text, 2L * text) || !scalar(cutoff)) return@capture null
            val copied = linkedMapOf<Int, BigFraction>()
            for (id in centers.indices) {
                if (!entry() || !scalar(centers[id])) return@capture null
                copied[id] = centers[id]
            }
            val freed = mutableListOf<Int>()
            val ready = result as? JointRepairNeighborhood.AdvisorySelection.Ready
            val declined = result as? JointRepairNeighborhood.AdvisorySelection.Declined
            if (declined == null && !pay(1L + centers.size, 0L)) return@capture null
            for (id in declined?.freed ?: centers.indices) {
                if (declined != null ||
                    (ready != null && id !in ready.condition.lower && id !in ready.condition.upper)) {
                    if (!entry()) return@capture null
                    freed += id
                }
            }
            val approximate = linkedMapOf<Int, Double>()
            var complete = reals == 0
            if (sample != null && sample.numRealVars == reals) {
                complete = true
                for (id in 0 until reals) {
                    if (!entry() || !pay(1L, 64L)) return@capture null
                    val value = sample.approximateRealValue(id)
                    if (value.isFinite()) approximate[id] = value else complete = false
                }
            }
            val metadata = JointRepairObservedSelection(pass, root, coordinate, expanded, visits,
                ready?.closed, declined?.row, declined?.column, declined?.peer,
                revision.identity, revision.semantics)
            val disposition = when (result) {
                is JointRepairNeighborhood.AdvisorySelection.Ready -> "ready"
                is JointRepairNeighborhood.AdvisorySelection.Declined -> when (result.limit) {
                    JointRepairNeighborhood.AdvisorySelection.Limit.Width -> "width-declined"
                    JointRepairNeighborhood.AdvisorySelection.Limit.Visits -> "visits-declined"
                }
                JointRepairNeighborhood.AdvisorySelection.Unavailable -> "unavailable"
            }
            JointRepairObservation(0L, 0L, "selection", disposition, centerKind = kind, centers = copied,
                freed = freed, cutoff = cutoff, integerCount = centers.size, realCount = reals,
                booleanCount = 0, selection = metadata, realCenters = approximate, realCentersComplete = complete)
        }?.selection

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
private const val MAX_ENTRIES = 16_384
private const val MAX_BYTES = 1_048_576L
private const val MAX_SCALAR_BITS = 4096
