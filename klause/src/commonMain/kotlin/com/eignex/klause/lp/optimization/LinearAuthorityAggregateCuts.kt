package com.eignex.klause.lp.optimization

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
    fun prepareSourcePairs(
        source: SourceProofContext, cancellation: Cancellation,
        observe: (String, Long) -> Unit = { _, _ -> }, maximumCandidates: Int = 64,
        limits: LinearAuthorityAggregateLimits = LinearAuthorityAggregateLimits(
            work = 500_000L, allocation = 8_388_608L),
        point: Map<SourceCoordinate, Double>? = null,
    ): List<LinearAuthorityCut> {
        require(maximumCandidates in 0..64)
        val meter = AggregateMeter(limits, cancellation, observe)
        val attemptLimits = limits.copy(work = minOf(limits.work, 65_536L),
            allocation = minOf(limits.allocation, 1_048_576L))
        val candidates = ArrayList<LinearAuthorityCut>()
        var eligibilityKnown = true
        try {
            meter.reserve(source.premises.size.toLong(), source.premises.size * 128L)
            val rows = ArrayList<ProbeRow>()
            val bounds = HashMap<Pair<SourceCoordinate, Boolean>, LinearAuthorityAggregateTerm>()
            val incidence = HashMap<Pair<SourceCoordinate, Boolean>, MutableList<ProbeRow>>()
            for ((owner, entry) in source.premises.entries.withIndex()) {
                meter.reserve(1L, 128L + entry.key.length * 2L)
                val term = LinearAuthorityAggregateTerm(owner, entry.key, entry.value.inequality)
                if (term.inequality.supportSize > limits.support) {
                    eligibilityKnown = false
                    observe("AggregatePairSourceSupportLimit", 1L)
                    continue
                }
                meter.reserve(term.inequality.supportSize * 3L, 96L + term.inequality.supportSize * 64L)
                val coefficients = term.inequality.coefficients
                if (coefficients.keys.any { it.kind == SourceCoordinate.Kind.Boolean }) continue
                val row = ProbeRow(term, coefficients)
                if (row.realCount == 0) continue
                rows += row
                for ((id, coefficient) in coefficients) {
                    meter.reserve(1L)
                    if (id.kind != SourceCoordinate.Kind.Real) continue
                    meter.reserve(1L, 160L)
                    incidence.getOrPut(id to (coefficient > BigFraction.ZERO)) { ArrayList() }.add(row)
                    if (coefficients.size == 1) {
                        meter.reserve(1L, 96L)
                        bounds.getOrPut(id to (coefficient > BigFraction.ZERO)) { term }
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
                        meter.reserve(1L, 256L)
                        val cut = derive(listOf(row.term.copy(weight = leftWeight),
                            other.term.copy(weight = rightWeight)), derivedStop, charge, attemptLimits,
                            allowMixed = true, continuousBounds = bounds) ?: continue
                        if (point != null && !separates(cut, point, meter, observe)) continue
                        meter.reserve(1L, 128L + cut.inequality.supportSize * 64L)
                        candidates += cut
                    }
                }
            }
            observe(if (eligibilityKnown) "AggregatePairComplete" else "AggregatePairIncomplete", 1L)
        } catch (stop: AggregateStop) {
            observe("AggregatePair${stop.reason}", 1L)
        }
        return candidates.toList()
    }

    private fun separates(
        cut: LinearAuthorityCut, point: Map<SourceCoordinate, Double>, meter: AggregateMeter,
        observe: (String, Long) -> Unit,
    ): Boolean {
        meter.reserve(1L, 128L + cut.inequality.supportSize * 64L)
        var activity = 0.0
        var normSquared = 0.0
        for ((id, value) in cut.inequality.coefficients) {
            meter.reserve(4L)
            val coordinate = point[id] ?: run {
                observe("AggregatePairPointUnavailable", 1L)
                return false
            }
            val coefficient = value.toDouble()
            activity += coefficient * coordinate
            normSquared += coefficient * coefficient
        }
        meter.reserve(1L)
        val efficacy = (activity - cut.inequality.rhs.toDouble()) / sqrt(normSquared)
        if (!efficacy.isFinite()) {
            observe("AggregatePairPointUnavailable", 1L)
            return false
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
    ): LinearAuthorityCut? {
        val meter = AggregateMeter(limits, cancellation, observe)
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
            for (term in terms) {
                val weight = meter.number(term.weight)
                if (weight <= BigFraction.ZERO) stopAggregate("NonpositiveWeight")
                if (term.inequality.supportSize > limits.support) stopAggregate("SupportLimit")
                val row = term.inequality.coefficients
                meter.reserve(row.size.toLong(), row.size * 64L)
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
            var denominator = BIG_ONE
            for (value in integerValues) {
                meter.arithmetic(BigFraction.of(denominator, BIG_ONE), value)
                denominator = denominator / denominator.gcd(value.den) * value.den
                meter.number(BigFraction.of(denominator, BIG_ONE))
            }
            var divisor = BIG_ZERO
            for (value in integerValues) {
                meter.arithmetic(BigFraction.of(denominator, BIG_ONE), value)
                val integral = value.num * (denominator / value.den)
                meter.number(BigFraction.of(integral, BIG_ONE))
                meter.arithmetic(BigFraction.of(divisor, BIG_ONE), BigFraction.of(integral, BIG_ONE))
                divisor = divisor.gcd(integral.abs())
            }
            val scale = meter.number(BigFraction.of(denominator, divisor))
            val normalized = activity.mapValues { (_, value) -> meter.multiply(value, scale) }
            val scaledRhs = meter.multiply(rhs, scale)
            meter.reserve(terms.size.toLong() + normalized.size, terms.size * 128L + normalized.size * 64L)
            val combination = SourceDerivation.Combination(terms.map {
                SourceDerivation.Combination.Term(meter.multiply(it.weight, scale),
                    SourceDerivation.Premise(it.premise))
            })
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
