package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PreparedSource
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.presolve.observePreparation
import com.eignex.klause.presolve.preparationInterrupted
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.solver.result.PresolvePreparationStage
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource

/** An open source model after the shared preparation phase, with the route selected from the result. */
internal sealed interface OpenSourcePreparation {

    /** The one source-safe preparation this outcome came out of, whichever way it went. */
    val prepared: PreparedSource

    /** Source preparation refuted the model before any theory saw it. */
    class Refuted(override val prepared: PreparedSource) : OpenSourcePreparation

    /** The prepared model and the ownership selected from it. */
    class Planned(
        override val prepared: PreparedSource,
        /** [PreparedSource.problem], plus any row the caller added after preparation. */
        val model: Problem,
        /** Ownership selected from [model], so factor indices agree with it. */
        val plan: ComponentPlan,
    ) : OpenSourcePreparation {
        /** The complete open route [plan] selected. */
        val route: ProblemPipeline get() = plan.theoryPipeline
    }
}

/**
 * Prepare this source model, then select its complete open route from what preparation produced.
 *
 * Planning after preparation is what lets a source rewrite move factor ownership with the factors: a
 * plan selected ahead of the phase is indexed by factors the prepared model may not have.
 */
internal fun Problem.prepareOpenSource(
    objective: LinearObjective? = null,
    config: PresolveConfig = PresolveConfig.DEFAULT,
    solutionSetSensitive: Boolean = false,
    cancellation: Cancellation = Cancellation.Never,
    budget: PresolveBudget? = null,
    projections: PreparedFactorProjections? = null,
    presolveCancellation: Cancellation = cancellation,
): OpenSourcePreparation {
    projections?.requireSource(this)
    val mark = TimeSource.Monotonic.markNow()
    val preparation = budget?.orSpent(presolveCancellation) ?: presolveCancellation
    val prepared = PresolvePipeline.prepareSource(
        this,
        config,
        objective,
        solutionSetSensitive,
        preparation,
        budget,
    )
    if (prepared.infeasible) return OpenSourcePreparation.Refuted(prepared)
    return prepared.planned(prepared.problem, projections, cancellation).also {
        prepared.elapsed = mark.elapsedNow()
    }
}

/**
 * This preparation's plan over [model], the prepared model plus whatever row the caller appended to it.
 *
 * Planning keeps `preferFinite = false`. A preparation that closed every open side must not erase the
 * complete theory component selected for this invocation; routing a newly bounded model to the finite
 * lane remains the frontend's policy decision.
 */
internal fun PreparedSource.planned(
    model: Problem,
    projections: PreparedFactorProjections? = null,
    cancellation: Cancellation = Cancellation.Never,
): OpenSourcePreparation.Planned = budget.observePreparation(
    PresolvePreparationStage.SOURCE_ENCODER, PresolveLane.SOURCE, model, cancellation,
) { token, observation ->
    val selected = projections?.forEngineView(model)?.let { model.componentPlan(it, cancellation = token) }
        ?: model.componentPlan(cancellation = token)
    val plan = if (!token() && selected.theoryPipeline == ProblemPipeline.FINITE_CP) {
        requireNotNull(selected.forLinearConditions(model, retainEmptyTheory = true, cancellation = token)) {
            "source preparation lost the selected arithmetic completion"
        }
    } else selected
    require(
        (plan.theoryPipeline != ProblemPipeline.FINITE_CP &&
            plan.theoryPipeline != ProblemPipeline.UNSUPPORTED_OPEN) || token() ||
            plan.encodingDecline == EncoderDecline.RESOURCE,
    ) {
        "source preparation left the model without an open-theory route"
    }
    if (plan.encodingDecline == EncoderDecline.RESOURCE) observation.preparationInterrupted()
    OpenSourcePreparation.Planned(this, model, plan)
}
