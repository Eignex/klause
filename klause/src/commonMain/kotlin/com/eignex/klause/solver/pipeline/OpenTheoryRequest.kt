package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.OpenPresolveResult
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.closeOpenBounds
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.hasActivity
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

    // Every phase below reads minimize-sense coefficients — source presolve pins a column by their sign —
    // so the request's sense is resolved once, here, rather than at each of them.
    internal val minimizedObjective: LinearObjective?
        get() = objective?.let { if (maximize) it.negated() else it }

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
     *
     * Inspection only: a pass may resolve a Boolean column away and leave it unconstrained, and the
     * reconstruction that recovers it stays with the preparation rather than travelling on the model. A
     * caller that wants a witness runs [execute], which lifts one before it leaves the route.
     */
    fun prepare(request: OpenTheoryRequest): OpenPreparation {
        val source = request.model.prepareOpenSource(
            request.minimizedObjective,
            request.presolveConfig,
            request.solutionSetSensitive,
            request.presolveCancellation,
            request.presolveBudget,
        )
        val prepared = source.prepared
        val sourceStats = prepared.stats.takeIf { prepared.changed || it.infeasible }
        val planned = source as? OpenSourcePreparation.Planned
            ?: return OpenPreparation(prepared.problem, sourceStats, closedSides = 0, infeasible = true)
        val lpStats = LpStatsSink(LpRoute.STANDALONE)
        val closed = planned.model.closeOpenBounds(request.presolveCancellation, lpStats)
        val closingStats = lpStats.snapshot()
        val stats = if (!closingStats.hasActivity()) {
            sourceStats
        } else {
            val base = sourceStats ?: PresolveStats()
            base.copy(lpStats = base.lpStats.mergedWith(closingStats))
        }
        return when (closed) {
            OpenPresolveResult.Refuted ->
                OpenPreparation(planned.model, stats, closedSides = 0, infeasible = true)

            is OpenPresolveResult.Tightened ->
                OpenPreparation(closed.spec, stats, closed.closedSides, infeasible = false)
        }
    }

    /**
     * Execute [request] on the open portfolio: its theory route as one arm — the descent when it optimizes — and
     * local-search arms over the source columns beside it, every local-search witness checked against the source
     * model. The arms run on up to [cores] lanes at once.
     */
    fun executePortfolio(
        request: OpenTheoryRequest,
        params: TheoryParams = TheoryParams(),
        cores: Int = 1,
    ): OpenTheoryExecution {
        val portfolio = OpenPortfolio(request.model, request, params, lanes = cores)
        val stop = params.cancellation or params.timeout
        val objective = request.minimizedObjective ?: return OpenTheoryExecution.Satisfy(portfolio.solve(stop))
        return OpenTheoryExecution.Optimize(portfolio.minimize(objective, minimizerFor(request, objective), stop))
    }

    /**
     * Search [model], an open model no theory decides, with local search alone. It can show the model satisfiable
     * and find incumbents for [objective], minimized, and never shows the model unsatisfiable or an incumbent optimal.
     * A model of continuous columns alone is searched only when [searchesContinuousOnly].
     */
    fun searchWithoutTheory(
        model: Problem,
        params: TheoryParams = TheoryParams(),
        objective: LinearObjective? = null,
        cores: Int = 1,
        searchesContinuousOnly: Boolean = false,
    ): OpenTheoryExecution {
        val portfolio =
            OpenPortfolio(model, request = null, params, lanes = cores, searchesContinuousOnly = searchesContinuousOnly)
        val stop = params.cancellation or params.timeout
        if (objective == null) return OpenTheoryExecution.Satisfy(portfolio.solve(stop))
        return OpenTheoryExecution.Optimize(portfolio.minimize(objective, minimizer = null, stop))
    }

    /** The engine that decides the satisfaction [request]. */
    internal fun engineFor(request: OpenTheoryRequest): OpenTheoryEngine = OpenTheoryEngine(
        request.model,
        request.route,
        request.presolveConfig,
        request.solutionSetSensitive,
        request.presolveCancellation,
        request.presolveBudget,
    )

    /**
     * Execute [request] the way [engine] asks: [FiniteEngine.MIXED] on the open portfolio, the theory beside local
     * search; [FiniteEngine.BACKTRACK] and [FiniteEngine.FIXED] through the theory alone; [FiniteEngine.LOCAL_SEARCH]
     * with local search alone, which never refutes the model or proves an optimum. [FiniteEngine.ALNS] has no open
     * route. A portfolio runs on up to [cores] lanes.
     */
    fun execute(
        request: OpenTheoryRequest,
        params: TheoryParams,
        engine: FiniteEngine,
        cores: Int = 1,
    ): OpenTheoryExecution =
        when (engine) {
            FiniteEngine.MIXED -> executePortfolio(request, params, cores)
            FiniteEngine.BACKTRACK, FiniteEngine.FIXED -> execute(request, params)
            FiniteEngine.LOCAL_SEARCH ->
                searchWithoutTheory(request.model, params, request.minimizedObjective, cores, searchesContinuousOnly = true)

            FiniteEngine.ALNS -> throw IllegalArgumentException("engine `${engine.id}` has no open-model route")
        }

    /**
     * Search [model], an open model no theory decides, the way [engine] asks: local search under
     * [FiniteEngine.MIXED] and [FiniteEngine.LOCAL_SEARCH], the latter also over continuous columns alone. A complete
     * engine has nothing to run on it, so the answer is unknown.
     */
    fun searchWithoutTheory(
        model: Problem,
        params: TheoryParams,
        objective: LinearObjective?,
        engine: FiniteEngine,
        cores: Int = 1,
    ): OpenTheoryExecution = when (engine) {
        FiniteEngine.MIXED -> searchWithoutTheory(model, params, objective, cores)

        FiniteEngine.LOCAL_SEARCH -> searchWithoutTheory(model, params, objective, cores, searchesContinuousOnly = true)

        FiniteEngine.BACKTRACK, FiniteEngine.FIXED -> {
            val stats = SolveStats.EMPTY
            if (objective == null) {
                OpenTheoryExecution.Satisfy(OpenTheoryResult.Unknown(TerminationReason.Unsupported, stats))
            } else {
                val unsearched = OpenTheoryOptimum.Bounded(null, null, TerminationReason.Unsupported, stats)
                OpenTheoryExecution.Optimize(unsearched)
            }
        }

        FiniteEngine.ALNS -> throw IllegalArgumentException("engine `${engine.id}` has no open-model route")
    }

    /** Execute [request] through its selected complete theory route. */
    fun execute(request: OpenTheoryRequest, params: TheoryParams = TheoryParams()): OpenTheoryExecution {
        val objective = request.minimizedObjective
        if (objective == null) return OpenTheoryExecution.Satisfy(engineFor(request).solve(params))
        return OpenTheoryExecution.Optimize(minimizerFor(request, objective).minimize(params))
    }

    /**
     * Whether the descent can minimize [request]'s objective: an integral one weighting no Boolean, whose bound row
     * leaves the model inside a complete open theory. A request it cannot minimize is left to local search.
     */
    fun canMinimize(request: OpenTheoryRequest): Boolean {
        val objective = request.minimizedObjective ?: return false
        if (objective.realCoefficients.any { it != 0.0 } || objective.boolWeights.any { it != 0L }) return false
        return minimizerFor(request, objective).decidesEveryRound
    }

    // The descent minimizing [objective], the request's own in minimized form.
    private fun minimizerFor(request: OpenTheoryRequest, objective: LinearObjective): OpenTheoryMinimizer =
        OpenTheoryMinimizer(
            request.model,
            objective,
            request.presolveConfig,
            request.solutionSetSensitive,
            request.presolveCancellation,
            request.presolveBudget,
        )
}
