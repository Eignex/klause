package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import kotlin.math.abs

internal interface LinearAuthoritySourceRow {
    val term: LinearAuthorityAggregateTerm
    val coefficients: Map<SourceCoordinate, BigFraction>
    val hasInteger: Boolean
}

internal class LinearAuthoritySplitRow(
    override val term: LinearAuthorityAggregateTerm,
    override val coefficients: Map<SourceCoordinate, BigFraction>,
) : LinearAuthoritySourceRow {
    override val hasInteger = coefficients.keys.any { it.kind == SourceCoordinate.Kind.Integer }
}

internal object LinearAuthoritySplitCuts {
    const val MAX_ROWS = 48

    fun prepare(
        source: SourceProofContext, rows: List<LinearAuthoritySourceRow>,
        point: Map<SourceCoordinate, Double>, cancellation: Cancellation,
        observe: (String, Long) -> Unit = { _, _ -> },
        limits: LinearAuthorityAggregateLimits = LinearAuthorityAggregateLimits(),
        maximumCandidates: Int = 64,
        incidence: Map<Pair<SourceCoordinate, Boolean>, List<LinearAuthoritySourceRow>>? = null,
    ): List<LinearAuthorityCut> {
        require(maximumCandidates in 0..64)
        if (maximumCandidates == 0 || rows.size < 2) return emptyList()
        val run = SplitRun(source, point, AggregateMeter(limits, cancellation, observe), limits, observe)
        return run.prepare(rows, maximumCandidates, incidence)
    }
}

