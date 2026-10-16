package com.eignex.klause.formats.mps

import com.eignex.klause.ir.LinearSourceIndicator
import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.util.Cancellation

/**
 * Capture original MPS numbers and effective declarations before constructing an engine view.
 * Returns null when [cancellation] interrupts the capture; no partial source escapes.
 */
fun MpsModel.toLinearSourceProblem(
    semanticOverrides: SemanticOverrides = SemanticOverrides(),
    cancellation: Cancellation = Cancellation.Never,
): LinearSourceProblem? {
    if (cancellation()) return null
    val policy = semantics.resolve(semanticOverrides)
    val numbers = sourceNumbers()
    val bounds = effectiveVariableBounds(policy, numbers, cancellation) ?: return null
    val declarations = ArrayList<LinearSourceVariable>(variables.size)
    for ((index, variable) in variables.withIndex()) {
        cancellation.charge(1L)
        if (cancellation()) return null
        val (lower, upper) = bounds[index]
        declarations += LinearSourceVariable(variable.name, variable.integer,
            lower.finiteMps()?.fraction, upper.finiteMps()?.fraction)
    }
    val rows = ArrayList<LinearSourceRow>(constraints.size)
    for ((index, row) in constraints.withIndex()) {
        cancellation.charge(1L)
        if (cancellation()) return null
        val terms = ArrayList<LinearSourceTerm>(row.indices.size)
        for (entry in row.indices.indices) {
            cancellation.charge(1L)
            if (cancellation()) return null
            terms += LinearSourceTerm(row.indices[entry], numbers.constraintCoefficients[index][entry].fraction)
        }
        val (lower, upper) = numbers.constraintBounds[index]
        rows += LinearSourceRow(terms, lower.finiteMps()?.fraction, upper.finiteMps()?.fraction,
            row.indicator?.let { LinearSourceIndicator(it.column, it.whenOne) })
    }
    val original = objective
    val objective = if (original.name.isEmpty() && original.indices.isEmpty()) null else {
        val terms = ArrayList<LinearSourceTerm>(original.indices.size)
        for (entry in original.indices.indices) {
            cancellation.charge(1L)
            if (cancellation()) return null
            terms += LinearSourceTerm(original.indices[entry], numbers.objectiveCoefficients[entry].fraction)
        }
        LinearSourceObjective(terms, numbers.objectiveConstant.fraction, sense)
    }
    if (cancellation()) return null
    return LinearSourceProblem(declarations, rows, objective, policy).takeUnless { cancellation() }
}
