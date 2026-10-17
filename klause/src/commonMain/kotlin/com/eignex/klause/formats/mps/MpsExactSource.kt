package com.eignex.klause.formats.mps

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.Term
import com.eignex.klause.lowering.lowerLinearSource
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact

/** Compile the canonical MPS source through shared exact views, defaulting to zero feasibility slack. */
fun MpsModel.toExactSourceProblem(
    settings: ProblemSettings = KlauseConfig.current.problemSettings(),
    semanticOverrides: SemanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance()),
): MpsCompiled = compileMpsView(
    checkNotNull(toLinearSourceProblem(semanticOverrides)), settings, allowProjection = false,
)

internal fun MpsModel.compileMpsView(
    source: LinearSourceProblem,
    settings: ProblemSettings,
    allowProjection: Boolean,
): MpsCompiled {
    val view = checkNotNull(lowerLinearSource(source, settings, allowProjection, Cancellation.Never))
    check(view.objectiveScale.den == BIG_ONE && view.objectiveScale.num.fitsLong()) {
        "shared MPS objective multiplier does not fit a signed word"
    }
    val declarations = source.variables
    val columns = declarations.mapIndexed { index, variable ->
        val reference = view.columns[index]
        val real = Term.isReal(reference)
        MpsColumn(variable.name, real, if (real) Term.realVar(reference) else Term.intVar(reference))
    }
    // Rational zero has one value; the fresh compatibility view retains explicit binary64 bound signs.
    for ((index, column) in columns.withIndex()) {
        if (!column.real) continue
        val literal = variables[index]
        val declaration = declarations[index]
        if (declaration.lower == SemanticRational.ZERO && literal.lower?.toRawBits() == (-0.0).toRawBits() &&
            view.model.realLower[column.id] == 0.0
        ) view.model.realLower[column.id] = -0.0
        if (declaration.upper == SemanticRational.ZERO && literal.upper?.toRawBits() == (-0.0).toRawBits() &&
            view.model.realUpper[column.id] == 0.0
        ) view.model.realUpper[column.id] = -0.0
    }
    return MpsCompiled(
        view.model,
        view.objective,
        source.objective?.sense == ObjectiveSense.MAXIMIZE,
        columns,
        view.objectiveScale.num.toLongExact(),
        declarations.count { !it.integer },
    ).withLinearSource(
        source,
        if (view.exact) null else projectionDifference(source) ?: "working projection differs from source numbers",
        null,
    )
}

private fun MpsModel.projectionDifference(source: LinearSourceProblem): String? {
    val declarations = source.variables
    for (variable in declarations) {
        if (listOfNotNull(variable.lower, variable.upper).any { !it.projectsExactly() }) {
            return "column bound '${variable.name}'"
        }
    }
    for ((index, row) in source.rowValues.withIndex()) {
        if (row.termValues.any { !it.coefficient.projectsExactly() }) {
            return "row '${constraints[index].name}' coefficient"
        }
        if (listOfNotNull(row.lower, row.upper).any { !it.projectsExactly() }) {
            return "row '${constraints[index].name}' bound"
        }
    }
    for (term in source.objective?.termValues.orEmpty()) {
        if (!term.coefficient.projectsExactly()) return "objective coefficient '${declarations[term.column].name}'"
    }
    return if (source.objective?.constant?.projectsExactly() == false) "objective constant" else null
}

private fun SemanticRational.projectsExactly(): Boolean {
    val value = BigFraction.of(numerator, denominator)
    return BigFraction.ofDouble(value.toDouble()) == value
}
