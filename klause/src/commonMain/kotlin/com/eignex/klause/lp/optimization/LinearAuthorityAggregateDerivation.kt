package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRoundingLimit
import com.eignex.klause.solver.evidence.mixedIntegerRounding
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.div
import com.eignex.util.numbers.gcd
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.times
import kotlin.math.abs

internal fun integerScale(
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

internal class IntegerAggregate(
    val coefficients: Map<SourceCoordinate, BigFraction>, val rhs: BigFraction, val strict: Boolean,
    val combination: SourceDerivation.Combination, val terms: List<LinearAuthorityAggregateTerm>,
    val proofBytes: Long,
)

internal class HalfIntegerCut(
    val cut: LinearAuthorityCut, val coefficients: Map<SourceCoordinate, BigFraction>,
    val rhs: BigFraction, val strictSources: Int,
)

internal class AggregateCapture {
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

internal fun halfIntegerCut(
    aggregate: IntegerAggregate,
    bounds: IntegerBoundIndex,
    cancellation: ExecutionControl, observe: (String, Long) -> Unit, limits: LinearAuthorityAggregateLimits,
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
                val bytes = 128L + bound.identityBytes + (limits.scalarBits + 7L) / 4L
                if (bytes > limits.proofBytes - proofBytes) stopAggregate("ProofLimit")
                proofBytes += bytes
                meter.reserve(bound.identityBytes / 2L + 1L, bytes)
                if ((bound.owner == null && bound.derivation !is SourceDerivation.Assumption) ||
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
                proofTerms += SourceDerivation.Combination.Term(weight, bound.derivation)
                bound.owner?.let { owners += it }
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

internal class IntegerBound(
    val owner: Int?, val inequality: SourceInequality, val derivation: SourceDerivation,
    val identityBytes: Long,
)

internal class IntegerBoundIndex(
    private val source: SourceProofContext, knownSingletons: Array<SourceCoordinate?>? = null,
) {
    private class CompletedBatch(
        val coordinates: Set<SourceCoordinate>,
        val bounds: Map<Pair<SourceCoordinate, Boolean>, IntegerBound>,
    )
    private var completed: MutableList<CompletedBatch>? = null
    private var singletonCoordinates: Array<SourceCoordinate?>? = knownSingletons

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
        val pending = HashMap<Pair<SourceCoordinate, Boolean>, IntegerBound>()
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
            pending[side] = IntegerBound(owner, inequality, SourceDerivation.Premise(name), name.length * 2L)
            observe("AggregateIntegerBoundRetained", 1L)
        }
        observe("AggregateIntegerBoundSourceScanComplete", 1L)
        if (source.scope.condition.hasNumericBounds) for (id in missing) {
            meter.reserve(1L)
            if (id.kind != SourceCoordinate.Kind.Integer) continue
            for (upper in listOf(false, true)) {
                meter.reserve(2L, 64L)
                val value = source.scope.condition.boundValue(id, upper) ?: continue
                meter.number(value)
                val side = id to upper
                val original = pending[side]
                if (original != null) {
                    val coefficient = meter.number(checkNotNull(original.inequality.coefficient(id)))
                    val rhs = meter.number(original.inequality.rhs)
                    if (meter.multiply(coefficient, value) >= rhs) continue
                }
                meter.reserve(4L, 320L)
                val sign = if (upper) BigFraction.ONE else BigFraction.MINUS_ONE
                pending[side] = IntegerBound(null, SourceInequality(mapOf(id to sign),
                    meter.multiply(sign, value)), SourceDerivation.Assumption(id, upper), 32L)
                observe("AggregateIntegerBoundScopedRetained", 1L)
            }
        }
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
    ): IntegerBound? {
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

internal fun deriveWithCoefficients(
    terms: List<LinearAuthorityAggregateTerm>, cancellation: ExecutionControl,
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
        var proofBytes = aggregateProofBytes(terms, meter, limits)
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
        if (!mixed) return finalizeIntegerAggregate(terms, activity, rhs, strict, proofBytes,
            meter, limits, observe, capture)
        val scale = integerScale(integerValues, meter, observe)
        val normalized = activity.mapValues { (_, value) -> meter.multiply(value, scale) }
        val scaledRhs = meter.multiply(rhs, scale)
        meter.reserve(terms.size.toLong() + normalized.size, terms.size * 128L + normalized.size * 64L)
        val combination = SourceDerivation.Combination(terms.map {
            SourceDerivation.Combination.Term(meter.multiply(it.weight, scale),
                SourceDerivation.Premise(it.premise))
        })
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
            sides.map { it.inequality },
            ExecutionControl {
                meter.reserve(1L)
                cancellation()
            }) { left, right ->
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
        LinearAuthorityCut(terms.first().owner, conclusion, proof, mixed = true,
            owners = (terms + sides).mapTo(linkedSetOf()) { it.owner }, family = CutFamily.SOURCE_AGGREGATION)
    } catch (stop: AggregateStop) {
        observe("Aggregate${stop.reason}", 1L)
        null
    } catch (_: SourceRoundingLimit) {
        observe("AggregateScalarLimit", 1L)
        null
    }
}

internal fun aggregateProofBytes(
    terms: List<LinearAuthorityAggregateTerm>, meter: AggregateMeter, limits: LinearAuthorityAggregateLimits,
): Long {
    if (terms.size !in 2..limits.rows) stopAggregate("RowLimit")
    meter.reserve(terms.size.toLong(), terms.size * 128L)
    val identityCharacters = terms.sumOf { it.premise.length.toLong() }
    val proofBytes = identityCharacters * 2L + terms.size * (128L + (limits.scalarBits + 7L) / 4L)
    if (proofBytes > limits.proofBytes) stopAggregate("ProofLimit")
    meter.reserve(identityCharacters + terms.size, proofBytes)
    if (terms.any { it.owner < 0 || it.premise.isBlank() } ||
        terms.map { it.owner }.distinct().size != terms.size ||
        terms.map { it.premise }.distinct().size != terms.size) stopAggregate("SourceIdentity")
    if (terms.any { it.weight <= BigFraction.ZERO }) stopAggregate("NonpositiveWeight")
    return proofBytes
}

internal fun finalizeIntegerAggregate(
    terms: List<LinearAuthorityAggregateTerm>, activity: Map<SourceCoordinate, BigFraction>,
    rhs: BigFraction, strict: Boolean, proofBytes: Long, meter: AggregateMeter,
    limits: LinearAuthorityAggregateLimits, observe: (String, Long) -> Unit, capture: AggregateCapture?,
): LinearAuthorityCut? {
    meter.reserve(activity.size.toLong() + 1L)
    if (activity.isEmpty()) return null
    if (activity.keys.any { it.kind != SourceCoordinate.Kind.Integer }) stopAggregate("ContinuousResidual")
    val scale = integerScale(activity.values, meter, observe)
    meter.reserve(terms.size.toLong() + activity.size, terms.size * 128L + activity.size * 64L)
    val normalized = activity.mapValues { (_, value) -> meter.multiply(value, scale) }
    val scaledRhs = meter.multiply(rhs, scale)
    val combination = SourceDerivation.Combination(terms.map {
        SourceDerivation.Combination.Term(meter.multiply(it.weight, scale), SourceDerivation.Premise(it.premise))
    })
    if (capture != null) {
        meter.reserve(1L, 128L)
        capture.integer = IntegerAggregate(normalized, scaledRhs, strict, combination, terms.toList(), proofBytes)
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
    return LinearAuthorityCut(terms.first().owner, SourceInequality(normalized, rounded), proof,
        owners = terms.mapTo(linkedSetOf()) { it.owner }, family = CutFamily.SOURCE_AGGREGATION)
}

internal class AggregateMeter(
    private val limits: LinearAuthorityAggregateLimits,
    private val cancellation: ExecutionControl,
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

internal class AggregateStop(val reason: String) : RuntimeException()

internal fun stopAggregate(reason: String): Nothing = throw AggregateStop(reason)
