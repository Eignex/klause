package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.evidence.IntegerPinStatus
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.integerPinBounds
import com.eignex.klause.util.Cancellation

internal class LinearAuthorityQuery private constructor(
    private val source: SourceProofContext,
    val coordinates: List<SourceCoordinate>,
    val indices: Map<SourceCoordinate, Int>,
    val cited: List<Pair<SourceInequality, SourceDerivation>>,
    val rows: List<ExactRationalInequality>,
    val inactive: Set<Int>,
    val costs: List<BigFraction>,
    val constant: BigFraction,
    val sign: BigFraction,
) {
    val context: SourceProofContext = source.withLinearAuthority()
    val closureRows: List<ExactRationalInequality> = rows.map {
        ExactRationalInequality(it.columns, it.coefficients, it.rhs)
    }

    fun matches(other: SourceProofContext): Boolean = source === other ||
        (source.scope == other.scope && source.coordinates == other.coordinates && source.premises == other.premises &&
            source.affineObjectiveAvailable == other.affineObjectiveAvailable &&
            source.objective == other.objective && source.objectiveConstant == other.objectiveConstant &&
            source.semantics == other.semantics && source.semanticsChecker === other.semanticsChecker)

    companion object {
        fun prepare(
            source: SourceProofContext, template: LinearAuthorityTemplate?, cancellation: Cancellation,
        ): LinearAuthorityQuery? {
            val coordinates = template?.coordinates ?: source.numericCoordinates()
            val numeric = template?.numeric ?: source.numericContext()
            val premises = numeric.premises
            val integerBounds = if (premises.values.any { it.integerPins.isNotEmpty() }) {
                numeric.integerPinBounds(source.scope.condition, cancellation) ?: return null
            } else null
            val cited = ArrayList<Pair<SourceInequality, SourceDerivation>>()
            val inactive = HashSet<Int>()
            for ((id, premise) in premises) {
                if (cancellation()) return null
                val active = premise.guards.all { (bool, value) -> source.scope.condition.bools[bool] == value } &&
                    (premise.integerPins.isEmpty() ||
                        integerBounds?.status(premise.integerPins) == IntegerPinStatus.Established)
                if (active || template != null) {
                    if (!active) inactive += cited.size
                    cited += premise.inequality to SourceDerivation.Premise(id)
                }
            }
            for ((upper, bounds) in listOf(false to source.scope.condition.lower, true to source.scope.condition.upper)) {
                val sign = if (upper) BigFraction.ONE else BigFraction.MINUS_ONE
                for ((id, value) in bounds) cited += SourceInequality(mapOf(id to sign), value * sign) to
                    SourceDerivation.Assumption(id, upper)
            }
            val indices = template?.indices ?: coordinates.withIndex().associate { it.value to it.index }
            val rows = if (template == null) cited.map { it.first.row(indices) }
                else template.rows + cited.drop(template.rows.size).map { it.first.row(indices) }
            val sign = if (source.scope.objective?.sense == ObjectiveSense.MINIMIZE) BigFraction.ONE
                else BigFraction.MINUS_ONE
            return LinearAuthorityQuery(source, coordinates, indices, cited, rows, inactive,
                coordinates.map { sign * (numeric.objective[it] ?: BigFraction.ZERO) }, numeric.objectiveConstant * sign,
                sign)
        }
    }
}
