package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation

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
    /** Execute [request] through its selected complete theory route. */
    fun execute(request: OpenTheoryRequest, params: TheoryParams = TheoryParams()): OpenTheoryExecution {
        // Preparation is a phase of this solve, so the solve's own stop reaches it: the request's
        // allowance is what bounds it, and the caller's deadline is what ends it.
        val preparation = Cancellation {
            request.presolveCancellation() || params.cancellation() || params.timeout()
        }
        val objective = request.objective
        if (objective == null) {
            return OpenTheoryExecution.Satisfy(
                OpenTheoryEngine(
                    request.model,
                    request.route,
                    request.presolveConfig,
                    request.solutionSetSensitive,
                    preparation,
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
                preparation,
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
