package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PreparedSource
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.presolve.observePreparation
import com.eignex.klause.presolve.preparationInterrupted
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.solver.result.PresolvePreparationStage
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.RetainedLinearEncoding
import com.eignex.klause.util.ExecutionControl
import kotlin.time.TimeSource

// An open source model after the shared preparation phase, with the route selected from the result.
internal sealed interface OpenSourcePreparation {

    val prepared: PreparedSource

    // Source preparation refuted the model before any theory saw it.
    class Refuted(override val prepared: PreparedSource) : OpenSourcePreparation

    class Planned(
        override val prepared: PreparedSource,
        // [PreparedSource.problem], plus any row the caller added after preparation.
        val model: Problem,
        // Selected from [model], so factor indices agree with it.
        val plan: ComponentPlan,
    ) : OpenSourcePreparation {
        val route: ProblemPipeline get() = plan.theoryPipeline
    }
}

// A source rewrite requires fresh ownership because factor indices belong to the selected model; an unchanged
// model retains the captured plan under its identical projection binding.
internal fun Problem.prepareOpenSource(
    objective: LinearObjective? = null,
    config: PresolveConfig = PresolveConfig.DEFAULT,
    solutionSetSensitive: Boolean = false,
    cancellation: ExecutionControl = ExecutionControl.Never,
    budget: PresolveBudget? = null,
    projections: PreparedFactorProjections? = null,
    presolveCancellation: ExecutionControl = cancellation,
    capturedPlan: ComponentPlan? = null,
): OpenSourcePreparation {
    projections?.requireSource(this)
    val mark = TimeSource.Monotonic.markNow()
    val prepared = PresolvePipeline.prepareSource(
        this,
        config,
        objective,
        solutionSetSensitive,
        presolveCancellation,
        budget,
    )
    if (prepared.infeasible) return OpenSourcePreparation.Refuted(prepared)
    return prepared.planned(prepared.problem, projections, cancellation, capturedPlan).also {
        prepared.elapsed = mark.elapsedNow()
    }
}

// [model] is the prepared model plus whatever row the caller appended. Planning keeps `preferFinite = false`: a
// preparation that closed every open side must not erase the complete theory component selected for this
// invocation; routing a newly bounded model to the finite lane is the frontend's decision.
internal fun PreparedSource.planned(
    model: Problem,
    projections: PreparedFactorProjections? = null,
    cancellation: ExecutionControl = ExecutionControl.Never,
    capturedPlan: ComponentPlan? = null,
): OpenSourcePreparation.Planned = budget.observePreparation(
    PresolvePreparationStage.SOURCE_ENCODER, PresolveLane.SOURCE, model, cancellation,
) { token, observation ->
    val selected = capturedPlan?.takeIf { it.reusableFor(model, projections) }
        ?.takeUnless {
            token.charge(1L)
            token()
        }
        ?: run {
            var retained: RetainedLinearEncoding? = null
            val selection = budget.observePreparation(
                PresolvePreparationStage.SOURCE_SELECTION, PresolveLane.SOURCE, model, token,
            ) { selectionToken, _ ->
                val mapping = if (model === problem && projections?.providers === FactorProjectionProviders.Default) {
                    retainedFactors(selectionToken)
                } else null
                retained = mapping?.let { capturedPlan?.retainedEncoding(it, projections) }
                if (retained != null) requireNotNull(projections).retained(requireNotNull(mapping))
                else projections?.forEngineView(model) ?: FactorProjectionProviders.Default.prepare(model)
            }
            budget.observePreparation(
                PresolvePreparationStage.SOURCE_OWNERSHIP, PresolveLane.SOURCE, model, token,
            ) { ownershipToken, _ ->
                model.buildComponentPlan(selection, cancellation = ownershipToken, retained = retained,
                    preparationBudget = budget)
            }
        }
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
