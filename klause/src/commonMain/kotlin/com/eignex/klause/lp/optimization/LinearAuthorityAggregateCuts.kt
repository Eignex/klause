package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.cut.AggregationMirSeparator
import com.eignex.klause.lp.cut.CutPool
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.div
import com.eignex.util.numbers.times
import kotlin.math.abs
import kotlin.math.sqrt

internal data class LinearAuthorityAggregateTerm(
    val owner: Int,
    val premise: String,
    val inequality: SourceInequality,
    val weight: BigFraction = BigFraction.ONE,
)

internal data class LinearAuthorityAggregateLimits(
    val rows: Int = 4,
    val support: Int = 128,
    val scalarBits: Int = 256,
    val proofBytes: Long = 16_384L,
    val work: Long = 65_536L,
    val allocation: Long = 1024L * 1024L,
) {
    init {
        require(rows >= 2 && support > 0 && scalarBits in 1..4096 &&
            proofBytes >= 0L && work >= 0L && allocation >= 0L)
    }
}

internal data class LinearAuthorityAggregateProbeResult(
    val candidates: List<LinearAuthorityCut>,
    val violations: List<BigFraction>,
    val activeSourceRowsSatisfied: Boolean?,
    val maximumSourceResidual: BigFraction?,
    val complete: Boolean,
)

internal object LinearAuthorityAggregateCuts {
    val sourcePairLimits = LinearAuthorityAggregateLimits(work = 500_000L, allocation = 8_388_608L)
    val pointSourcePairLimits = LinearAuthorityAggregateLimits()

    fun prepareSourcePairs(
        source: SourceProofContext, cancellation: ExecutionControl,
        observe: (String, Long) -> Unit = { _, _ -> }, maximumCandidates: Int = 64,
        limits: LinearAuthorityAggregateLimits = sourcePairLimits,
        point: Map<SourceCoordinate, Double>? = null,
    ): List<LinearAuthorityCut> {
        require(maximumCandidates in 0..64)
        val meter = AggregateMeter(limits, cancellation, observe)
        val attemptLimits = limits.copy(work = minOf(limits.work, 65_536L),
            allocation = minOf(limits.allocation, 1_048_576L))
        val candidates = ArrayList<LinearAuthorityCut>()
        var eligibilityKnown = true
        var retainedContinuationUsed = false
        try {
            meter.reserve(source.premiseCount.toLong(), 512L)
            val rows = ArrayList<ProbeRow>()
            val integerRows = if (point == null) null else {
                meter.reserve(1L, 128L)
                ArrayList<ProbeRow>()
            }
            val bounds = HashMap<Pair<SourceCoordinate, Boolean>, LinearAuthorityAggregateTerm>()
            val incidence = HashMap<Pair<SourceCoordinate, Boolean>, MutableList<ProbeRow>>()
            var ordinal = 0
            source.forEachPremise { name, premise ->
                val owner = ordinal++
                meter.reserve(1L, 128L + name.length * 2L)
                val term = LinearAuthorityAggregateTerm(owner, name, premise.inequality)
                if (term.inequality.supportSize > limits.support) {
                    eligibilityKnown = false
                    observe("AggregatePairSourceSupportLimit", 1L)
                    return@forEachPremise
                }
                meter.reserve(term.inequality.supportSize * 3L, 96L + term.inequality.supportSize * 64L)
                val coefficients = term.inequality.coefficients
                if (coefficients.keys.any { it.kind == SourceCoordinate.Kind.Boolean }) return@forEachPremise
                val row = ProbeRow(term, coefficients)
                if (row.realCount == 0) {
                    if (integerRows != null && row.hasInteger && coefficients.size > 1) {
                        if (integerRows.size < AggregationMirSeparator.MAX_ROWS) {
                            meter.reserve(1L, 16L)
                            integerRows += row
                        } else {
                            eligibilityKnown = false
                            observe("AggregateIntegerRowLimit", 1L)
                        }
                    }
                    return@forEachPremise
                }
                rows += row
                for ((id, coefficient) in coefficients) {
                    meter.reserve(1L)
                    if (id.kind != SourceCoordinate.Kind.Real) continue
                    meter.reserve(1L, 64L)
                    val side = id to (coefficient > BigFraction.ZERO)
                    val bucket = incidence[side] ?: run {
                        meter.reserve(1L, 160L)
                        ArrayList<ProbeRow>().also { incidence[side] = it }
                    }
                    bucket.add(row)
                    observe("AggregateIncidenceReferences", 1L)
                    if (coefficients.size == 1) {
                        meter.reserve(1L, 96L)
                        bounds.getOrPut(side) { term }
                    }
                }
            }
            if (point != null) orderPointRows(rows, incidence, point, meter, observe, source)
            val charge: (String, Long) -> Unit = { name, count ->
                when (name) {
                    "AggregateWork" -> meter.reserve(count)
                    "AggregateAllocation" -> meter.reserve(0L, count)
                    else -> {
                        if (name.endsWith("Limit") || name == "AggregateStopped" || name.endsWith("Identity")) {
                            eligibilityKnown = false
                        }
                        observe(name, count)
                    }
                }
            }
            val derivedStop = object : ExecutionControl() {
                override fun isCancelled(): Boolean = cancellation()
                override fun deadline() = cancellation.deadline()
            }
            val selectedBounds = if (point == null) bounds else pointBounds(bounds, point, meter, observe)
            val integerBounds = if (point == null) null else {
                meter.reserve(1L, 64L)
                IntegerBoundIndex(source)
            }
            val extension = if (point == null) null else {
                meter.reserve(1L, 320L)
                PairExtension(incidence, selectedBounds, bounds, point, meter, attemptLimits, derivedStop,
                    observe, checkNotNull(integerBounds))
            }
            if (!integerRows.isNullOrEmpty() && point != null) {
                eligibilityKnown = false
                meter.reserve(1L, 128L)
                val integerLimits = attemptLimits.copy(work = minOf(meter.remainingWork / 4L, 16_384L),
                    allocation = minOf(meter.remainingAllocation / 4L, 262_144L))
                val integerMeter = AggregateMeter(integerLimits, derivedStop, charge)
                try {
                    integerMeter.reserve(1L, 320L)
                    PairExtension(incidence, selectedBounds, bounds, point, integerMeter, integerLimits,
                        derivedStop, charge, checkNotNull(integerBounds)).integerRows(
                        integerRows, candidates, maximumCandidates)
                } catch (stop: AggregateStop) {
                    charge("AggregateInteger" + stop.reason, 1L)
                }
            }
            val beforeProjection = candidates.size
            if (point != null && limits.rows > 4) {
                eligibilityKnown = false
                val projectionLimits = attemptLimits.copy(rows = minOf(16, limits.rows),
                    work = minOf(meter.remainingWork / 4L, 16_384L),
                    allocation = minOf(meter.remainingAllocation / 4L, 262_144L))
                val projectionMeter = AggregateMeter(projectionLimits, derivedStop, charge)
                try {
                    projectionMeter.reserve(1L, 320L)
                    PairExtension(incidence, selectedBounds, bounds, point, projectionMeter, projectionLimits,
                        derivedStop, charge, checkNotNull(integerBounds)).integerProjections(
                        rows, candidates, maximumCandidates)
                } catch (stop: AggregateStop) {
                    charge("AggregateIntegerProjection" + stop.reason, 1L)
                }
            }
            val projectedInteger = candidates.size > beforeProjection
            for (row in rows) {
                meter.reserve(1L)
                if (!row.hasInteger) continue
                meter.reserve(1L, 128L)
                val paired = HashSet<Int>()
                for ((id, coefficient) in row.coefficients) {
                    meter.reserve(1L)
                    if (id.kind != SourceCoordinate.Kind.Real) continue
                    for (other in incidence[id to (coefficient < BigFraction.ZERO)].orEmpty()) {
                        meter.reserve(1L)
                        if (other.hasInteger && other.term.owner <= row.term.owner) continue
                        meter.reserve(1L, 64L)
                        if (!paired.add(other.term.owner)) continue
                        if (candidates.size >= maximumCandidates) stopAggregate("RetentionLimit")
                        observe("AggregatePairAttempts", 1L)
                        val pivot = other.coefficients.getValue(id)
                        val rightWeight = meter.number(if (coefficient > BigFraction.ZERO) coefficient
                        else coefficient.negated())
                        val leftWeight = meter.number(if (pivot > BigFraction.ZERO) pivot else pivot.negated())
                        meter.reserve(1L, 320L)
                        val terms = listOf(row.term.copy(weight = leftWeight), other.term.copy(weight = rightWeight))
                        val snapshots = listOf(row.coefficients, other.coefficients)
                        var cut = deriveWithCoefficients(terms, derivedStop, charge, attemptLimits,
                            allowMixed = true, continuousBounds = selectedBounds,
                            coefficientSnapshots = snapshots, capture = extension?.capture)
                        var continuedAfterRetention = false
                        if (point != null) {
                            val initial = cut
                            val separating = if (initial == null) false else separates(initial, point, meter, observe)
                            if (separating == null) {
                                eligibilityKnown = false
                                continue
                            }
                            if (!separating) {
                                if (candidates.isNotEmpty()) {
                                    if (retainedContinuationUsed) continue
                                    retainedContinuationUsed = true
                                    continuedAfterRetention = true
                                    observe("AggregateRetainedContinuation", 1L)
                                }
                                cut = extension?.extend(terms, snapshots)
                                if (extension?.attempted == true) eligibilityKnown = false
                            }
                        }
                        val retained = cut ?: continue
                        if ((continuedAfterRetention || projectedInteger) &&
                            redundantContinuation(retained, candidates, meter, limits)) {
                            observe("AggregateRetainedContinuationDuplicate", 1L)
                            continue
                        }
                        meter.reserve(1L, 128L + retained.inequality.supportSize * 64L)
                        candidates += retained
                    }
                }
            }
            observe(if (eligibilityKnown) "AggregatePairComplete" else "AggregatePairIncomplete", 1L)
        } catch (stop: AggregateStop) {
            observe("AggregatePair${stop.reason}", 1L)
        }
        observe("AggregatePairRetainedCandidates", candidates.size.toLong())
        return candidates.toList()
    }

