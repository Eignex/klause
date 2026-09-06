package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.OpenPresolveResult
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.closeOpenBounds
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.util.Cancellation

/**
 * What the open lane's preparation made of a request, with no theory run.
 *
 * @property model the prepared model over the bounds the closing proved, or the request's own where
 *  nothing fired.
 * @property stats what the source passes did, or `null` where they left the model alone.
 * @property closedSides how many open sides the bound closing proved a bound for.
 * @property infeasible whether preparation refuted the model, which is the model's own verdict.
 */
class OpenPreparation internal constructor(
    val model: Problem,
    val stats: PresolveStats?,
    val closedSides: Int,
    val infeasible: Boolean,
)

/** A complete open-model solve request selected by the orchestration layer. */
class OpenTheoryRequest internal constructor(
    /** Source model whose complete theory route is selected by this pipeline. */
    val model: Problem,
    /** Null requests satisfiability; a value requests optimization. */
    val objective: LinearObjective? = null,
    /** Whether [objective] is maximized rather than minimized. */
    val maximize: Boolean = false,
    /**
     * Decomposition of the untransformed [model], which is what a frontend routes and renders by.
     *
     * The plan the theory executes under is selected again after source-safe preparation, from the model
     * that phase produced — see [OpenSourcePreparation].
     */
    internal val componentPlan: ComponentPlan,
    /** Source-safe presolve configuration. */
    internal val presolveConfig: PresolveConfig = PresolveConfig.DEFAULT,
    /** Whether source preparation must preserve the complete solution set. */
    internal val solutionSetSensitive: Boolean = false,
    /** Cancellation token for source preparation. */
    internal val presolveCancellation: Cancellation = Cancellation.Never,
    /** Optional preparation allowance shared with source passes. */
    internal val presolveBudget: PresolveBudget? = null,
) {
    /** Build a request and select its source decomposition. */
    constructor(
        model: Problem,
        objective: LinearObjective? = null,
        maximize: Boolean = false,
    ) : this(model, objective, maximize, model.componentPlan())

    /** Complete open-theory route [model] declares, before source-safe preparation transforms it. */
    val route: ProblemPipeline get() = componentPlan.theoryPipeline

    /** Return this request with the caller's resolved source-preparation policy. */
    fun withPresolve(
        config: PresolveConfig,
        solutionSetSensitive: Boolean = false,
        cancellation: Cancellation = Cancellation.Never,
        budget: PresolveBudget? = null,
    ): OpenTheoryRequest = OpenTheoryRequest(
        model,
        objective,
        maximize,
        componentPlan,
        config,
        solutionSetSensitive,
        cancellation,
        budget,
    )
}

/** The common execution result for a complete open-model request. */
sealed interface OpenTheoryExecution {
    /** Satisfiability result for a request without an objective. */
    data class Satisfy(
        /** The satisfiability verdict. */
        val result: OpenTheoryResult,
    ) : OpenTheoryExecution

    /** Optimization result for a request with an objective. */
    data class Optimize(
        /** The optimization verdict. */
        val result: OpenTheoryOptimum,
    ) : OpenTheoryExecution
}

/** Executes an open-model request without exposing individual theory implementations to callers. */
object OpenTheoryPipeline {
    /**
     * Run what the open lane does before any theory sees the model, and stop: the source-safe passes, then
     * the bound closing.
     *
     * For a caller inspecting or A/B-comparing a presolve configuration. On an open model this phase is
     * the only reduction that runs before a route is chosen, and its effect on the open sides is what
     * decides how much the theory is left to do — neither of which a solving run reports.
     */
    fun prepare(request: OpenTheoryRequest): OpenPreparation {
        val source = request.model.prepareOpenSource(
            request.objective,
            request.presolveConfig,
            request.solutionSetSensitive,
            request.presolveCancellation,
            request.presolveBudget,
        )
        val prepared = source.prepared
        val stats = prepared.stats.takeIf { prepared.changed || it.infeasible }
        val planned = source as? OpenSourcePreparation.Planned
            ?: return OpenPreparation(prepared.problem, stats, closedSides = 0, infeasible = true)
        return when (val closed = planned.model.closeOpenBounds(request.presolveCancellation)) {
            OpenPresolveResult.Refuted ->
                OpenPreparation(planned.model, stats, closedSides = 0, infeasible = true)

            is OpenPresolveResult.Tightened ->
                OpenPreparation(closed.spec, stats, closed.closedSides, infeasible = false)
        }
    }

    /** Execute [request] through its selected complete theory route. */
    fun execute(request: OpenTheoryRequest, params: TheoryParams = TheoryParams()): OpenTheoryExecution {
        val objective = request.objective
        if (objective == null) {
            return OpenTheoryExecution.Satisfy(
                OpenTheoryEngine(
                    request.model,
                    request.route,
                    request.presolveConfig,
                    request.solutionSetSensitive,
                    request.presolveCancellation,
                    request.presolveBudget,
                ).solve(params),
            )
        }
        val driven = if (request.maximize) objective.negated() else objective
        return OpenTheoryExecution.Optimize(
            OpenTheoryMinimizer(
                request.model,
                driven,
                request.presolveConfig,
                request.solutionSetSensitive,
                request.presolveCancellation,
                request.presolveBudget,
            ).minimize(params),
        )
    }
}

/** The objective whose minimum is this one's maximum. */
private fun LinearObjective.negated(): LinearObjective = LinearObjective(
    boolWeights = LongArray(boolWeights.size) { -boolWeights[it] },
    intCoefficients = LongArray(intCoefficients.size) { -intCoefficients[it] },
    constant = -constant,
    realCoefficients = DoubleArray(realCoefficients.size) { -realCoefficients[it] },
)
