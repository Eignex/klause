package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PreparedSource
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation

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
 * plan selected ahead of the phase is indexed by factors the prepared model no longer has, which is why
 * this order is the whole boundary rather than a detail of it.
 */
internal fun Problem.prepareOpenSource(
    objective: LinearObjective? = null,
    config: PresolveConfig = PresolveConfig.DEFAULT,
    solutionSetSensitive: Boolean = false,
    cancellation: Cancellation = Cancellation.Never,
    budget: PresolveBudget? = null,
): OpenSourcePreparation {
    val preparation = Cancellation { cancellation() || budget?.remaining() == 0L }
    val prepared = PresolvePipeline.prepareSource(
        this,
        config,
        objective,
        solutionSetSensitive,
        preparation,
        budget,
    )
    if (prepared.infeasible) return OpenSourcePreparation.Refuted(prepared)
    return prepared.planned(prepared.problem)
}

/**
 * This preparation's plan over [model], the prepared model plus whatever row the caller appended to it.
 *
 * Planning keeps `preferFinite = false`. A preparation that closed every open side must not erase the
 * complete theory component selected for this invocation; routing a newly bounded model to the finite
 * lane remains the frontend's policy decision.
 */
internal fun PreparedSource.planned(model: Problem): OpenSourcePreparation.Planned {
    val plan = model.componentPlan()
    require(
        plan.theoryPipeline != ProblemPipeline.FINITE_CP &&
            plan.theoryPipeline != ProblemPipeline.UNSUPPORTED_OPEN,
    ) {
        "source preparation left the model without an open-theory route"
    }
    return OpenSourcePreparation.Planned(this, model, plan)
}
