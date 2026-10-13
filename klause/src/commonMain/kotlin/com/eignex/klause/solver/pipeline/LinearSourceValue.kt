package com.eignex.klause.solver.pipeline

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

internal fun SourceProofContext.linearValue(sample: Sample, cancellation: Cancellation): BigFraction? {
    if (cancellation() || !affineObjectiveAvailable) return null
    var value = objectiveConstant
    for ((coordinate, weight) in objective) {
        if (cancellation()) return null
        val scalar = when (coordinate.kind) {
            SourceCoordinate.Kind.Boolean -> {
                if (coordinate.id !in 0 until sample.numBoolVars) return null
                if (sample.boolValue(coordinate.id)) BigFraction.ONE else BigFraction.ZERO
            }
            SourceCoordinate.Kind.Integer -> {
                if (coordinate.id !in 0 until sample.numIntVars) return null
                BigFraction.of(sample.exactIntValue(coordinate.id), BIG_ONE)
            }
            SourceCoordinate.Kind.Real -> sample.exactReals?.getOrNull(coordinate.id) ?: return null
        }
        value += weight * scalar
    }
    return value
}
