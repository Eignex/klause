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
        class Side(val row: Int, val coefficient: BigFraction, val value: BigFraction, val strict: Boolean)
        val lower = HashMap<SourceCoordinate, Side>()
        val upper = HashMap<SourceCoordinate, Side>()
        for ((id, entry) in cited.withIndex()) {
            meter.reserve(1L)
            if (id in inactive || id in cutFamilies || entry.first.supportSize != 1) continue
            val row = entry.first
            val (coordinate, coefficient) = row.coefficients.entries.single()
            meter.number(coefficient)
            meter.number(row.rhs)
            meter.arithmetic(row.rhs, coefficient)
            val value = meter.number(row.rhs * coefficient.reciprocal())
            val negative = coefficient < BigFraction.ZERO
            val sides = if (negative) lower else upper
            val previous = sides[coordinate]
            if (previous != null) meter.arithmetic(value, previous.value)
            val improves = previous == null || (if (negative) value > previous.value else value < previous.value) ||
                (value == previous.value && row.strict && !previous.strict)
            if (improves) {
                meter.reserve(1L, 96L)
                sides[coordinate] = Side(id, coefficient, value, row.strict)
            }
        }
        for ((id, family) in selected) {
            val cut = cited[id].first
            observe("SelectedCutBoundCandidates", 1L)
            observe("SelectedCutBoundSupportMax", cut.supportSize.toLong())
            var residual = meter.number(cut.rhs)
            var strict = cut.strict
            meter.reserve(1L, 64L + cut.supportSize * 48L)
            val terms = ArrayList<SourceDerivation.Combination.Term>()
            terms += SourceDerivation.Combination.Term(BigFraction.ONE, cited[id].second)
            var complete = true
            for ((coordinate, coefficient) in cut.coefficients) {
                meter.number(coefficient)
                val side = (if (coefficient > BigFraction.ZERO) lower else upper)[coordinate]
                if (side == null) {
                    observe("SelectedCutBoundMissingSides", 1L)
                    complete = false
                    break
                }
                meter.arithmetic(coefficient, side.coefficient)
                val weight = meter.number(coefficient.negated() * side.coefficient.reciprocal())
                residual = meter.add(residual, meter.multiply(weight, cited[side.row].first.rhs))
                strict = strict || side.strict
                terms += SourceDerivation.Combination.Term(weight, cited[side.row].second)
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
