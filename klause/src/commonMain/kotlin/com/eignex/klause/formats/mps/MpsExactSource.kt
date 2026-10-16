package com.eignex.klause.formats.mps

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.Term
import com.eignex.klause.lowering.lowerLinearSource
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact

/** Compile the canonical MPS source through shared exact views, defaulting to zero feasibility slack. */
fun MpsModel.toExactSourceProblem(
    settings: ProblemSettings = KlauseConfig.current.problemSettings(),
    semanticOverrides: SemanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance()),
): MpsCompiled = lowerExactSource(settings, semantics.resolve(semanticOverrides))

internal fun MpsModel.lowerExactSource(
    settings: ProblemSettings,
    effectiveSemantics: EffectiveSourceSemantics,
): MpsCompiled = checkNotNull(captureLinearSource(effectiveSemantics, Cancellation.Never))
    .compileMpsView(settings, allowProjection = false)

internal fun LinearSourceProblem.compileMpsView(settings: ProblemSettings, allowProjection: Boolean): MpsCompiled {
    val view = checkNotNull(lowerLinearSource(this, settings, allowProjection, Cancellation.Never))
    check(view.objectiveScale.den == BIG_ONE && view.objectiveScale.num.fitsLong()) {
        "shared MPS objective multiplier does not fit a signed word"
    }
    val columns = variables.mapIndexed { index, variable ->
        val reference = view.columns[index]
        val real = Term.isReal(reference)
        MpsColumn(variable.name, real, if (real) Term.realVar(reference) else Term.intVar(reference))
    }
    return MpsCompiled(
        view.model,
        view.objective,
        objective?.sense == ObjectiveSense.MAXIMIZE,
        columns,
        view.objectiveScale.num.toLongExact(),
        variables.count { !it.integer },
    ).withLinearSource(this, if (view.exact) null else "working projection differs from source numbers", null)
}