    private fun redundantContinuation(
        candidate: LinearAuthorityCut, retained: List<LinearAuthorityCut>,
        meter: AggregateMeter, limits: LinearAuthorityAggregateLimits,
    ): Boolean {
        for (earlier in retained) {
            meter.reserve(1L + candidate.owners.size + earlier.owners.size)
            if (!candidate.owners.containsAll(earlier.owners)) continue
            val support = candidate.inequality.supportSize.toLong() + earlier.inequality.supportSize + 2L
            meter.reserve(support * (1L + (limits.scalarBits + 63L) / 64L))
            if (candidate.inequality != earlier.inequality) continue
            if (sourcePremisesOnly(candidate.derivation, meter, limits) &&
                sourcePremisesOnly(earlier.derivation, meter, limits)) return true
        }
        return false
    }

    private fun sourcePremisesOnly(
        proof: SourceDerivation, meter: AggregateMeter, limits: LinearAuthorityAggregateLimits, depth: Int = 0,
    ): Boolean {
        meter.reserve(1L)
        if (depth >= 8) return false
        return when (proof) {
            is SourceDerivation.Premise -> true
            is SourceDerivation.IntegerRounding -> sourcePremisesOnly(proof.inequality, meter, limits, depth + 1)
            is SourceDerivation.MixedIntegerRounding -> {
                meter.reserve(limits.support.toLong() + 1L, 64L + limits.support * 16L)
                sourcePremisesOnly(proof.inequality, meter, limits, depth + 1) &&
                    proof.bounds.all { sourcePremisesOnly(it, meter, limits, depth + 1) }
            }
            is SourceDerivation.Combination -> {
                val terms = limits.support.toLong() + limits.rows + 1L
                meter.reserve(terms, 64L + terms * 16L)
                proof.terms.all { sourcePremisesOnly(it.derivation, meter, limits, depth + 1) }
            }
            else -> false
        }
    }

    private fun admitPointRowOrder(
        rows: List<ProbeRow>, incidence: Map<Pair<SourceCoordinate, Boolean>, List<ProbeRow>>,
        meter: AggregateMeter, observe: (String, Long) -> Unit,
    ): Boolean {
        if (rows.size < 3) return false
        meter.reserve(rows.size.toLong() + incidence.size * 2L, 128L)
        val entries = rows.sumOf { it.coefficients.size.toLong() }
        val references = incidence.values.sumOf { it.size.toLong() }
        fun sortingWork(size: Int): Long {
            val levels = if (size < 2) 0 else Int.SIZE_BITS - (size - 1).countLeadingZeroBits()
            return size.toLong() * (1L + levels)
        }
        val projectionWork = 9L * (entries + rows.size)
        val comparisonWork = sortingWork(rows.size) + incidence.values.sumOf { sortingWork(it.size) }
        val allocation = 192L + rows.size * 80L + (entries + rows.size) * 96L +
            references * 16L + incidence.size * 64L
        if (projectionWork + comparisonWork > meter.remainingWork / 2L ||
            allocation > meter.remainingAllocation / 2L) {
            observe("AggregatePointRowOrderDeclined", 1L)
            return false
        }
        return true
    }

