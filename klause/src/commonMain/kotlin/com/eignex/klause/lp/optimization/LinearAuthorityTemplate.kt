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
    val indices: Map<SourceCoordinate, Int>,
    val rows: List<ExactRationalInequality>,
    val cuts: LinearAuthorityCuts?,
) {
    fun withAdditional(
        additional: List<LinearAuthorityCut>, cancellation: Cancellation,
        onCost: (Long, Long) -> Unit = { _, _ -> },
    ): LinearAuthorityTemplate? {
        if (additional.isEmpty()) return this
        val prepared = cuts ?: return null
        require(additional.size <= 64)
        for (cut in additional) {
            onCost(1L + cut.owners.size + cut.inequality.supportSize,
                64L + cut.owners.size * 32L + cut.inequality.supportSize * 48L)
            if (cancellation()) return null
            require(cut.owners.all { it in rows.indices })
            require(cut.inequality.coefficients.keys.all { it in indices })
        }
        onCost(prepared.rows.size.toLong() + additional.size,
            64L + (prepared.rows.size.toLong() + additional.size) * 8L)
        if (cancellation()) return null
        return LinearAuthorityTemplate(source, numeric, coordinates, indices, rows, prepared.withAdditional(additional))
    }

    fun matches(other: SourceProofContext): Boolean = source.scope.revision == other.scope.revision &&
        source.scope.objective == other.scope.objective &&
        source.scope.condition.bools == other.scope.condition.bools && source.coordinates == other.coordinates &&
        source.premises == other.premises && source.objective == other.objective &&
        source.objectiveConstant == other.objectiveConstant

    companion object {
        fun prepare(
            source: SourceProofContext, cancellation: Cancellation, cuts: Boolean = false,
            complementaryCuts: Boolean = false,
            onCutWork: (Long) -> Unit = {},
        ): LinearAuthorityTemplate? {
            val numeric = source.numericContext()
            val coordinates = source.numericCoordinates()
            val indices = coordinates.withIndex().associate { it.value to it.index }
            val rows = ArrayList<ExactRationalInequality>()
            for (premise in numeric.premises.values) {
                if (cancellation()) return null
                rows += premise.inequality.row(indices)
            }
            val separated = if (cuts) {
                LinearAuthorityCuts.prepare(numeric, cancellation, complementaryCuts, onCutWork) ?: return null
            } else null
            return LinearAuthorityTemplate(source, numeric, coordinates, indices, rows, separated)
        }
    }
}

internal fun SourceInequality.row(indices: Map<SourceCoordinate, Int>): ExactRationalInequality {
    val entries = coefficients.entries.sortedBy { indices.getValue(it.key) }
    return ExactRationalInequality(entries.map { indices.getValue(it.key) }.toIntArray(),
        entries.map { it.value }, rhs, strict)
}