private class SplitRun(
    private val source: SourceProofContext,
    private val point: Map<SourceCoordinate, Double>,
    private val meter: AggregateMeter,
    private val limits: LinearAuthorityAggregateLimits,
    private val observe: (String, Long) -> Unit,
) {
    private class Bound(
        val row: LinearAuthoritySourceRow, val coefficient: BigFraction, val origin: BigFraction,
    )
    private class Nonnegative(val row: LinearAuthoritySourceRow, val scale: BigFraction, val alpha: BigFraction)
    private val bounds = HashMap<Pair<SourceCoordinate, Boolean>, Bound>()
    private val checkedBounds = HashSet<SourceCoordinate>()
    private val checkedRows = HashSet<Int>()

    fun prepare(
        rows: List<LinearAuthoritySourceRow>, maximum: Int,
        borrowed: Map<Pair<SourceCoordinate, Boolean>, List<LinearAuthoritySourceRow>>?,
    ): List<LinearAuthorityCut> {
        val result = ArrayList<LinearAuthorityCut>()
        try {
            val count = minOf(rows.size, LinearAuthoritySplitCuts.MAX_ROWS)
            meter.reserve(count.toLong() * 2L, 320L + count * 20L)
            val admitted = rows.take(count)
            if (count < rows.size) observe("AggregateSplitRowLimit", 1L)
            if (admitted.none { it.hasInteger }) return emptyList()
            val owners = IntArray(count) { admitted[it].term.owner }
            meter.reserve(count.toLong() * count / 8L + 1L)
            owners.sort()
            val incidence = borrowed?.also { observe("AggregateSplitBorrowedIncidence", 1L) }
                ?: indexRows(admitted)
            for (row in admitted) {
                meter.reserve(1L)
                if (!row.hasInteger || !eligible(row)) continue
                meter.reserve(1L, 128L)
                val paired = HashSet<Int>()
                for ((id, coefficient) in row.coefficients) {
                    meter.reserve(1L)
                    if (id.kind != SourceCoordinate.Kind.Real) continue
                    meter.reserve(1L, 64L)
                    for (other in incidence[id to (coefficient.signum() > 0)].orEmpty()) {
                        meter.reserve(7L)
                        if (owners.binarySearch(other.term.owner) < 0 || !eligible(other) ||
                            other.term.owner == row.term.owner ||
                            (other.term.owner < row.term.owner && other.hasInteger)) continue
                        meter.reserve(1L, 32L)
                        if (!paired.add(other.term.owner)) continue
                        if (result.size >= maximum) throw AggregateStop("RetentionLimit")
                        validate(row)
                        validate(other)
                        observe("AggregateSplitAttempts", 1L)
                        val weights = listOf(meter.number(magnitude(other.coefficients.getValue(id))),
                            meter.number(magnitude(coefficient).negated()))
                        val cut = pair(row, other, weights) ?: continue
                        meter.reserve(1L, 128L + cut.inequality.supportSize * 64L + cut.owners.size * 32L)
                        result += cut
                        observe("AggregateSplitEligible", 1L)
                    }
                }
            }
            observe("AggregateSplitComplete", 1L)
        } catch (stop: AggregateStop) {
            observe("AggregateSplit${stop.reason}", 1L)
        }
        return result.toList()
    }

    private fun eligible(row: LinearAuthoritySourceRow): Boolean {
        meter.reserve(1L)
        if (row.coefficients.size !in 2..limits.support) return false
        val premise = source.premise(row.term.premise) ?: return false
        return premise.inequality === row.term.inequality && premise.guardCount == 0 && premise.integerPinCount == 0
    }

    private fun validate(row: LinearAuthoritySourceRow) {
        meter.reserve(1L)
        if (row.term.owner in checkedRows) return
        meter.reserve(1L, 32L)
        checkedRows += row.term.owner
        meter.number(row.term.inequality.rhs)
        for ((coordinate, value) in row.coefficients) {
            if (coordinate.kind == SourceCoordinate.Kind.Boolean) throw AggregateStop("BooleanActivity")
            meter.number(value)
        }
    }

    private fun indexRows(rows: List<LinearAuthoritySourceRow>):
        Map<Pair<SourceCoordinate, Boolean>, List<LinearAuthoritySourceRow>> {
        meter.reserve(1L, 128L)
        val incidence = HashMap<Pair<SourceCoordinate, Boolean>, MutableList<LinearAuthoritySourceRow>>()
        for (row in rows) {
            meter.reserve(1L)
            if (row.coefficients.size !in 2..limits.support) continue
            for ((id, coefficient) in row.coefficients) {
                meter.reserve(1L)
                if (id.kind != SourceCoordinate.Kind.Real) continue
                meter.reserve(1L, 64L)
                val key = id to (coefficient.signum() > 0)
                val bucket = incidence[key] ?: run {
                    meter.reserve(1L, 160L)
                    ArrayList<LinearAuthoritySourceRow>().also { incidence[key] = it }
                }
                bucket += row
            }
        }
        return incidence
    }

    private fun fillBounds(activity: Map<SourceCoordinate, BigFraction>, anchor: SourceCoordinate) {
        meter.reserve(activity.size.toLong())
        val count = activity.count { (id, coefficient) ->
            id != anchor && !coefficient.isZero && id !in checkedBounds
        }
        if (count == 0) return
        meter.reserve(activity.size.toLong(), 128L + count * 32L)
        val wanted = HashSet<SourceCoordinate>()
        for ((id, coefficient) in activity) {
            if (id != anchor && !coefficient.isZero && id !in checkedBounds) wanted += id
        }
        meter.reserve(1L, 128L)
        val found = HashMap<Pair<SourceCoordinate, Boolean>, Bound>()
        var owner = 0
        source.forEachPremise { name, premise ->
            val index = owner++
            meter.reserve(1L)
            observe("AggregateSplitBoundVisits", 1L)
            if (premise.guardCount != 0 || premise.integerPinCount != 0) return@forEachPremise
            val id = premise.inequality.singleCoordinate() ?: return@forEachPremise
            if (id !in wanted) return@forEachPremise
            val coefficient = meter.number(checkNotNull(premise.inequality.coefficient(id)))
            meter.reserve(1L, 64L)
            val key = id to (coefficient.signum() > 0)
            if (key in found) return@forEachPremise
            val origin = divide(meter.number(premise.inequality.rhs), coefficient)
            meter.reserve(1L, 192L + name.length * 2L)
            val term = LinearAuthorityAggregateTerm(index, name, premise.inequality)
            found[key] = Bound(LinearAuthoritySplitRow(term, mapOf(id to coefficient)), coefficient, origin)
        }
        meter.reserve(found.size.toLong() + wanted.size, 128L + wanted.size * 32L + found.size * 48L)
        bounds.putAll(found)
        checkedBounds.addAll(wanted)
        observe("AggregateSplitBoundScanComplete", 1L)
    }

    private fun pair(
        left: LinearAuthoritySourceRow, right: LinearAuthoritySourceRow, weights: List<BigFraction>,
    ): LinearAuthorityCut? {
        meter.reserve(2L, 256L)
        val rows = listOf(left, right)
        val activity = linkedMapOf<SourceCoordinate, BigFraction>()
        var rhs = BigFraction.ZERO
        for ((index, row) in rows.withIndex()) {
            for ((id, coefficient) in row.coefficients) {
                meter.reserve(1L)
                if (id !in activity && activity.size >= limits.support) {
                    observe("AggregateSplitSupportLimit", 1L)
                    return null
                }
                activity[id] = meter.add(activity[id] ?: BigFraction.ZERO,
                    meter.multiply(weights[index], coefficient))
            }
            rhs = meter.add(rhs, meter.multiply(weights[index], row.term.inequality.rhs))
        }
        for ((coordinate, coefficient) in activity) {
            meter.reserve(1L)
            if (coordinate.kind != SourceCoordinate.Kind.Integer || coefficient.isZero) continue
            val value = point[coordinate]?.takeIf { it.isFinite() } ?: continue
            if (value % 1.0 == 0.0) continue
            val cut = derive(rows, weights, activity, rhs, coordinate, coefficient)
            if (cut != null) return cut
        }
        return null
    }

    private fun endpoint(coordinate: SourceCoordinate): Bound? {
        meter.reserve(2L, 128L)
        val lower = bounds[coordinate to false]
        val upper = bounds[coordinate to true]
        if (lower == null) return upper
        if (upper == null) return lower
        val value = point[coordinate]?.takeIf { it.isFinite() } ?: return lower
        return if (abs(value - lower.origin.toDouble()) <= abs(value - upper.origin.toDouble())) lower else upper
    }

    private fun derive(
        rows: List<LinearAuthoritySourceRow>, weights: List<BigFraction>,
        activity: Map<SourceCoordinate, BigFraction>, rhs: BigFraction,
        anchor: SourceCoordinate, gamma: BigFraction,
    ): LinearAuthorityCut? {
        fillBounds(activity, anchor)
        meter.reserve(activity.size.toLong(), 128L + activity.size * 64L)
        val nonnegative = ArrayList<Nonnegative>()
        var shifted = rhs
        for ((id, coefficient) in activity) {
            meter.reserve(1L)
            if (id == anchor || coefficient.isZero) continue
            val bound = endpoint(id) ?: run {
                observe("AggregateSplitMissingBound", 1L)
                return null
            }
            shifted = meter.add(shifted, meter.multiply(coefficient, bound.origin).negated())
            val sign = if (bound.coefficient < BigFraction.ZERO) BigFraction.ONE else BigFraction.MINUS_ONE
            val alpha = divide(meter.multiply(coefficient, sign), gamma)
            meter.reserve(1L, 64L)
            nonnegative += Nonnegative(bound.row, meter.number(magnitude(bound.coefficient).reciprocal()), alpha)
        }
        for ((index, row) in rows.withIndex()) {
            meter.reserve(1L, 64L)
            nonnegative += Nonnegative(row, BigFraction.ONE, divide(weights[index], gamma))
        }
        val beta = divide(shifted, gamma)
        meter.arithmetic(beta, BigFraction.ONE)
        val threshold = meter.number(BigFraction.of(beta.negated().ceilInteger(), BIG_ONE).negated())
        val fraction = meter.add(beta, threshold.negated())
        if (fraction.isZero) return null
        val belowScale = meter.number(fraction.reciprocal())
        val aboveScale = meter.number(meter.add(BigFraction.ONE, fraction.negated()).reciprocal())
        return conclusion(nonnegative, anchor, threshold, belowScale, aboveScale)
    }

    private fun conclusion(
        nonnegative: List<Nonnegative>, anchor: SourceCoordinate, threshold: BigFraction,
        belowScale: BigFraction, aboveScale: BigFraction,
    ): LinearAuthorityCut? {
        meter.reserve(nonnegative.size.toLong(), 256L + nonnegative.size * 128L)
        var proofBytes = 256L
        fun term(weight: BigFraction, proof: SourceDerivation, characters: Int = 0): SourceDerivation.Combination.Term {
            val bytes = 96L + characters * 2L + (limits.scalarBits + 7L) / 4L
            if (bytes > limits.proofBytes - proofBytes) throw AggregateStop("ProofLimit")
            proofBytes += bytes
            meter.reserve(1L, bytes)
            observe("AggregateSplitProofTerms", 1L)
            return SourceDerivation.Combination.Term(weight, proof)
        }
        val below = arrayListOf(term(belowScale, SourceDerivation.Assumption(anchor, upper = true)))
        val above = arrayListOf(term(aboveScale, SourceDerivation.Assumption(anchor, upper = false)))
        val coefficients = linkedMapOf<SourceCoordinate, BigFraction>()
        val owners = HashSet<Int>()
        var rhs = BigFraction.MINUS_ONE
        // Signed slack identities give two proofs whose inequality multipliers are nonnegative.
        for (entry in nonnegative) {
            val lower = meter.multiply(entry.alpha, belowScale)
            val upper = meter.multiply(entry.alpha.negated(), aboveScale)
            meter.arithmetic(lower, upper)
            val coefficient = if (lower >= upper) lower else upper
            if (coefficient.isZero) continue
            val factor = meter.multiply(coefficient, entry.scale)
            val row = entry.row
            for ((id, value) in row.coefficients) {
                meter.reserve(1L)
                if (id !in coefficients && coefficients.size >= limits.support) throw AggregateStop("SupportLimit")
                coefficients[id] = meter.add(coefficients[id] ?: BigFraction.ZERO, meter.multiply(factor, value))
            }
            rhs = meter.add(rhs, meter.multiply(factor, row.term.inequality.rhs))
            val belowWeight = meter.multiply(meter.add(coefficient, lower.negated()), entry.scale)
            val aboveWeight = meter.multiply(meter.add(coefficient, upper.negated()), entry.scale)
            if (belowWeight < BigFraction.ZERO || aboveWeight < BigFraction.ZERO) throw AggregateStop("ProofSign")
            if (!belowWeight.isZero) below += term(belowWeight,
                SourceDerivation.Premise(row.term.premise), row.term.premise.length)
            if (!aboveWeight.isZero) above += term(aboveWeight,
                SourceDerivation.Premise(row.term.premise), row.term.premise.length)
            meter.reserve(1L, 32L)
            owners += row.term.owner
        }
        meter.reserve(coefficients.size.toLong(), 128L + coefficients.size * 64L)
        val inequality = SourceInequality(coefficients, rhs)
        val split = SourceDerivation.IntegerSplit(anchor, threshold,
            SourceDerivation.Combination(below), SourceDerivation.Combination(above))
        val proof = SourceDerivation.Import(SourceEvidence(source.scope, SourceClaim.Consequence(inequality), split))
        meter.reserve(1L, 256L + owners.size * 32L)
        if (owners.isEmpty()) return null
        return LinearAuthorityCut(owners.min(), inequality, proof, mixed = true, owners = owners,
            family = CutFamily.SOURCE_AGGREGATION)
    }

    private fun magnitude(value: BigFraction): BigFraction =
        if (value.signum() < 0) value.negated() else value

    private fun divide(left: BigFraction, right: BigFraction): BigFraction =
        meter.multiply(left, meter.number(right.reciprocal()))
}