    private fun orderPointRows(
        rows: MutableList<ProbeRow>, incidence: Map<Pair<SourceCoordinate, Boolean>, MutableList<ProbeRow>>,
        point: Map<SourceCoordinate, Double>, meter: AggregateMeter, observe: (String, Long) -> Unit,
        source: SourceProofContext,
    ) {
        if (!admitPointRowOrder(rows, incidence, meter, observe)) return
        meter.reserve(rows.size.toLong(), 128L + rows.size * 64L)
        val priorities = HashMap<Int, Double>()
        for (row in rows) {
            var activity = 0.0
            var normSquared = 0.0
            var complete = true
            for ((id, value) in row.coefficients) {
                meter.reserve(4L)
                val coordinate = point[id]
                if (coordinate == null || !coordinate.isFinite()) {
                    complete = false
                    break
                }
                val coefficient = meter.number(value).toDouble()
                activity += coefficient * coordinate
                normSquared += coefficient * coefficient
            }
            val rhs = row.term.inequality.rhs
            meter.reserve(4L)
            val projectedRhs = meter.number(rhs).toDouble()
            val distance = if (complete) abs(projectedRhs - activity) / sqrt(normSquared) else Double.NaN
            priorities[row.term.owner] = if (distance.isFinite()) {
                observe("AggregatePointRowsScored", 1L)
                distance
            } else {
                observe("AggregatePointRowUnavailable", 1L)
                Double.POSITIVE_INFINITY
            }
        }
        val comparator = Comparator<ProbeRow> { left, right ->
            meter.reserve(1L)
            val order = priorities.getValue(left.term.owner).compareTo(priorities.getValue(right.term.owner))
            if (order == 0) left.term.owner.compareTo(right.term.owner) else order
        }
        fun order(values: MutableList<ProbeRow>) {
            meter.reserve(values.size.toLong(), 64L + values.size * 16L)
            values.sortWith(comparator)
        }
        order(rows)
        for (bucket in incidence.values) order(bucket)
        prioritizeScopedRows(rows, priorities, source, meter, observe)
    }

    private fun prioritizeScopedRows(
        rows: MutableList<ProbeRow>, priorities: Map<Int, Double>, source: SourceProofContext,
        meter: AggregateMeter, observe: (String, Long) -> Unit,
    ) {
        val condition = source.scope.condition
        if (!condition.hasNumericBounds) return
        meter.reserve(rows.size.toLong(), 128L)
        val work = rows.size * 5L + rows.sumOf { it.coefficients.size.toLong() } * 3L
        val allocation = 256L + rows.size * 128L
        if (work > meter.remainingWork / 2L || allocation > meter.remainingAllocation / 2L) {
            observe("AggregatePointScopedOrderDeclined", 1L)
            return
        }
        meter.reserve(rows.size.toLong(), allocation)
        val scoped = ArrayList<ProbeRow>()
        val other = ArrayList<ProbeRow>()
        for (row in rows) {
            meter.reserve(1L)
            var relevant = false
            if (priorities.getValue(row.term.owner).isFinite()) for (id in row.coefficients.keys) {
                meter.reserve(3L)
                if (id.kind == SourceCoordinate.Kind.Integer &&
                    (condition.boundValue(id, false) != null || condition.boundValue(id, true) != null)) relevant = true
            }
            if (relevant) {
                scoped += row
                observe("AggregatePointScopedRows", 1L)
            } else other += row
        }
        var index = 0
        for (group in listOf(scoped, other)) {
            for (row in group) {
                meter.reserve(1L)
                rows[index++] = row
            }
        }
        observe("AggregatePointScopedOrderComplete", 1L)
    }

    private fun pointBounds(
        bounds: Map<Pair<SourceCoordinate, Boolean>, LinearAuthorityAggregateTerm>,
        point: Map<SourceCoordinate, Double>, meter: AggregateMeter, observe: (String, Long) -> Unit,
    ): Map<Pair<SourceCoordinate, Boolean>, LinearAuthorityAggregateTerm> {
        meter.reserve(bounds.size.toLong(), 128L + bounds.size * 96L)
        val selected = HashMap(bounds)
        for ((side, lower) in bounds) {
            meter.reserve(1L)
            if (side.second) continue
            val id = side.first
            val upper = bounds[id to true] ?: continue
            val value = point[id]?.takeIf { it.isFinite() } ?: continue
            fun distance(bound: LinearAuthorityAggregateTerm): Double {
                meter.reserve(4L, 128L)
                val coefficient = meter.number(bound.inequality.coefficients.getValue(id)).toDouble()
                val rhs = meter.number(bound.inequality.rhs).toDouble()
                return abs(value - rhs / coefficient)
            }
            val lowerDistance = distance(lower)
            val upperDistance = distance(upper)
            if (!lowerDistance.isFinite() || !upperDistance.isFinite()) {
                observe("AggregatePointBoundUnavailable", 1L)
                continue
            }
            val lowerChosen = lowerDistance <= upperDistance
            meter.reserve(1L, 64L)
            if (lowerChosen) selected.remove(id to true) else selected.remove(id to false)
            observe(if (lowerChosen) "AggregatePointLowerBounds" else "AggregatePointUpperBounds", 1L)
        }
        return selected
    }

    private fun separates(
        cut: LinearAuthorityCut, point: Map<SourceCoordinate, Double>, meter: AggregateMeter,
        observe: (String, Long) -> Unit,
    ): Boolean? {
        meter.reserve(1L, 256L)
        var activity = 0.0
        var normSquared = 0.0
        val complete = cut.inequality.forEachCoefficient { id, value ->
            meter.reserve(4L, 32L)
            val coordinate = point[id] ?: return@forEachCoefficient false
            val coefficient = value.toDouble()
            activity += coefficient * coordinate
            normSquared += coefficient * coefficient
            true
        }
        if (!complete) {
            observe("AggregatePairPointUnavailable", 1L)
            return null
        }
        meter.reserve(1L)
        val efficacy = (activity - cut.inequality.rhs.toDouble()) / sqrt(normSquared)
        if (!efficacy.isFinite()) {
            observe("AggregatePairPointUnavailable", 1L)
            return null
        }
        val eligible = efficacy >= CutPool.MIN_EFFICACY
        observe(if (eligible) "AggregatePairPointEligible" else "AggregatePairLowEfficacy", 1L)
        return eligible
    }

    private class ProbeRow(
        val term: LinearAuthorityAggregateTerm, val coefficients: Map<SourceCoordinate, BigFraction>,
    ) {
        val realCount = coefficients.keys.count { it.kind == SourceCoordinate.Kind.Real }
        val firstInteger = coefficients.keys.firstOrNull { it.kind == SourceCoordinate.Kind.Integer }
        val hasInteger: Boolean get() = firstInteger != null
    }

