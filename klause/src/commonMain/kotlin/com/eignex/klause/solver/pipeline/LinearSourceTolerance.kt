package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.solver.evidence.LinearToleranceQualification
import com.eignex.klause.solver.evidence.ToleranceLinearRow
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

internal fun LinearSourceProblem.toleranceQualification(stop: Cancellation): LinearToleranceQualification? {
    fun interrupted(): Boolean {
        stop.charge(1L)
        return stop()
    }
    if (interrupted()) return null
    return LinearToleranceQualification(
        integers = variableValues.map {
            if (interrupted()) return null
            it.integer
        },
        variableBounds = variableValues.map {
            if (interrupted()) return null
            it.lower to it.upper
        },
        rows = rowValues.map { row ->
            if (interrupted()) return null
            ToleranceLinearRow(
                row.termValues.map { term ->
                    if (interrupted()) return null
                    term.column to term.coefficient
                },
                row.lower,
                row.upper,
                row.indicator?.let {
                    mapOf(it.column to if (it.whenOne) BigFraction.ONE else BigFraction.ZERO)
                }.orEmpty(),
            )
        },
        objective = objective?.termValues.orEmpty().map { term ->
            if (interrupted()) return null
            term.column to term.coefficient
        },
        objectiveConstant = objective?.constant ?: BigFraction.ZERO,
        minimize = objective?.sense != ObjectiveSense.MAXIMIZE,
        tolerance = semantics.effective.feasibilityTolerance ?: SourceTolerance(),
        cancellation = stop,
    )
}
