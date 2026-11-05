package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.cut.AggregationMirSeparator
import com.eignex.klause.lp.cut.CutPool
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRoundingLimit
import com.eignex.klause.solver.evidence.mixedIntegerRounding
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.abs
import com.eignex.klause.util.div
import com.eignex.klause.util.gcd
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.times
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
        source: SourceProofContext, cancellation: Cancellation,
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
            val derivedStop = object : Cancellation {
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
            var unavailableBasePairs: HashSet<Long>? = null
            fun preparePairs(
                pairMeter: AggregateMeter, pairCharge: (String, Long) -> Unit, pairExtension: PairExtension?,
                firstOnly: Boolean = false, allocationFloor: Long = 0L,
            ): Boolean {
                for (row in rows) {
                    pairMeter.reserve(1L)
                    if (!row.hasInteger) continue
                    pairMeter.reserve(1L, 128L)
                    val paired = HashSet<Int>()
                    for ((id, coefficient) in row.coefficients) {
                        pairMeter.reserve(1L)
                        if (id.kind != SourceCoordinate.Kind.Real) continue
                        for (other in incidence[id to (coefficient < BigFraction.ZERO)].orEmpty()) {
                            pairMeter.reserve(1L)
                            if (other.hasInteger && other.term.owner <= row.term.owner) continue
                            pairMeter.reserve(1L, 64L)
                            if (!paired.add(other.term.owner)) continue
                            if (allocationFloor > 0L && pairMeter.remainingAllocation <= allocationFloor) {
                                stopAggregate("AllocationLimit")
                            }
                            val unavailable = unavailableBasePairs
                            if (pairExtension != null && unavailable != null) {
                                pairMeter.reserve(1L, 32L)
                                val key = (row.term.owner.toLong() shl 32) or other.term.owner.toLong()
                                if (key in unavailable) {
                                    observe("AggregatePairPointUnavailableReuse", 1L)
                                    continue
                                }
                            }
                            if (candidates.size >= maximumCandidates) stopAggregate("RetentionLimit")
                            observe("AggregatePairAttempts", 1L)
                            val pivot = other.coefficients.getValue(id)
                            val rightWeight = pairMeter.number(if (coefficient > BigFraction.ZERO) coefficient
                                else coefficient.negated())
                            val leftWeight = pairMeter.number(if (pivot > BigFraction.ZERO) pivot else pivot.negated())
                            pairMeter.reserve(1L, 320L)
                            val terms = listOf(row.term.copy(weight = leftWeight),
                                other.term.copy(weight = rightWeight))
                            val snapshots = listOf(row.coefficients, other.coefficients)
                            var cut = deriveWithCoefficients(terms, derivedStop, pairCharge, attemptLimits,
                                allowMixed = true, continuousBounds = selectedBounds,
                                coefficientSnapshots = snapshots, capture = pairExtension?.capture)
                            if (point != null) {
                                val initial = cut
                                val separating = if (initial == null) false
                                    else separates(initial, point, pairMeter, observe)
                                if (separating == null) {
                                    eligibilityKnown = false
                                    if (pairExtension == null) {
                                        val unavailable = unavailableBasePairs ?: run {
                                            pairMeter.reserve(1L, 128L)
                                            HashSet<Long>().also { unavailableBasePairs = it }
                                        }
                                        pairMeter.reserve(1L, 64L)
                                        unavailable += (row.term.owner.toLong() shl 32) or other.term.owner.toLong()
                                    }
                                    continue
                                }
                                if (!separating) {
                                    if (pairExtension == null || candidates.isNotEmpty()) continue
                                    cut = pairExtension.extend(terms, snapshots)
                                    if (pairExtension.attempted) eligibilityKnown = false
                                }
                            }
                            val retained = cut ?: continue
                            pairMeter.reserve(1L, 128L + retained.inequality.supportSize * 64L)
                            candidates += retained
                            if (firstOnly) return false
                        }
                    }
                }
                return true
            }
            if (point != null && candidates.isEmpty()) {
                meter.reserve(1L, 128L)
                val baseLimits = attemptLimits.copy(work = minOf(attemptLimits.work, meter.remainingWork / 2L),
                    allocation = minOf(attemptLimits.allocation, meter.remainingAllocation))
                val baseMeter = AggregateMeter(baseLimits, derivedStop, charge)
                val baseCharge: (String, Long) -> Unit = { name, count ->
                    when (name) {
                        "AggregateWork" -> baseMeter.reserve(count)
                        "AggregateAllocation" -> baseMeter.reserve(0L, count)
                        else -> charge(name, count)
                    }
                }
                try {
                    val complete = preparePairs(baseMeter, baseCharge, null, firstOnly = true,
                        allocationFloor = baseLimits.allocation / 2L)
                    observe(if (complete) "AggregatePairBaseComplete" else "AggregatePairBaseYielded", 1L)
                } catch (stop: AggregateStop) {
                    eligibilityKnown = false
                    observe("AggregatePairBase" + stop.reason, 1L)
                }
                if (candidates.isNotEmpty()) {
                    observe("AggregatePairBaseRetained", candidates.size.toLong())
                    observe("AggregatePairIncomplete", 1L)
                    return candidates.toList()
                }
            }
            preparePairs(meter, charge, extension)
            observe(if (eligibilityKnown) "AggregatePairComplete" else "AggregatePairIncomplete", 1L)
        } catch (stop: AggregateStop) {
            observe("AggregatePair${stop.reason}", 1L)
        }
        return candidates.toList()
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
        val hasInteger = coefficients.keys.any { it.kind == SourceCoordinate.Kind.Integer }
    }

    private fun integerScale(
        values: Collection<BigFraction>, meter: AggregateMeter, observe: (String, Long) -> Unit,
    ): BigFraction {
        meter.reserve(values.size.toLong(), 64L)
        var allIntegral = true
        var containsUnit = false
        for (value in values) {
            if (value.den != BIG_ONE) allIntegral = false
            if (value == BigFraction.ONE || value == BigFraction.MINUS_ONE) containsUnit = true
        }
        if (allIntegral && containsUnit) {
            val scale = meter.number(BigFraction.ONE)
            observe("AggregateUnitScaleNormalization", 1L)
            return scale
        }
        var denominator = BIG_ONE
        for (value in values) {
            meter.arithmetic(BigFraction.of(denominator, BIG_ONE), value)
            denominator = denominator / denominator.gcd(value.den) * value.den
            meter.number(BigFraction.of(denominator, BIG_ONE))
        }
        var divisor = BIG_ZERO
        for (value in values) {
            meter.arithmetic(BigFraction.of(denominator, BIG_ONE), value)
            val integral = value.num * (denominator / value.den)
            meter.number(BigFraction.of(integral, BIG_ONE))
            meter.arithmetic(BigFraction.of(divisor, BIG_ONE), BigFraction.of(integral, BIG_ONE))
            divisor = divisor.gcd(integral.abs())
        }
        return meter.number(BigFraction.of(denominator, divisor))
    }

    private class IntegerAggregate(
        val coefficients: Map<SourceCoordinate, BigFraction>, val rhs: BigFraction, val strict: Boolean,
        val combination: SourceDerivation.Combination, val terms: List<LinearAuthorityAggregateTerm>,
        val proofBytes: Long,
    )

    private class HalfIntegerCut(
        val cut: LinearAuthorityCut, val coefficients: Map<SourceCoordinate, BigFraction>,
        val rhs: BigFraction, val strictSources: Int,
    )

    private class AggregateCapture {
        var coefficients: Map<SourceCoordinate, BigFraction>? = null
        var integer: IntegerAggregate? = null
    }

    private fun halfHasNoIntegerActivity(
        aggregate: IntegerAggregate,
        bounds: IntegerBoundIndex,
        meter: AggregateMeter, observe: (String, Long) -> Unit, upperChoices: Set<SourceCoordinate>,
    ): Boolean {
        for ((id, value) in aggregate.coefficients) {
            meter.reserve(1L)
            observe("AggregateHalfPreviewCoordinates", 1L)
            if (id.kind != SourceCoordinate.Kind.Integer ||
                (value != BigFraction.ONE && value != BigFraction.MINUS_ONE)) return false
            val lower = bounds.lookup(id, false, meter)
            val upper = lower == null || id in upperChoices
            if (upper) {
                if (bounds.lookup(id, true, meter) == null || value != BigFraction.MINUS_ONE) return false
            } else if (value != BigFraction.ONE) return false
        }
        return true
    }

    private fun halfIntegerCut(
        aggregate: IntegerAggregate,
        bounds: IntegerBoundIndex,
        cancellation: Cancellation, observe: (String, Long) -> Unit, limits: LinearAuthorityAggregateLimits,
        upperChoices: Set<SourceCoordinate> = emptySet(),
    ): HalfIntegerCut? {
        val meter = AggregateMeter(limits, cancellation, observe)
        observe("AggregateHalfAttempts", 1L)
        return try {
            meter.reserve(1L, 64L)
            if (halfHasNoIntegerActivity(aggregate, bounds, meter, observe, upperChoices)) {
                observe("AggregateHalfPreviewNoIntegerActivity", 1L)
                observe("AggregateHalfNoIntegerActivity", 1L)
                return null
            }
            meter.reserve(1L, 320L + aggregate.coefficients.size * 160L + aggregate.terms.size * 64L)
            var proofBytes = aggregate.proofBytes + 256L
            if (proofBytes > limits.proofBytes) stopAggregate("ProofLimit")
            val half = meter.number(BigFraction.ofLong(2).reciprocal())
            val coefficients = LinkedHashMap<SourceCoordinate, BigFraction>()
            val proofTerms = arrayListOf(SourceDerivation.Combination.Term(half, aggregate.combination))
            val owners = aggregate.terms.mapTo(linkedSetOf()) { it.owner }
            var rhs = meter.multiply(aggregate.rhs, half)
            var strictSources = if (aggregate.strict) 1 else 0
            for ((id, original) in aggregate.coefficients) {
                if (id.kind != SourceCoordinate.Kind.Integer || original.den != BIG_ONE) {
                    stopAggregate("IntegerActivity")
                }
                var value = meter.multiply(original, half)
                if (value.den != BIG_ONE) {
                    val lower = bounds.lookup(id, false, meter)
                    val upper = lower == null || id in upperChoices
                    val bound = (if (upper) bounds.lookup(id, true, meter) else lower)
                        ?: stopAggregate("MissingIntegerBounds")
                    val bytes = 128L + bound.premise.length * 2L + (limits.scalarBits + 7L) / 4L
                    if (bytes > limits.proofBytes - proofBytes) stopAggregate("ProofLimit")
                    proofBytes += bytes
                    meter.reserve(bound.premise.length.toLong() + 1L, bytes)
                    if (bound.owner < 0 || bound.premise.isBlank() || bound.weight != BigFraction.ONE ||
                        bound.inequality.supportSize != 1) stopAggregate("BoundIdentity")
                    val (coordinate, coefficient) = bound.inequality.coefficients.entries.single()
                    if (coordinate != id || (coefficient > BigFraction.ZERO) != upper) {
                        stopAggregate("BoundIdentity")
                    }
                    meter.arithmetic(value, BigFraction.ONE)
                    val rounded = meter.number(if (upper) BigFraction.of(value.ceilInteger(), BIG_ONE)
                        else BigFraction.of(value.negated().ceilInteger(), BIG_ONE).negated())
                    val delta = meter.add(rounded, meter.number(value.negated()))
                    meter.number(coefficient)
                    meter.arithmetic(BigFraction.ONE, coefficient)
                    val weight = meter.multiply(delta, meter.number(coefficient.reciprocal()))
                    if (weight <= BigFraction.ZERO) stopAggregate("BoundIdentity")
                    rhs = meter.add(rhs, meter.multiply(weight, meter.number(bound.inequality.rhs)))
                    if (bound.inequality.strict) strictSources++
                    proofTerms += SourceDerivation.Combination.Term(weight, SourceDerivation.Premise(bound.premise))
                    owners += bound.owner
                    value = rounded
                }
                coefficients[id] = value
            }
            if (coefficients.values.all { it.isZero }) {
                observe("AggregateHalfNoIntegerActivity", 1L)
                return null
            }
            meter.arithmetic(rhs, BigFraction.ONE)
            val rounded = meter.number(if (strictSources > 0)
                BigFraction.of(rhs.ceilInteger(), BIG_ONE) - BigFraction.ONE
                else BigFraction.of(rhs.negated().ceilInteger(), BIG_ONE).negated())
            meter.reserve(1L)
            val proof = SourceDerivation.IntegerRounding(SourceDerivation.Combination(proofTerms))
            observe("AggregateCandidates", 1L)
            observe("AggregateHalfCandidates", 1L)
            val cut = LinearAuthorityCut(aggregate.terms.first().owner, SourceInequality(coefficients, rounded), proof,
                owners = owners, family = CutFamily.AGGREGATION_MIR)
            HalfIntegerCut(cut, coefficients, rhs, strictSources)
        } catch (stop: AggregateStop) {
            observe("AggregateHalf${stop.reason}", 1L)
            null
        } catch (_: SourceRoundingLimit) {
            observe("AggregateHalfScalarLimit", 1L)
            null
        }
    }

    private class IntegerBoundIndex(private val source: SourceProofContext) {
        private class CompletedBatch(
            val coordinates: Set<SourceCoordinate>,
            val bounds: Map<Pair<SourceCoordinate, Boolean>, LinearAuthorityAggregateTerm>,
        )
        private var completed: MutableList<CompletedBatch>? = null
        private var singletonCoordinates: Array<SourceCoordinate?>? = null

        fun prepare(
            coefficients: Map<SourceCoordinate, BigFraction>, meter: AggregateMeter,
            observe: (String, Long) -> Unit,
        ) {
            meter.reserve(coefficients.size.toLong(), 128L + coefficients.size * 64L)
            val missing = HashSet<SourceCoordinate>()
            for (id in coefficients.keys) {
                val batches = completed
                var index = (batches?.size ?: 0) - 1
                while (index >= 0) {
                    meter.reserve(1L)
                    if (id in checkNotNull(batches)[index].coordinates) break
                    index--
                }
                if (index < 0) missing += id
            }
            if (missing.isEmpty()) return
            observe("AggregateIntegerBoundScanAttempts", 1L)
            meter.reserve(1L, 128L)
            val pending = HashMap<Pair<SourceCoordinate, Boolean>, LinearAuthorityAggregateTerm>()
            val saved = singletonCoordinates
            val snapshot = saved ?: run {
                meter.reserve(source.premiseCount.toLong(), 128L + source.premiseCount * 8L)
                arrayOfNulls<SourceCoordinate>(source.premiseCount)
            }
            if (saved != null) observe("AggregateIntegerBoundSnapshotReuseAttempts", 1L)
            meter.reserve(1L, 64L)
            var ordinal = 0
            source.forEachPremise { name, premise ->
                val owner = ordinal++
                meter.reserve(1L)
                val inequality = premise.inequality
                val id = if (saved != null) {
                    saved[owner] ?: return@forEachPremise
                } else {
                    if (inequality.supportSize != 1) return@forEachPremise
                    meter.reserve(1L, 48L)
                    val inspected = checkNotNull(inequality.singleCoordinate())
                    snapshot[owner] = inspected
                    inspected
                }
                if (id.kind != SourceCoordinate.Kind.Integer || id !in missing) return@forEachPremise
                meter.reserve(1L)
                val coefficient = checkNotNull(inequality.coefficient(id))
                meter.reserve(1L, 64L)
                val side = id to (coefficient > BigFraction.ZERO)
                if (side in pending) return@forEachPremise
                meter.reserve(1L, 256L + name.length * 2L)
                pending[side] = LinearAuthorityAggregateTerm(owner, name, inequality)
                observe("AggregateIntegerBoundRetained", 1L)
            }
            observe("AggregateIntegerBoundSourceScanComplete", 1L)
            val size = completed?.size ?: 0
            meter.reserve(size + 1L, 256L + (size + 1L) * 32L)
            val batches = completed ?: ArrayList<CompletedBatch>()
            batches.add(CompletedBatch(missing, pending))
            completed = batches
            if (saved == null) {
                singletonCoordinates = snapshot
                observe("AggregateIntegerBoundSnapshotComplete", 1L)
            }
            observe("AggregateIntegerBoundCoordinates", missing.size.toLong())
            observe("AggregateIntegerBoundScanComplete", 1L)
        }

        fun lookup(
            id: SourceCoordinate, upper: Boolean, meter: AggregateMeter,
        ): LinearAuthorityAggregateTerm? {
            val batches = completed ?: return null
            meter.reserve(1L, 64L)
            val side = id to upper
            var index = batches.lastIndex
            while (index >= 0) {
                meter.reserve(1L)
                batches[index].bounds[side]?.let { return it }
                index--
            }
            return null
        }
    }

    private class PairExtension(
        private val incidence: Map<Pair<SourceCoordinate, Boolean>, List<ProbeRow>>,
        private val continuousBounds: Map<Pair<SourceCoordinate, Boolean>, LinearAuthorityAggregateTerm>,
        private val preferredBounds: Map<Pair<SourceCoordinate, Boolean>, LinearAuthorityAggregateTerm>,
        private val point: Map<SourceCoordinate, Double>,
        private val meter: AggregateMeter,
        private val limits: LinearAuthorityAggregateLimits,
        private val cancellation: Cancellation,
        private val observe: (String, Long) -> Unit,
        private val integerBounds: IntegerBoundIndex,
    ) {
        private class Continuation(val row: ProbeRow, val residual: BigFraction, val pivot: BigFraction)
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
        ): Continuation? {
            for ((id, value) in aggregate) {
                meter.reserve(1L)
                if (id.kind != SourceCoordinate.Kind.Real) continue
                meter.reserve(1L, 64L)
                var first: ProbeRow? = null
                var selected: ProbeRow? = null
                var best = Double.POSITIVE_INFINITY
                for (other in incidence[id to (value < BigFraction.ZERO)].orEmpty()) {
                    meter.reserve(1L + terms.size)
                    if (other.coefficients.size <= 1 || terms.any { it.owner == other.term.owner }) continue
                    if (first == null) first = other
                    attempted = true
                    val score = continuationScore(other, other.coefficients.getValue(id))
                    if (score == null) {
                        observe("AggregateContinuationPointUnavailable", 1L)
                    } else {
                        observe("AggregateContinuationScored", 1L)
                        if (score < best) {
                            best = score
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

        private fun continuationScore(row: ProbeRow, pivot: BigFraction): Double? {
            meter.reserve(4L, 128L)
            val divisor = abs(meter.number(pivot).toDouble())
            if (!divisor.isFinite() || divisor == 0.0) return null
            var slack = meter.number(row.term.inequality.rhs).toDouble()
            for ((id, value) in row.coefficients) {
                meter.reserve(4L)
                val coordinate = point[id]?.takeIf { it.isFinite() } ?: return null
                val coefficient = meter.number(value).toDouble()
                if (!coefficient.isFinite()) return null
                slack -= coefficient * coordinate
            }
            meter.reserve(4L)
            val score = slack / divisor
            return score.takeIf { it.isFinite() }
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
            fun distance(bound: LinearAuthorityAggregateTerm, coordinate: Double): Double {
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
            fun contribution(term: LinearAuthorityAggregateTerm, delta: BigFraction): BigFraction {
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
        ): LinearAuthorityCut? {
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
                val next = continuation(terms, aggregate) ?: return null
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
    }

    fun inspectPairs(
        rows: List<LinearAuthorityAggregateTerm>, inactive: Set<Int>,
        point: Map<SourceCoordinate, BigFraction>, cancellation: Cancellation,
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
            val derivedStop = object : Cancellation {
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
        terms: List<LinearAuthorityAggregateTerm>, cancellation: Cancellation,
        observe: (String, Long) -> Unit = { _, _ -> },
        limits: LinearAuthorityAggregateLimits = LinearAuthorityAggregateLimits(),
        allowMixed: Boolean = false,
        continuousBounds: Map<Pair<SourceCoordinate, Boolean>, LinearAuthorityAggregateTerm> = emptyMap(),
    ): LinearAuthorityCut? = deriveWithCoefficients(terms, cancellation, observe, limits, allowMixed,
        continuousBounds, coefficientSnapshots = null)

    private fun deriveWithCoefficients(
        terms: List<LinearAuthorityAggregateTerm>, cancellation: Cancellation,
        observe: (String, Long) -> Unit,
        limits: LinearAuthorityAggregateLimits,
        allowMixed: Boolean,
        continuousBounds: Map<Pair<SourceCoordinate, Boolean>, LinearAuthorityAggregateTerm>,
        coefficientSnapshots: List<Map<SourceCoordinate, BigFraction>>?,
        capture: AggregateCapture? = null,
    ): LinearAuthorityCut? {
        val meter = AggregateMeter(limits, cancellation, observe)
        capture?.coefficients = null
        capture?.integer = null
        observe("AggregateAttempts", 1L)
        return try {
            meter.reserve(1L)
            if (terms.size !in 2..limits.rows) stopAggregate("RowLimit")
            meter.reserve(terms.size.toLong(), terms.size * 128L)
            val identityCharacters = terms.sumOf { it.premise.length.toLong() }
            var proofBytes = identityCharacters * 2L + terms.size * (128L + (limits.scalarBits + 7L) / 4L)
            if (proofBytes > limits.proofBytes) stopAggregate("ProofLimit")
            meter.reserve(identityCharacters, proofBytes)
            if (terms.any { it.owner < 0 || it.premise.isBlank() } ||
                terms.map { it.owner }.distinct().size != terms.size ||
                terms.map { it.premise }.distinct().size != terms.size) stopAggregate("SourceIdentity")
            val coefficients = linkedMapOf<SourceCoordinate, BigFraction>()
            var rhs = BigFraction.ZERO
            var strict = false
            for ((index, term) in terms.withIndex()) {
                val weight = meter.number(term.weight)
                if (weight <= BigFraction.ZERO) stopAggregate("NonpositiveWeight")
                if (term.inequality.supportSize > limits.support) stopAggregate("SupportLimit")
                val row = coefficientSnapshots?.get(index) ?: term.inequality.coefficients
                meter.reserve(row.size.toLong(), if (coefficientSnapshots == null) row.size * 64L else 0L)
                for ((id, value) in row) {
                    if (id.kind == SourceCoordinate.Kind.Boolean) stopAggregate("BooleanActivity")
                    if (id !in coefficients && coefficients.size >= limits.support) {
                        stopAggregate("SupportLimit")
                    }
                    val product = meter.multiply(weight, meter.number(value))
                    coefficients[id] = meter.add(coefficients[id] ?: BigFraction.ZERO, product)
                }
                rhs = meter.add(rhs, meter.multiply(weight, meter.number(term.inequality.rhs)))
                strict = strict || term.inequality.strict
            }
            val activity = coefficients.filterValues { !it.isZero }
            capture?.coefficients = activity
            val integerValues = activity.filterKeys { it.kind == SourceCoordinate.Kind.Integer }.values
            if (integerValues.isEmpty()) {
                observe("AggregateNoIntegerActivity", 1L)
                return null
            }
            val mixed = activity.keys.any { it.kind == SourceCoordinate.Kind.Real }
            if (mixed && !allowMixed) {
                observe("AggregateContinuousResidual", 1L)
                return null
            }
            val scale = integerScale(integerValues, meter, observe)
            val normalized = activity.mapValues { (_, value) -> meter.multiply(value, scale) }
            val scaledRhs = meter.multiply(rhs, scale)
            meter.reserve(terms.size.toLong() + normalized.size, terms.size * 128L + normalized.size * 64L)
            val combination = SourceDerivation.Combination(terms.map {
                SourceDerivation.Combination.Term(meter.multiply(it.weight, scale),
                    SourceDerivation.Premise(it.premise))
            })
            if (capture != null && !mixed) {
                meter.reserve(1L, 128L)
                capture.integer = IntegerAggregate(normalized, scaledRhs, strict, combination, terms, proofBytes)
            }
            if (mixed) {
                for (id in normalized.keys.filter { it.kind == SourceCoordinate.Kind.Real }) {
                    meter.reserve(1L)
                    if ((id to true) !in continuousBounds && (id to false) !in continuousBounds) {
                        stopAggregate("MissingBounds")
                    }
                }
                val sides = normalized.filterKeys { it.kind == SourceCoordinate.Kind.Real }.map { (id, value) ->
                    meter.reserve(1L)
                    val preferred = id to (value > BigFraction.ZERO)
                    val side = if (preferred in continuousBounds) preferred else id to (value < BigFraction.ZERO)
                    val bound = continuousBounds[side] ?: stopAggregate("MissingBounds")
                    val bytes = 128L + bound.premise.length * 2L + (limits.scalarBits + 7L) / 4L
                    if (bytes > limits.proofBytes - proofBytes) stopAggregate("ProofLimit")
                    proofBytes += bytes
                    meter.reserve(bound.premise.length.toLong() + 1L, bytes)
                    if (bound.owner < 0 || bound.premise.isBlank() || bound.weight != BigFraction.ONE ||
                        bound.inequality.supportSize != 1) stopAggregate("BoundIdentity")
                    val (coordinate, coefficient) = bound.inequality.coefficients.entries.single()
                    if (coordinate != id || (coefficient > BigFraction.ZERO) != side.second) {
                        stopAggregate("BoundIdentity")
                    }
                    meter.number(coefficient)
                    meter.number(bound.inequality.rhs)
                    bound
                }
                val conclusion = mixedIntegerRounding(SourceInequality(normalized, scaledRhs, strict),
                    sides.map { it.inequality }, Cancellation { meter.reserve(1L); cancellation() }) { left, right ->
                    meter.number(left)
                    meter.number(right)
                    meter.arithmetic(left, right)
                }
                if (conclusion == null) {
                    observe("AggregateIntegralShiftedRhs", 1L)
                    return null
                }
                for (value in conclusion.coefficients.values + conclusion.rhs) meter.number(value)
                val proof = SourceDerivation.MixedIntegerRounding(combination,
                    sides.map { SourceDerivation.Premise(it.premise) })
                meter.reserve(1L)
                observe("AggregateCandidates", 1L)
                observe("AggregateMixedCandidates", 1L)
                return LinearAuthorityCut(terms.first().owner, conclusion, proof, mixed = true,
                    owners = (terms + sides).mapTo(linkedSetOf()) { it.owner }, family = CutFamily.SOURCE_AGGREGATION)
            }
            meter.reserve(1L + limits.scalarBits / 64L, 64L + limits.scalarBits / 4L)
            val rounded = meter.number(if (strict) {
                BigFraction.of(scaledRhs.ceilInteger(), BIG_ONE) - BigFraction.ONE
            } else BigFraction.of(scaledRhs.negated().ceilInteger(), BIG_ONE).negated())
            if (!strict && rounded == scaledRhs) {
                observe("AggregateIntegralRhs", 1L)
                return null
            }
            val proof = SourceDerivation.IntegerRounding(combination)
            meter.reserve(1L)
            observe("AggregateCandidates", 1L)
            LinearAuthorityCut(terms.first().owner, SourceInequality(normalized, rounded), proof,
                owners = terms.mapTo(linkedSetOf()) { it.owner }, family = CutFamily.SOURCE_AGGREGATION)
        } catch (stop: AggregateStop) {
            observe("Aggregate${stop.reason}", 1L)
            null
        } catch (_: SourceRoundingLimit) {
            observe("AggregateScalarLimit", 1L)
            null
        }
    }
}

private class AggregateMeter(
    private val limits: LinearAuthorityAggregateLimits,
    private val cancellation: Cancellation,
    private val observe: (String, Long) -> Unit,
) {
    private var work = 0L
    private var allocation = 0L
    val remainingWork: Long get() = limits.work - work
    val remainingAllocation: Long get() = limits.allocation - allocation

    fun reserve(units: Long, bytes: Long = 0L) {
        val reason = when {
            cancellation() -> "Stopped"
            units > limits.work - work -> "WorkLimit"
            bytes > limits.allocation - allocation -> "AllocationLimit"
            else -> null
        }
        if (reason != null) throw AggregateStop(reason)
        work += units
        allocation += bytes
        observe("AggregateWork", units)
        observe("AggregateAllocation", bytes)
        cancellation.charge(units)
        if (cancellation()) throw AggregateStop("Stopped")
    }

    fun number(value: BigFraction): BigFraction {
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
        reserve(1L + bits / 64L, 32L + bits / 4L)
        if (bits > limits.scalarBits) throw AggregateStop("ScalarLimit")
        return value
    }

    fun arithmetic(left: BigFraction, right: BigFraction) {
        val bits = maxOf(left.num.magnitudeBitLength(), left.den.magnitudeBitLength()).toLong() +
            maxOf(right.num.magnitudeBitLength(), right.den.magnitudeBitLength()) + 2L
        val words = (bits + 63L) / 64L
        reserve(1L + words * words, 64L + bits / 2L)
    }

    fun multiply(left: BigFraction, right: BigFraction): BigFraction {
        arithmetic(left, right)
        return number(left * right)
    }

    fun add(left: BigFraction, right: BigFraction): BigFraction {
        arithmetic(left, right)
        return number(left + right)
    }
}

private class AggregateStop(val reason: String) : RuntimeException()

private fun stopAggregate(reason: String): Nothing = throw AggregateStop(reason)