    private class PairExtension(
        private val incidence: Map<Pair<SourceCoordinate, Boolean>, List<ProbeRow>>,
        private val continuousBounds: Map<Pair<SourceCoordinate, Boolean>, LinearAuthorityAggregateTerm>,
        private val preferredBounds: Map<Pair<SourceCoordinate, Boolean>, LinearAuthorityAggregateTerm>,
        private val point: Map<SourceCoordinate, Double>,
        private val meter: AggregateMeter,
        private val limits: LinearAuthorityAggregateLimits,
        private val cancellation: ExecutionControl,
        private val observe: (String, Long) -> Unit,
        private val integerBounds: IntegerBoundIndex,
    ) {
        private class Continuation(val row: ProbeRow, val residual: BigFraction, val pivot: BigFraction)
        private class ProjectionSeed(val row: ProbeRow, val coordinate: SourceCoordinate, val fraction: Double)
        private val slacks = HashMap<Int, Double>()
        val capture = AggregateCapture()
        var attempted = false
            private set
        private val charge: (String, Long) -> Unit = { name, count ->
            when (name) {
                "AggregateWork" -> meter.reserve(count)
                "AggregateAllocation" -> meter.reserve(0L, count)
                else -> observe(name, count)
            }
        }

        private fun continuation(
            terms: List<LinearAuthorityAggregateTerm>, aggregate: Map<SourceCoordinate, BigFraction>,
            snapshots: List<Map<SourceCoordinate, BigFraction>>,
            knownSupport: Set<SourceCoordinate>? = null,
            integerProjection: Boolean = false,
        ): Continuation? {
            val support = knownSupport ?: run {
                meter.reserve(snapshots.size.toLong(), 128L + snapshots.size * 32L)
                val collected = HashSet<SourceCoordinate>()
                for (row in snapshots) {
                    for (id in row.keys) {
                        meter.reserve(1L)
                        if (id in collected) continue
                        if (collected.size >= limits.support) stopAggregate("SupportLimit")
                        meter.reserve(1L, 64L)
                        collected.add(id)
                    }
                }
                collected
            }
            for ((id, value) in aggregate) {
                meter.reserve(1L)
                if (id.kind != SourceCoordinate.Kind.Real) continue
                meter.reserve(1L, 64L)
                var first: ProbeRow? = null
                var selected: ProbeRow? = null
                var best = Double.POSITIVE_INFINITY
                var bestRank = Long.MIN_VALUE
                for (other in incidence[id to (value < BigFraction.ZERO)].orEmpty()) {
                    meter.reserve(1L + terms.size)
                    if (other.coefficients.size <= 1 || terms.any { it.owner == other.term.owner }) continue
                    attempted = true
                    if (!fitsSupport(other, support)) {
                        observe("AggregateContinuationSupportSkipped", 1L)
                        continue
                    }
                    val pivot = other.coefficients.getValue(id)
                    if (cancelsTerm(other, terms, value, pivot, integerProjection)) {
                        observe("AggregateContinuationInverseSkipped", 1L)
                        continue
                    }
                    if (first == null) first = other
                    val rank = if (integerProjection) projectionRank(other, aggregate, support) else 0L
                    if (rank < bestRank) continue
                    val score = continuationScore(other, pivot)
                    if (score == null) {
                        observe("AggregateContinuationPointUnavailable", 1L)
                    } else {
                        observe("AggregateContinuationScored", 1L)
                        if (rank > bestRank || (rank == bestRank && score < best)) {
                            best = score
                            bestRank = rank
                            selected = other
                        }
                    }
                }
                val row = selected ?: first ?: continue
                meter.reserve(1L, 128L)
                observe("AggregateContinuationComplete", 1L)
                return Continuation(row, value, row.coefficients.getValue(id))
            }
            return null
        }

        private fun fitsSupport(row: ProbeRow, support: Set<SourceCoordinate>): Boolean {
            meter.reserve(1L, 32L)
            var size = support.size
            for (id in row.coefficients.keys) {
                meter.reserve(1L)
                if (id !in support && ++size > limits.support) return false
            }
            return true
        }

        private fun cancelsTerm(
            row: ProbeRow, terms: List<LinearAuthorityAggregateTerm>, residual: BigFraction, pivot: BigFraction,
            allowWeaker: Boolean = false,
        ): Boolean {
            if (row.term.inequality.strict) return false
            for (term in terms) {
                meter.reserve(1L)
                if (term.inequality.strict || term.inequality.supportSize != row.coefficients.size) continue
                val inverse = term.inequality.forEachCoefficient { coordinate, value ->
                    meter.reserve(3L)
                    val other = row.coefficients[coordinate] ?: return@forEachCoefficient false
                    if (!value.isZero && (value < BigFraction.ZERO) == (other < BigFraction.ZERO)) {
                        return@forEachCoefficient false
                    }
                    meter.number(value.negated()) == meter.number(other)
                }
                if (!inverse) continue
                val inverseRhs = meter.number(term.inequality.rhs.negated())
                val rowRhs = meter.number(row.term.inequality.rhs)
                if (allowWeaker) meter.arithmetic(rowRhs, inverseRhs)
                if (if (allowWeaker) rowRhs < inverseRhs else rowRhs != inverseRhs) continue
                val appended = meter.number(if (residual > BigFraction.ZERO) residual else residual.negated())
                val scale = meter.number(if (pivot > BigFraction.ZERO) pivot else pivot.negated())
                if (meter.multiply(term.weight, scale) == appended) return true
            }
            return false
        }

        private fun projectionRank(
            row: ProbeRow, aggregate: Map<SourceCoordinate, BigFraction>, seen: Set<SourceCoordinate>? = null,
        ): Long {
            var introduced = 0
            var opposed = 0
            var revived = false
            for ((id, value) in row.coefficients) {
                meter.reserve(1L)
                if (id.kind != SourceCoordinate.Kind.Real) continue
                val current = aggregate[id]
                if (current == null) {
                    meter.reserve(1L)
                    introduced++
                    if (seen != null && id in seen) revived = true
                } else {
                    meter.reserve(2L)
                    if ((current < BigFraction.ZERO) != (value < BigFraction.ZERO)) opposed++
                }
            }
            val base = limits.support.toLong() + 1L
            return opposed * base - introduced - if (revived) base * base else 0L
        }

        private fun continuationScore(row: ProbeRow, pivot: BigFraction): Double? {
            meter.reserve(4L)
            val divisor = abs(meter.number(pivot).toDouble())
            if (!divisor.isFinite() || divisor == 0.0) return null
            var slack = slacks[row.term.owner]
            if (slack == null) {
                meter.reserve(1L, 128L)
                var projected = meter.number(row.term.inequality.rhs).toDouble()
                for ((id, value) in row.coefficients) {
                    meter.reserve(4L)
                    val coordinate = point[id]?.takeIf(Double::isFinite)
                    if (coordinate == null) {
                        projected = Double.NaN
                        break
                    }
                    val coefficient = meter.number(value).toDouble()
                    if (!coefficient.isFinite()) {
                        projected = Double.NaN
                        break
                    }
                    projected -= coefficient * coordinate
                }
                slacks[row.term.owner] = projected
                slack = projected
            }
            meter.reserve(4L)
            val score = slack / divisor
            return score.takeIf { it.isFinite() }
        }

        fun integerProjections(rows: List<ProbeRow>, candidates: MutableList<LinearAuthorityCut>, maximum: Int) {
            val cap = minOf(maximum, candidates.size + 2)
            if (candidates.size >= cap) return
            observe("AggregateIntegerProjectionAllocationGrant", meter.remainingAllocation)
            meter.reserve(1L, 256L)
            val seeds = ArrayList<ProjectionSeed>()
            fun order(left: ProjectionSeed, right: ProjectionSeed): Int {
                meter.reserve(1L)
                val order = right.fraction.compareTo(left.fraction)
                return if (order == 0) left.row.term.owner.compareTo(right.row.term.owner) else order
            }
            for (row in rows) {
                meter.reserve(1L)
                if (!row.hasInteger || row.coefficients.size <= 1) continue
                var coordinate: SourceCoordinate? = null
                var fraction = CutPool.MIN_EFFICACY
                if (row.coefficients.size - row.realCount == 1) {
                    meter.reserve(4L)
                    val id = checkNotNull(row.firstInteger)
                    val value = point[id]?.takeIf(Double::isFinite) ?: continue
                    val distance = abs(value - kotlin.math.round(value))
                    if (distance > fraction) {
                        coordinate = id
                        fraction = distance
                    }
                } else for (id in row.coefficients.keys) {
                    meter.reserve(4L)
                    if (id.kind != SourceCoordinate.Kind.Integer) continue
                    val value = point[id]?.takeIf(Double::isFinite) ?: continue
                    val distance = abs(value - kotlin.math.round(value))
                    if (distance > fraction) {
                        coordinate = id
                        fraction = distance
                    }
                }
                if (coordinate != null) {
                    var duplicate = -1
                    for (index in seeds.indices) {
                        meter.reserve(1L)
                        if (seeds[index].coordinate == coordinate) duplicate = index
                    }
                    if (duplicate >= 0 && seeds[duplicate].row.term.owner <= row.term.owner) continue
                    if (duplicate >= 0) seeds.removeAt(duplicate)
                    if (seeds.size == 8) {
                        meter.reserve(1L)
                        val last = seeds.last()
                        if (fraction < last.fraction ||
                            (fraction == last.fraction && row.term.owner >= last.row.term.owner)) continue
                        seeds.removeAt(seeds.lastIndex)
                    }
                    meter.reserve(1L, 64L)
                    val retained = ProjectionSeed(row, coordinate, fraction)
                    var insertion = 0
                    while (insertion < seeds.size && order(seeds[insertion], retained) <= 0) insertion++
                    seeds.add(insertion, retained)
                }
            }
            for (seed in seeds) {
                meter.reserve(1L, 64L)
                if (candidates.size >= cap) return
                val row = seed.row
                var selected: Continuation? = null
                var best = Double.POSITIVE_INFINITY
                var bestRank = Long.MIN_VALUE
                for ((id, coefficient) in row.coefficients) {
                    meter.reserve(1L)
                    if (id.kind != SourceCoordinate.Kind.Real) continue
                    for (other in incidence[id to (coefficient < BigFraction.ZERO)].orEmpty()) {
                        meter.reserve(1L)
                        if (other.term.owner == row.term.owner || other.coefficients.size <= 1) continue
                        val pivot = other.coefficients.getValue(id)
                        val rank = projectionRank(other, row.coefficients)
                        if (rank < bestRank) continue
                        val score = continuationScore(other, pivot) ?: continue
                        if (rank > bestRank || (rank == bestRank && score < best)) {
                            best = score
                            bestRank = rank
                            meter.reserve(1L, 64L)
                            selected = Continuation(other, coefficient, pivot)
                        }
                    }
                }
                val next = selected ?: continue
                meter.arithmetic(next.residual, next.pivot)
                val weight = meter.number(next.residual.negated() * next.pivot.reciprocal())
                meter.reserve(1L, 320L)
                val terms = listOf(row.term, next.row.term.copy(weight = weight))
                val snapshots = listOf(row.coefficients, next.row.coefficients)
                observe("AggregateIntegerProjectionAttempts", 1L)
                var cut = deriveWithCoefficients(terms, cancellation, charge, limits,
                    allowMixed = false, continuousBounds = emptyMap(),
                    coefficientSnapshots = snapshots, capture = capture)
                if (cut == null || separates(cut, point, meter, observe) != true) {
                    cut = extend(terms, snapshots, integerOnly = true)
                }
                val retained = cut ?: continue
                meter.reserve(1L, 128L + retained.inequality.supportSize * 64L)
                candidates += retained
                observe("AggregateIntegerProjectionEligible", 1L)
            }
        }

        fun integerRows(rows: List<ProbeRow>, candidates: MutableList<LinearAuthorityCut>, maximum: Int) {
            val cap = minOf(maximum, candidates.size + AggregationMirSeparator.MAX_CUTS)
            fun retain(cut: LinearAuthorityCut, event: String) {
                meter.reserve(1L, 128L + cut.inequality.supportSize * 64L)
                candidates += cut
                observe(event, 1L)
            }
            for (row in rows) {
                meter.reserve(1L)
                if (candidates.size >= cap) stopAggregate("RetentionLimit")
                val cut = integerRow(row) ?: continue
                retain(cut, "AggregateIntegerRowEligible")
            }
            for (left in rows.indices) {
                for (right in left + 1 until rows.size) {
                    meter.reserve(1L, 320L)
                    if (candidates.size >= cap) stopAggregate("RetentionLimit")
                    observe("AggregateIntegerPairAttempts", 1L)
                    val first = rows[left]
                    val second = rows[right]
                    val terms = listOf(first.term, second.term)
                    val snapshots = listOf(first.coefficients, second.coefficients)
                    var cut = deriveWithCoefficients(terms, cancellation, charge, limits,
                        allowMixed = false, continuousBounds = emptyMap(),
                        coefficientSnapshots = snapshots, capture = capture)
                    val separating = if (cut == null) false else separates(cut, point, meter, observe)
                    if (separating == null) continue
                    if (!separating) cut = halfInteger()
                    val retained = cut ?: continue
                    retain(retained, "AggregateIntegerPairEligible")
                }
            }
        }

        private fun integerRow(row: ProbeRow): LinearAuthorityCut? {
            observe("AggregateIntegerRowAttempts", 1L)
            capture.integer = null
            return try {
                val term = row.term
                val proofBytes = term.premise.length * 2L + 128L + (limits.scalarBits + 7L) / 4L
                if (proofBytes > limits.proofBytes) stopAggregate("ProofLimit")
                meter.reserve(term.premise.length + 1L, 384L + proofBytes)
                if (term.owner < 0 || term.premise.isBlank() || term.weight != BigFraction.ONE) {
                    stopAggregate("SourceIdentity")
                }
                for (value in row.coefficients.values) meter.number(value)
                val scale = integerScale(row.coefficients.values, meter, observe)
                val unitScale = scale == BigFraction.ONE
                observe(if (unitScale) "AggregateIntegerRowUnitScale" else "AggregateIntegerRowScaled", 1L)
                val normalized = if (unitScale) row.coefficients else {
                    meter.reserve(row.coefficients.size.toLong(), row.coefficients.size * 96L)
                    row.coefficients.mapValues { (_, value) -> meter.multiply(value, scale) }
                }
                val originalRhs = meter.number(term.inequality.rhs)
                val rhs = if (unitScale) originalRhs else meter.multiply(originalRhs, scale)
                val combination = SourceDerivation.Combination(listOf(
                    SourceDerivation.Combination.Term(scale, SourceDerivation.Premise(term.premise))))
                capture.integer = IntegerAggregate(normalized, rhs, term.inequality.strict,
                    combination, listOf(term), proofBytes)
                observe("AggregateIntegerRowNormalized", 1L)
                halfInteger()
            } catch (stop: AggregateStop) {
                observe("AggregateIntegerRow" + stop.reason, 1L)
                null
            }
        }

        private fun halfInteger(): LinearAuthorityCut? {
            val aggregate = capture.integer ?: return null
            attempted = true
            integerBounds.prepare(aggregate.coefficients, meter, observe)
            val bounds = integerBounds
            val initial = halfIntegerCut(aggregate, bounds, cancellation, charge, limits) ?: return null
            when (separates(initial.cut, point, meter, observe)) {
                true -> {
                    observe("AggregateHalfEligible", 1L)
                    return initial.cut
                }
                null -> return null
                false -> Unit
            }
            val side = integerSide(aggregate, bounds, initial)
            if (side != null) {
                meter.reserve(1L, 64L)
                observe("AggregateHalfSideAttempts", 1L)
                val alternative = halfIntegerCut(aggregate, bounds, cancellation, charge, limits, setOf(side))
                if (alternative != null && separates(alternative.cut, point, meter, observe) == true) {
                    observe("AggregateHalfSideEligible", 1L)
                    observe("AggregateHalfEligible", 1L)
                    return alternative.cut
                }
            }
            val sides = integerPointSides(aggregate, bounds) ?: return null
            observe("AggregateHalfPointAttempts", 1L)
            val alternative = halfIntegerCut(aggregate, bounds, cancellation, charge, limits, sides) ?: return null
            if (separates(alternative.cut, point, meter, observe) != true) return null
            observe("AggregateHalfPointEligible", 1L)
            observe("AggregateHalfEligible", 1L)
            return alternative.cut
        }

        private fun integerPointSides(
            aggregate: IntegerAggregate, bounds: IntegerBoundIndex,
        ): Set<SourceCoordinate>? {
            meter.reserve(1L, 128L)
            val selected = linkedSetOf<SourceCoordinate>()
            val half = meter.number(BigFraction.ofLong(2).reciprocal())
            fun distance(bound: IntegerBound, coordinate: Double): Double {
                meter.reserve(4L, 128L)
                val coefficient = meter.number(bound.inequality.coefficients.values.single())
                meter.arithmetic(BigFraction.ONE, coefficient)
                val reciprocal = meter.number(coefficient.reciprocal())
                val endpoint = meter.multiply(meter.number(bound.inequality.rhs), reciprocal).toDouble()
                return abs(coordinate - endpoint)
            }
            for ((id, value) in aggregate.coefficients) {
                meter.reserve(1L)
                if (meter.multiply(value, half).den == BIG_ONE) continue
                val lower = bounds.lookup(id, false, meter) ?: continue
                val upper = bounds.lookup(id, true, meter) ?: continue
                val coordinate = point[id]?.takeIf { it.isFinite() } ?: continue
                val lowerDistance = distance(lower, coordinate)
                val upperDistance = distance(upper, coordinate)
                observe("AggregateHalfPointScans", 1L)
                if (!lowerDistance.isFinite() || !upperDistance.isFinite() || upperDistance >= lowerDistance) continue
                meter.reserve(1L, 64L)
                selected += id
            }
            meter.reserve(1L)
            if (selected.size < 2) return null
            observe("AggregateHalfPointComplete", 1L)
            return selected
        }

        private fun integerSide(
            aggregate: IntegerAggregate,
            bounds: IntegerBoundIndex, initial: HalfIntegerCut,
        ): SourceCoordinate? {
            meter.reserve(1L, 128L)
            var activity = 0.0
            var normSquared = 0.0
            for ((id, value) in initial.coefficients) {
                meter.reserve(4L)
                if (value.isZero) continue
                val coordinate = point[id]?.takeIf { it.isFinite() } ?: return null
                val coefficient = value.toDouble()
                activity += coefficient * coordinate
                normSquared += coefficient * coefficient
            }
            if (!activity.isFinite() || !normSquared.isFinite()) return null
            val half = meter.number(BigFraction.ofLong(2).reciprocal())
            var selected: SourceCoordinate? = null
            var best = CutPool.MIN_EFFICACY
            fun contribution(term: IntegerBound, delta: BigFraction): BigFraction {
                meter.reserve(1L, 128L)
                val coefficient = meter.number(term.inequality.coefficients.values.single())
                meter.arithmetic(BigFraction.ONE, coefficient)
                val weight = meter.multiply(delta, meter.number(coefficient.reciprocal()))
                return meter.multiply(weight, meter.number(term.inequality.rhs))
            }
            for ((id, value) in aggregate.coefficients) {
                meter.reserve(1L, 64L)
                if (meter.multiply(value, half).den == BIG_ONE) continue
                val lower = bounds.lookup(id, false, meter) ?: continue
                val upper = bounds.lookup(id, true, meter) ?: continue
                val coordinate = point[id]?.takeIf { it.isFinite() } ?: continue
                observe("AggregateHalfSideScans", 1L)
                val removed = contribution(lower, meter.number(half.negated()))
                val added = contribution(upper, half)
                val rhs = meter.add(initial.rhs, meter.add(added, meter.number(removed.negated())))
                val strictSources = initial.strictSources - (if (lower.inequality.strict) 1 else 0) +
                    (if (upper.inequality.strict) 1 else 0)
                meter.arithmetic(rhs, BigFraction.ONE)
                val rounded = meter.number(if (strictSources > 0)
                    BigFraction.of(rhs.ceilInteger(), BIG_ONE) - BigFraction.ONE
                else BigFraction.of(rhs.negated().ceilInteger(), BIG_ONE).negated())
                meter.reserve(8L)
                val coefficient = initial.coefficients.getValue(id).toDouble()
                val norm = sqrt(normSquared + 2.0 * coefficient + 1.0)
                val efficacy = (activity + coordinate - rounded.toDouble()) / norm
                if (!norm.isFinite() || norm <= 0.0 || !efficacy.isFinite() || efficacy < CutPool.MIN_EFFICACY) continue
                observe("AggregateHalfSidePredictedEligible", 1L)
                if (selected == null || efficacy > best) {
                    best = efficacy
                    selected = id
                }
            }
            return selected
        }

        private fun preferredSide(
            terms: List<LinearAuthorityAggregateTerm>, snapshots: List<Map<SourceCoordinate, BigFraction>>,
        ): LinearAuthorityCut? {
            if (continuousBounds.size == preferredBounds.size) return null
            val aggregate = capture.coefficients ?: return null
            meter.reserve(aggregate.size.toLong(), 128L)
            val different = aggregate.any { (id, value) ->
                if (id.kind != SourceCoordinate.Kind.Real) false else {
                    meter.reserve(1L, 64L)
                    val preferred = id to (value > BigFraction.ZERO)
                    preferred in preferredBounds && preferred !in continuousBounds
                }
            }
            if (!different) return null
            attempted = true
            observe("AggregatePreferredSideAttempts", 1L)
            val cut = deriveWithCoefficients(terms, cancellation, charge, limits, allowMixed = true,
                continuousBounds = preferredBounds, coefficientSnapshots = snapshots, capture = capture)
                ?: return null
            if (separates(cut, point, meter, observe) != true) return null
            observe("AggregatePreferredSideEligible", 1L)
            return cut
        }

        fun extend(
            seed: List<LinearAuthorityAggregateTerm>, seedCoefficients: List<Map<SourceCoordinate, BigFraction>>,
            integerOnly: Boolean = false,
        ): LinearAuthorityCut? {
            if (integerOnly) return extendInteger(seed, seedCoefficients)
            attempted = false
            var terms = seed
            var snapshots = seedCoefficients
            while (true) {
                val half = halfInteger()
                if (half != null) return half
                val preferred = preferredSide(terms, snapshots)
                if (preferred != null) return preferred
                if (terms.size >= minOf(4, limits.rows)) return null
                val aggregate = capture.coefficients ?: return null
                val next = continuation(terms, aggregate, snapshots) ?: return null
                val existingScale = meter.number(if (next.pivot > BigFraction.ZERO) next.pivot
                else next.pivot.negated())
                val appendedScale = meter.number(if (next.residual > BigFraction.ZERO) next.residual
                else next.residual.negated())
                meter.reserve(terms.size + 1L, 128L + (terms.size + 1L) * 160L)
                terms = terms.map { it.copy(weight = meter.multiply(it.weight, existingScale)) } +
                    next.row.term.copy(weight = appendedScale)
                snapshots += next.row.coefficients
                attempted = true
                observe("AggregateExtendedAttempts", 1L)
                observe(if (terms.size == 3) "AggregateThreeRowAttempts" else "AggregateFourRowAttempts", 1L)
                val cut = deriveWithCoefficients(terms, cancellation, charge, limits, allowMixed = true,
                    continuousBounds = continuousBounds, coefficientSnapshots = snapshots, capture = capture)
                    ?: continue
                val separating = separates(cut, point, meter, observe) ?: return null
                if (separating) {
                    observe("AggregateExtendedEligible", 1L)
                    return cut
                }
            }
        }

        private fun extendInteger(
            seed: List<LinearAuthorityAggregateTerm>, seedCoefficients: List<Map<SourceCoordinate, BigFraction>>,
        ): LinearAuthorityCut? {
            val initial = capture.coefficients ?: return null
            meter.reserve(seed.size * 2L + initial.size, 512L + limits.rows * 32L + initial.size * 64L)
            val terms = ArrayList<LinearAuthorityAggregateTerm>(limits.rows).also { it.addAll(seed) }
            val snapshots = ArrayList<Map<SourceCoordinate, BigFraction>>(limits.rows).also {
                it.addAll(seedCoefficients)
            }
            val aggregate = LinkedHashMap(initial)
            fun scaled(value: BigFraction, weight: BigFraction): BigFraction {
                meter.reserve(1L)
                val checked = meter.number(value)
                return if (weight == BigFraction.ONE) checked else meter.multiply(checked, weight)
            }
            var rhs = BigFraction.ZERO
            for (term in seed) rhs = meter.add(rhs, scaled(term.inequality.rhs, term.weight))
            var strict = seed.any { it.inequality.strict }
            val support = HashSet<SourceCoordinate>()
            fun retainSupport(row: Map<SourceCoordinate, BigFraction>) {
                for (id in row.keys) {
                    meter.reserve(1L)
                    if (id in support) continue
                    if (support.size >= limits.support) stopAggregate("SupportLimit")
                    meter.reserve(1L, 64L)
                    support.add(id)
                }
            }
            for (row in snapshots) retainSupport(row)
            while (true) {
                halfInteger()?.let { return it }
                if (terms.size >= limits.rows) return null
                val next = continuation(terms, aggregate, snapshots, support, integerProjection = true) ?: return null
                meter.arithmetic(next.residual, next.pivot)
                val weight = meter.number(next.residual.negated() * next.pivot.reciprocal())
                meter.reserve(2L, 64L)
                terms.add(next.row.term.copy(weight = weight))
                snapshots.add(next.row.coefficients)
                retainSupport(next.row.coefficients)
                for ((id, value) in next.row.coefficients) {
                    val current = aggregate[id]
                    meter.reserve(1L, if (current == null) 64L else 0L)
                    val product = scaled(value, weight)
                    val updated = if (current == null) product else meter.add(current, product)
                    if (updated.isZero) aggregate.remove(id) else aggregate[id] = updated
                }
                rhs = meter.add(rhs, scaled(next.row.term.inequality.rhs, weight))
                strict = strict || next.row.term.inequality.strict
                capture.coefficients = aggregate
                capture.integer = null
                observe("AggregateIntegerProjectionRows", 1L)
                meter.reserve(aggregate.size.toLong())
                if (aggregate.keys.any { it.kind == SourceCoordinate.Kind.Real }) continue
                observe("AggregateIntegerProjectionCompleted", 1L)
                observe("AggregateIntegerProjectionFinalizationAllocationAvailable", meter.remainingAllocation)
                if (aggregate.isEmpty()) return null
                val proofBytes = aggregateProofBytes(terms, meter, limits)
                val cut = finalizeIntegerAggregate(terms, aggregate, rhs, strict, proofBytes,
                    meter, limits, observe, capture)
                observe("AggregateIntegerProjectionFinalized", 1L)
                if (cut != null && separates(cut, point, meter, observe) == true) return cut
            }
        }
    }

