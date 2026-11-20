package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

internal class LinearAuthorityCutConflict(val proof: SourceDerivation, val family: CutFamily)

internal fun LinearAuthorityQuery.selectedCutConflict(
    cancellation: ExecutionControl,
    onCost: (Long, Long) -> Unit,
    observe: (String, Long) -> Unit = { _, _ -> },
): LinearAuthorityCutConflict? {
    val meter = AggregateMeter(LinearAuthorityAggregateLimits(scalarBits = 4096), cancellation) { stage, amount ->
        if (stage == "AggregateWork") onCost(amount, 0L) else onCost(0L, amount)
    }
    return try {
        meter.reserve(1L + cutFamilies.size, 128L + minOf(8, cutFamilies.size) * 64L)
        val selected = cutFamilies.asSequence().filter { (row, family) ->
            row !in inactive && (family == CutFamily.SOURCE_AGGREGATION || family == CutFamily.AGGREGATION_MIR)
        }.take(8).toList()
        if (selected.isEmpty()) return null
        observe("SelectedCutBoundScans", 1L)
        val sides = CutConflictSides(this, meter, observe)
        sides.collect()
        for ((id, family) in selected) {
            val cut = cited[id].first
            observe("SelectedCutBoundCandidates", 1L)
            observe("SelectedCutBoundSupportMax", cut.supportSize.toLong())
            var residual = meter.number(cut.rhs)
            var strict = cut.strict
            meter.reserve(1L, 64L + cut.supportSize * 48L)
            val terms = ArrayList<SourceDerivation.Combination.Term>()
            terms += SourceDerivation.Combination.Term(BigFraction.ONE, cited[id].second)
            val complete = cut.forEachCoefficient { coordinate, coefficient ->
                meter.number(coefficient)
                val side = sides.opposite(coordinate, coefficient > BigFraction.ZERO)
                if (side == null) {
                    observe("SelectedCutBoundMissingSides", 1L)
                    observe("SelectedCutBoundMissing${coordinate.kind}Sides", 1L)
                    return@forEachCoefficient false
                }
                meter.arithmetic(coefficient, side.coefficient)
                val weight = meter.number(coefficient.negated() * side.coefficient.reciprocal())
                residual = meter.add(residual, meter.multiply(weight, side.rhs))
                strict = strict || side.strict
                terms += SourceDerivation.Combination.Term(weight, side.proof)
                true
            }
            if (complete) observe("SelectedCutBoundCompleteCandidates", 1L)
            if (complete && (residual < BigFraction.ZERO || (residual.isZero && strict))) {
                return LinearAuthorityCutConflict(SourceDerivation.Combination(terms), family)
            }
        }
        null
    } catch (stop: AggregateStop) {
        observe("SelectedCutBound${stop.reason}", 1L)
        null
    }
}

private class CutConflictSide(
    val coefficient: BigFraction,
    val rhs: BigFraction,
    val value: BigFraction,
    val strict: Boolean,
    val proof: SourceDerivation,
    val projected: Boolean = false,
)

private class CutConflictSides(
    private val query: LinearAuthorityQuery,
    private val meter: AggregateMeter,
    private val observe: (String, Long) -> Unit,
) {
    private val lower = HashMap<SourceCoordinate, CutConflictSide>()
    private val upper = HashMap<SourceCoordinate, CutConflictSide>()

    fun collect() {
        for ((id, entry) in query.cited.withIndex()) {
            meter.reserve(1L)
            if (id in query.inactive || id in query.cutFamilies || entry.first.supportSize != 1) continue
            val row = entry.first
            val coordinate = checkNotNull(row.singleCoordinate())
            val coefficient = checkNotNull(row.coefficient(coordinate))
            meter.number(coefficient)
            meter.number(row.rhs)
            meter.arithmetic(row.rhs, coefficient)
            meter.reserve(1L, 128L)
            val side = CutConflictSide(coefficient, row.rhs,
                meter.number(row.rhs * coefficient.reciprocal()), row.strict, entry.second)
            val sides = if (coefficient < BigFraction.ZERO) lower else upper
            if (improves(side, sides[coordinate])) {
                meter.reserve(1L, 64L)
                sides[coordinate] = side
            }
        }
    }

    fun opposite(coordinate: SourceCoordinate, needsLower: Boolean): CutConflictSide? {
        val sides = if (needsLower) lower else upper
        sides[coordinate]?.let { return it }
        var best: CutConflictSide? = null
        for ((id, entry) in query.cited.withIndex()) {
            meter.reserve(1L)
            val row = entry.first
            if (id in query.inactive || id in query.cutFamilies || row.supportSize !in 2..128) continue
            val coefficient = row.coefficient(coordinate) ?: continue
            meter.number(coefficient)
            if ((coefficient < BigFraction.ZERO) != needsLower) continue
            observe("SelectedCutBoundProjectionRows", 1L)
            var residual = meter.number(row.rhs)
            var strict = row.strict
            meter.reserve(row.supportSize.toLong(), 256L + row.supportSize * 64L)
            val terms = ArrayList<SourceDerivation.Combination.Term>()
            terms += SourceDerivation.Combination.Term(BigFraction.ONE, entry.second)
            val complete = row.forEachCoefficient { other, weight ->
                meter.reserve(1L)
                if (other == coordinate) return@forEachCoefficient true
                meter.number(weight)
                val side = (if (weight > BigFraction.ZERO) lower else upper)[other]
                if (side == null || side.projected) {
                    return@forEachCoefficient false
                }
                meter.arithmetic(weight, side.coefficient)
                val multiplier = meter.number(weight.negated() * side.coefficient.reciprocal())
                residual = meter.add(residual, meter.multiply(multiplier, side.rhs))
                strict = strict || side.strict
                terms += SourceDerivation.Combination.Term(multiplier, side.proof)
                true
            }
            if (complete) {
                meter.arithmetic(residual, coefficient)
                val projected = CutConflictSide(coefficient, residual,
                    meter.number(residual * coefficient.reciprocal()), strict,
                    SourceDerivation.Combination(terms), projected = true)
                observe("SelectedCutBoundProjectedSides", 1L)
                if (improves(projected, best)) best = projected
            }
        }
        if (best != null) {
            meter.reserve(1L, 128L)
            sides[coordinate] = best
        }
        return best
    }

    private fun improves(candidate: CutConflictSide, previous: CutConflictSide?): Boolean {
        if (previous == null) return true
        meter.arithmetic(candidate.value, previous.value)
        return (if (candidate.coefficient < BigFraction.ZERO) candidate.value > previous.value
            else candidate.value < previous.value) ||
            (candidate.value == previous.value && candidate.strict && !previous.strict)
    }
}
