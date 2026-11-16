package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.intervalBounds

internal data class QuadraticBoxBound(val lower: BigFraction, val upper: BigFraction)

internal fun quadraticBoxBound(
    source: Problem,
    objective: QuadraticObjectiveSpec,
    assumptions: Assumptions,
    sense: ObjectiveSense,
    step: () -> Unit,
): Pair<BigFraction, QuadraticBoxBound>? {
    fun range(variable: QuadraticVariable): Pair<BigFraction, BigFraction>? = when (variable) {
        is QuadraticVariable.Bool -> assumptions.boolValueOrNull(variable.id.index)?.let {
            val value = if (it) BigFraction.ONE else BigFraction.ZERO
            value to value
        } ?: (BigFraction.ZERO to BigFraction.ONE)
        is QuadraticVariable.Int -> assumptions.intValueOrNull(variable.id.index)?.let {
            val value = BigFraction.ofLong(it)
            value to value
        } ?: variable.id.index.let { id ->
            if (!source.intBounds.hasLower(id) || !source.intBounds.hasUpper(id)) null
            else BigFraction.ofLong(source.intBounds.lower(id)) to BigFraction.ofLong(source.intBounds.upper(id))
        }
        is QuadraticVariable.Real -> variable.id.index.let { id ->
            val lower = BigFraction.ofDouble(source.realLower[id])
            val upper = BigFraction.ofDouble(source.realUpper[id])
            if (lower == null || upper == null) null else lower to upper
        }
    }
    val bounds = objective.intervalBounds(::range, step) ?: return null
    val value = if (sense == ObjectiveSense.MINIMIZE) bounds.first else bounds.second
    return value to QuadraticBoxBound(bounds.first, bounds.second)
}