    fun inspectPairs(
        rows: List<LinearAuthorityAggregateTerm>, inactive: Set<Int>,
        point: Map<SourceCoordinate, BigFraction>, cancellation: ExecutionControl,
        observe: (String, Long) -> Unit = { _, _ -> },
        limits: LinearAuthorityAggregateLimits = LinearAuthorityAggregateLimits(),
        maximumCandidates: Int = 64,
        allowMixed: Boolean = false,
    ): LinearAuthorityAggregateProbeResult {
        require(maximumCandidates >= 0)
        val meter = AggregateMeter(limits, cancellation, observe)
        val candidates = ArrayList<LinearAuthorityCut>()
        val violations = ArrayList<BigFraction>()
        var pointKnown = true
        var pointSatisfied = true
        var maximumResidual: BigFraction? = null
        var sourceScanComplete = false
        var candidatesKnown = true
        var eligibilityKnown = true
        var complete = false
        fun residual(row: SourceInequality, coefficients: Map<SourceCoordinate, BigFraction>): BigFraction? {
            var activity = BigFraction.ZERO
            for ((id, value) in coefficients) {
                val coordinate = point[id] ?: return null
                activity = meter.add(activity, meter.multiply(meter.number(value), meter.number(coordinate)))
            }
            return meter.add(activity, meter.number(row.rhs).negated())
        }
        try {
            if (point.isEmpty()) stopAggregate("PointUnavailable")
            meter.reserve(rows.size.toLong(), rows.size * 64L)
            val continuous = ArrayList<ProbeRow>()
            val bounds = HashMap<Pair<SourceCoordinate, Boolean>, LinearAuthorityAggregateTerm>()
            for (term in rows) {
                meter.reserve(1L)
                if (term.owner in inactive) continue
                if (term.weight != BigFraction.ONE) stopAggregate("ProbeSourceWeight")
                val row = term.inequality
                if (row.supportSize > limits.support) {
                    observe("AggregateProbeSupportLimit", 1L)
                    pointKnown = false
                    eligibilityKnown = false
                    continue
                }
                meter.reserve(row.supportSize.toLong(), row.supportSize * 64L)
                val coefficients = row.coefficients
                val value = residual(row, coefficients)
                if (value == null) pointKnown = false else {
                    maximumResidual = maximumResidual?.let { if (it > value) it else value } ?: value
                    if (value > BigFraction.ZERO || (row.strict && value.isZero)) pointSatisfied = false
                }
                observe("AggregateProbeSourceRows", 1L)
                if (allowMixed && coefficients.size == 1) {
                    val (id, coefficient) = coefficients.entries.single()
                    if (id.kind == SourceCoordinate.Kind.Real) {
                        meter.reserve(1L, 96L)
                        bounds.getOrPut(id to (coefficient > BigFraction.ZERO)) {
                            observe("AggregateProbeBoundsIndexed", 1L)
                            term
                        }
                    }
                }
                if (coefficients.keys.any { it.kind == SourceCoordinate.Kind.Real } &&
                    coefficients.keys.none { it.kind == SourceCoordinate.Kind.Boolean }) {
                    continuous += ProbeRow(term, coefficients)
                }
            }
            sourceScanComplete = true
            val charge: (String, Long) -> Unit = { stage, count ->
                when (stage) {
                    "AggregateWork" -> meter.reserve(count)
                    "AggregateAllocation" -> meter.reserve(0L, count)
                    else -> {
                        if (stage.endsWith("Limit") || stage == "AggregateStopped" ||
                            stage.endsWith("Identity")) eligibilityKnown = false
                        observe(stage, count)
                    }
                }
            }
            // Nested charges reach the containing work meter through charge.
            val derivedStop = object : ExecutionControl() {
                override fun isCancelled(): Boolean = cancellation()
                override fun deadline() = cancellation.deadline()
            }
            for (left in continuous.indices) {
                for (right in left + 1 until continuous.size) {
                    meter.reserve(1L)
                    observe("AggregateProbePairs", 1L)
                    val a = continuous[left]
                    val b = continuous[right]
                    if ((!a.hasInteger && !b.hasInteger) || (!allowMixed && a.realCount != b.realCount)) continue
                    meter.reserve(1L, 64L + a.realCount * 16L)
                    val pivots = ArrayList<Map.Entry<SourceCoordinate, BigFraction>>()
                    for (entry in a.coefficients.entries) {
                        meter.reserve(1L)
                        val other = b.coefficients[entry.key]
                        if (entry.key.kind == SourceCoordinate.Kind.Real && other != null &&
                            (entry.value > BigFraction.ZERO) != (other > BigFraction.ZERO)) {
                            pivots += entry
                            if (!allowMixed) break
                        }
                    }
                    for (pivot in pivots) {
                        meter.reserve(1L, 256L)
                        observe("AggregateProbePivots", 1L)
                        val other = b.coefficients.getValue(pivot.key)
                        val aWeight = if (other > BigFraction.ZERO) other else other.negated()
                        val bWeight = if (pivot.value > BigFraction.ZERO) pivot.value else pivot.value.negated()
                        val cut = derive(listOf(a.term.copy(weight = aWeight), b.term.copy(weight = bWeight)),
                            derivedStop, charge, limits, allowMixed, bounds) ?: continue
                        val violation = residual(cut.inequality, cut.inequality.coefficients)
                        if (violation == null) {
                            candidatesKnown = false
                            observe("AggregateProbePointUnavailable", 1L)
                            continue
                        }
                        if (violation <= BigFraction.ZERO) continue
                        observe("AggregateProbePositiveViolations", 1L)
                        if (candidates.size >= maximumCandidates) stopAggregate("ProbeRetentionLimit")
                        meter.reserve(1L + cut.inequality.supportSize, 128L + cut.inequality.supportSize * 64L)
                        candidates += cut
                        violations += violation
                    }
                }
            }
            meter.reserve(1L)
            complete = candidatesKnown && eligibilityKnown
        } catch (stop: AggregateStop) {
            observe("AggregateProbe${stop.reason}", 1L)
        }
        return LinearAuthorityAggregateProbeResult(candidates.toList(), violations.toList(),
            if (!pointSatisfied) false else if (pointKnown && sourceScanComplete) true else null,
            maximumResidual, complete)
    }

    fun derive(
        terms: List<LinearAuthorityAggregateTerm>, cancellation: ExecutionControl,
        observe: (String, Long) -> Unit = { _, _ -> },
        limits: LinearAuthorityAggregateLimits = LinearAuthorityAggregateLimits(),
        allowMixed: Boolean = false,
        continuousBounds: Map<Pair<SourceCoordinate, Boolean>, LinearAuthorityAggregateTerm> = emptyMap(),
    ): LinearAuthorityCut? = deriveWithCoefficients(terms, cancellation, observe, limits, allowMixed,
        continuousBounds, coefficientSnapshots = null)
}
