package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.OpenPresolveResult
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.closeOpenBounds
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
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
    /** Shared ownership preparation, retaining source identity through routing. */
    val preparedPlan: PreparedSourcePlan = model.preparedWithComponents(
        componentPlan, objective = objective, maximize = maximize,
    ),
    internal val linearContext: SourceProofContext? = null,
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
        preparedPlan,
        linearContext,
    )

    /** Bind the source owner's complete exact affine premises, objective and original-coordinate semantics. */
    fun withLinearAuthority(context: SourceProofContext): OpenTheoryRequest {
        require(objective != null && context.affineObjectiveAvailable)
        require(context.scope.objective?.sense == if (maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE)
        val coordinates = buildSet {
            repeat(preparedPlan.sourceBoolCount) { add(SourceCoordinate(SourceCoordinate.Kind.Boolean, it)) }
            repeat(preparedPlan.sourceIntCount) { add(SourceCoordinate(SourceCoordinate.Kind.Integer, it)) }
            repeat(preparedPlan.sourceRealCount) { add(SourceCoordinate(SourceCoordinate.Kind.Real, it)) }
        }
        require(context.coordinates == coordinates) { "linear authority requires original source coordinates" }
        return OpenTheoryRequest(model, objective, maximize, componentPlan, presolveConfig, solutionSetSensitive,
            presolveCancellation, presolveBudget, preparedPlan, context)
    }
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
    ) : OpenTheoryExecution {
        private var retainedPublication: SourceIncumbentPublication? = null

        /** Latest accepted source point and exact value, separate from a terminal's private witness. */
        val acceptedIncumbent: SourceIncumbentPublication? get() = retainedPublication

        internal fun retainPublication(publication: SourceIncumbentPublication?) {
            retainedPublication = publication
        }
    }
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
    ): OpenTheoryExecution = executePortfolio(request, params, cores, null)

    /** Run the open portfolio and observe each verified exact source improvement after installation. */
    fun executePortfolio(
        request: OpenTheoryRequest,
        params: TheoryParams,
        cores: Int,
        onAccepted: ((SourceIncumbentPublication) -> Unit)?,
    ): OpenTheoryExecution = observed(onAccepted) { publication ->
        portfolioExecution(request, params, cores, sourceObjective(request, params), publication)
    }

    private fun portfolioExecution(
        request: OpenTheoryRequest,
        params: TheoryParams,
        cores: Int,
        values: OpenSourceObjective?,
        publication: SourcePublicationObserver,
        completeOnly: Boolean = false,
    ): OpenTheoryExecution {
        val portfolio = OpenPortfolio(request.model, request, params, seed = params.randomSeed, lanes = cores,
            sourceObjective = values, publication = publication, completeOnly = completeOnly)
        val stop = params.cancellation or params.timeout
        val objective = request.minimizedObjective ?: return OpenTheoryExecution.Satisfy(portfolio.solve(stop))
        return OpenTheoryExecution.Optimize(portfolio.minimize(objective, integralMinimizer(request, objective), stop))
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
    ): OpenTheoryExecution = observed(null) { publication ->
        heuristicExecution(model, params, objective, cores, searchesContinuousOnly,
            objective?.let { OpenSourceObjective(it) }, publication)
    }

    private fun heuristicExecution(
        model: Problem,
        params: TheoryParams,
        objective: LinearObjective?,
        cores: Int,
        searchesContinuousOnly: Boolean,
        values: OpenSourceObjective?,
        publication: SourcePublicationObserver,
        preparedSource: PreparedSourcePlan? = null,
    ): OpenTheoryExecution {
        val prepared = preparedSource ?: model.prepareExecutionPlan(values?.original, values?.maximize ?: false)
        val portfolio = OpenPortfolio(model, request = null, params, lanes = cores,
            searchesContinuousOnly = searchesContinuousOnly, sourceObjective = values, publication = publication,
            preparedSource = prepared)
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
     * with exact-coordinate repair alone, which never refutes the model or proves an optimum. [FiniteEngine.ALNS]
     * adaptively selects repair block sizes. A portfolio runs on up to [cores] lanes.
     */
    fun execute(
        request: OpenTheoryRequest,
        params: TheoryParams,
        engine: FiniteEngine,
        cores: Int = 1,
    ): OpenTheoryExecution = execute(request, params, engine, cores, null)

    /**
     * Observe verified exact original-source improvements. Callback failures propagate after worker cleanup;
     * the returned optimization retains its latest accepted publication for final rendering.
     */
    fun execute(
        request: OpenTheoryRequest,
        params: TheoryParams,
        engine: FiniteEngine,
        cores: Int,
        onAccepted: ((SourceIncumbentPublication) -> Unit)?,
    ): OpenTheoryExecution = observed(onAccepted) { publication ->
        executeObserved(request, params, engine, cores, sourceObjective(request, params), publication)
    }

    private fun executeObserved(
        request: OpenTheoryRequest,
        params: TheoryParams,
        engine: FiniteEngine,
        cores: Int,
        values: OpenSourceObjective?,
        publication: SourcePublicationObserver,
    ): OpenTheoryExecution {
        val operation = if (request.objective == null) ExecutionOperation.SATISFY else ExecutionOperation.OPTIMIZE
        if (!request.preparedPlan.capabilities.admission(engine, operation).runnable) {
            return unsupportedExecution(request.objective)
        }
        return when (engine) {
            FiniteEngine.MIXED -> portfolioExecution(request, params, cores, values, publication)
            FiniteEngine.BACKTRACK -> if (cores > 1) {
                portfolioExecution(request, params, cores, values, publication, completeOnly = true)
            } else executeDirect(request, params, values, publication)
            FiniteEngine.FIXED -> executeDirect(request, params, values, publication)
            FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS -> linearNeighborhoodInvocation(
                request.model, params, request.minimizedObjective, engine == FiniteEngine.ALNS, cores,
                request.preparedPlan, values, publication,
            )
        }
    }

    private fun unsupportedExecution(objective: LinearObjective?): OpenTheoryExecution = if (objective == null) {
        OpenTheoryExecution.Satisfy(OpenTheoryResult.Unknown(TerminationReason.Unsupported, SolveStats.EMPTY))
    } else {
        OpenTheoryExecution.Optimize(OpenTheoryOptimum.Bounded(
            null, null, TerminationReason.Unsupported, SolveStats.EMPTY))
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
    ): OpenTheoryExecution = searchWithoutTheory(model, params, objective, engine, cores, null)

    /** Observe exact improvements for a heuristic-only minimized objective. */
    fun searchWithoutTheory(
        model: Problem,
        params: TheoryParams,
        objective: LinearObjective?,
        engine: FiniteEngine,
        cores: Int,
        onAccepted: ((SourceIncumbentPublication) -> Unit)?,
    ): OpenTheoryExecution = searchWithoutTheory(model, params, objective, engine, cores, false, onAccepted)

    /**
     * Search a minimized execution objective while publishing its original direction. When [maximize] is true,
     * [objective] is the negated source objective; accepted values restore that source direction.
     */
    fun searchWithoutTheory(
        model: Problem,
        params: TheoryParams,
        objective: LinearObjective?,
        engine: FiniteEngine,
        cores: Int,
        maximize: Boolean,
        onAccepted: ((SourceIncumbentPublication) -> Unit)?,
    ): OpenTheoryExecution = observed(onAccepted) { publication ->
        val values = objective?.let { OpenSourceObjective(it, if (maximize) it.negated() else it, maximize) }
        heuristicObserved(model, params, objective, engine, cores, values, publication)
    }

    private fun heuristicObserved(
        model: Problem,
        params: TheoryParams,
        objective: LinearObjective?,
        engine: FiniteEngine,
        cores: Int,
        values: OpenSourceObjective?,
        publication: SourcePublicationObserver,
    ): OpenTheoryExecution {
        val prepared = model.preparedWithComponents(model.componentPlan(), objective = values?.original,
            maximize = values?.maximize ?: false)
        val operation = if (objective == null) ExecutionOperation.SATISFY else ExecutionOperation.OPTIMIZE
        if (!prepared.capabilities.admission(engine, operation).runnable) return unsupportedExecution(objective)
        return when (engine) {
            FiniteEngine.MIXED -> heuristicExecution(model, params, objective, cores, false,
                values, publication, prepared)
            FiniteEngine.LOCAL_SEARCH -> heuristicExecution(model, params, objective, cores, true,
                values, publication, prepared)
            FiniteEngine.BACKTRACK, FiniteEngine.FIXED -> unsupportedExecution(objective)
            FiniteEngine.ALNS -> linearNeighborhoodInvocation(model, params, objective, adaptive = true, cores,
                preparedSource = prepared, sourceObjective = values, publication = publication)
        }
    }

    /** Execute [request] through its selected complete theory route. */
    fun execute(request: OpenTheoryRequest, params: TheoryParams = TheoryParams()): OpenTheoryExecution =
        execute(request, params, null)

    /** Execute the complete theory and observe its verified exact original-source improvements. */
    fun execute(
        request: OpenTheoryRequest,
        params: TheoryParams,
        onAccepted: ((SourceIncumbentPublication) -> Unit)?,
    ): OpenTheoryExecution = observed(onAccepted) { publication ->
        executeDirect(request, params, sourceObjective(request, params), publication)
    }

    private fun executeDirect(
        request: OpenTheoryRequest,
        params: TheoryParams,
        values: OpenSourceObjective?,
        publication: SourcePublicationObserver,
    ): OpenTheoryExecution {
        val objective = request.minimizedObjective
        if (objective == null) return OpenTheoryExecution.Satisfy(engineFor(request).solve(params))
        val context = values?.context
        val binding = EngineInvocation(request.preparedPlan, ModelIdentity.of(request.model, objective), objective,
            scope = context?.scope, condition = context?.scope?.condition)
        if (context == null) {
            val integral = integralMinimizer(request, objective)
            if (integral != null) {
                return integral.descent(params).use { descent ->
                    OpenTheoryExecution.Optimize(requireNotNull(descent.runSlice(
                        params.cancellation or params.timeout, Long.MAX_VALUE, -1,
                    ) { assignment, _ -> values?.publish(assignment.toSample(request.model, binding), publication) }))
                }
            }
        }
        return RationalTheoryDescent(request.model, objective, params, context,
            publicationInvocation = binding).use { descent ->
            val result = requireNotNull(descent.runSlice(
                params.cancellation or params.timeout, Long.MAX_VALUE, -1,
            ) { incumbent -> values?.publish(incumbent.sample, publication) })
            val authority = descent.linearResult
            val folded = if (authority == null) result.openOptimum(objective) else requireNotNull(values).completed(
                authority, params.cancellation or params.timeout,
                if (params.timeout()) TerminationReason.Timeout else TerminationReason.Cancelled)
            OpenTheoryExecution.Optimize(folded)
        }
    }

    private fun sourceObjective(request: OpenTheoryRequest, params: TheoryParams): OpenSourceObjective? =
        request.objective?.let { original -> OpenSourceObjective(requireNotNull(request.minimizedObjective), original,
            request.maximize, request.linearAuthorityContext(params.cancellation or params.timeout),
            params.cancellation or params.timeout) }

    private fun observed(
        callback: ((SourceIncumbentPublication) -> Unit)?,
        action: (SourcePublicationObserver) -> OpenTheoryExecution,
    ): OpenTheoryExecution {
        val publication = SourcePublicationObserver(callback)
        return publication.execute { action(publication) }.also { execution ->
            if (execution is OpenTheoryExecution.Optimize) execution.retainPublication(publication.latest)
        }
    }

    /**
     * Whether a source-bound descent can execute [request]'s objective without leaving the admitted theories.
     * Complete affine source capture enables checked bounds, limits and source rays.
     */
    fun canMinimize(request: OpenTheoryRequest): Boolean = request.objective != null &&
        request.preparedPlan.capabilities.admission(ExecutionStrategy.COMPLETE, ExecutionOperation.OPTIMIZE).runnable

    private fun integralMinimizer(request: OpenTheoryRequest, objective: LinearObjective): OpenTheoryMinimizer? {
        if (objective.realCoefficientValues.any { it != 0.0 } ||
            objective.boolWeightValues.any { it != 0L }
        ) return null
        return minimizerFor(request, objective).takeIf { it.decidesEveryRound }
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
