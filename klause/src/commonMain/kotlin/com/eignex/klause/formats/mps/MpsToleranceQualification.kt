package com.eignex.klause.formats.mps

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.LinearToleranceQualification
import com.eignex.klause.solver.evidence.ToleranceLinearRow
import com.eignex.klause.util.Cancellation

internal fun MpsModel.exactClaimsCoverTolerance(tolerance: SourceTolerance, stop: Cancellation): Boolean =
    tolerance.isExact || toleranceQualification(tolerance, stop)?.verify() == true

internal fun MpsModel.toleranceObjectiveBound(tolerance: SourceTolerance, stop: Cancellation): BigFraction? =
    toleranceQualification(tolerance, stop)?.objectiveBound()

private fun MpsModel.toleranceQualification(
    tolerance: SourceTolerance,
    stop: Cancellation,
): LinearToleranceQualification? {
    fun interrupted(): Boolean {
        stop.charge(1)
        return stop()
    }
    if (interrupted()) return null
    val numbers = sourceNumbers()
    return LinearToleranceQualification(
        integers = variables.map {
            if (interrupted()) return null
            it.integer
        },
        variableBounds = numbers.variableBounds.map { (lo, hi) ->
            if (interrupted()) return null
            lo.finiteMps()?.fraction to hi.finiteMps()?.fraction
        },
        rows = constraints.mapIndexed { index, row ->
            if (interrupted()) return null
            val (lo, hi) = numbers.constraintBounds[index]
            ToleranceLinearRow(
                row.indices.mapIndexed { entry, column ->
                    if (interrupted()) return null
                    column to numbers.constraintCoefficients[index][entry].fraction
                },
                lo.finiteMps()?.fraction,
                hi.finiteMps()?.fraction,
                row.indicator?.let {
                    mapOf(it.column to if (it.whenOne) BigFraction.ONE else BigFraction.ZERO)
                }.orEmpty(),
            )
        },
        objective = objective.indices.mapIndexed { entry, column ->
            if (interrupted()) return null
            column to numbers.objectiveCoefficients[entry].fraction
        },
        objectiveConstant = numbers.objectiveConstant.fraction,
        minimize = sense == ObjectiveSense.MINIMIZE,
        tolerance = tolerance,
        cancellation = stop,
    )
}
