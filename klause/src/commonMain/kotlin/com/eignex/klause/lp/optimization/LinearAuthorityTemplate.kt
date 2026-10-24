package com.eignex.klause.lp.optimization

import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.Cancellation

internal class LinearAuthorityTemplate private constructor(
    private val source: SourceProofContext,
    val numeric: SourceProofContext,
    val coordinates: List<SourceCoordinate>,
    val rows: List<ExactRationalInequality>,
) {
    fun matches(other: SourceProofContext): Boolean = source.scope.revision == other.scope.revision &&
        source.scope.objective == other.scope.objective &&
        source.scope.condition.bools == other.scope.condition.bools && source.coordinates == other.coordinates &&
        source.premises == other.premises && source.objective == other.objective &&
        source.objectiveConstant == other.objectiveConstant

    companion object {
        fun prepare(source: SourceProofContext, cancellation: Cancellation): LinearAuthorityTemplate? {
            val numeric = source.numericContext()
            val coordinates = source.numericCoordinates()
            val indices = coordinates.withIndex().associate { it.value to it.index }
            val rows = ArrayList<ExactRationalInequality>()
            for (premise in numeric.premises.values) {
                if (cancellation()) return null
                rows += premise.inequality.row(indices)
            }
            return LinearAuthorityTemplate(source, numeric, coordinates, rows)
        }
    }
}

internal fun SourceInequality.row(indices: Map<SourceCoordinate, Int>): ExactRationalInequality {
    val entries = coefficients.entries.sortedBy { indices.getValue(it.key) }
    return ExactRationalInequality(entries.map { indices.getValue(it.key) }.toIntArray(),
        entries.map { it.value }, rhs, strict)
}
