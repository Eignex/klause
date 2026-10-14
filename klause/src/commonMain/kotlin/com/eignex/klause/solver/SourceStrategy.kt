package com.eignex.klause.solver

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.CompiledSchema
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.portfolio.LeafRealCompletion
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryOptimum
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryStats
import com.eignex.klause.solver.pipeline.PreparedExecutionKind
import com.eignex.klause.solver.pipeline.PreparedPlanDecline
import com.eignex.klause.solver.pipeline.QuadraticOptimizationExecution
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.admission
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.minimize
import com.eignex.klause.solver.pipeline.optimize
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.pipeline.polynomialRequest
import com.eignex.klause.solver.pipeline.preparePolynomialExecutionPlan
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation

/**
 * Source-aware strategy selected by [BacktrackSolver.source] or [LocalSearchSolver.source].
 * Each operation arranges shared preparation and exact completion over [source], with private invocation state.
 * Published coordinates retain the source namespaces, including exact continuous and wide integer values.
 * The source arrays and factor payloads are borrowed read-only for this strategy's lifetime.
 * Finite sessions, enumeration and resumable tuning remain on the finite solver facades.
 */
class SourceStrategy internal constructor(
    /** Original model, before strategy projections or source-safe bound preparation. */
    val source: Problem,
    /** Complete traversal or heuristic execution, without changing source constraints. */
    val engine: FiniteEngine,
    /** Captured schema names and typed source locations, when constructed from a schema or compilation. */
    val compiledSchema: CompiledSchema? = null,
    /** Factor mechanisms selected afresh for each operation's canonical source preparation. */
    val projectionProviders: FactorProjectionProviders = (source as? BakedProblem)?.projectionProviders
        ?: FactorProjectionProviders.Default,
) {
    init {
        require(engine == FiniteEngine.BACKTRACK || engine == FiniteEngine.LOCAL_SEARCH)
        require(compiledSchema == null || compiledSchema.problem === source)
    }

    /** Use this provider bundle for subsequent invocations over the same immutable source. */
    fun withProjectionProviders(providers: FactorProjectionProviders): SourceStrategy =
        SourceStrategy(source, engine, compiledSchema, providers)

    /**
     * Solve through the shared source route. [polynomial] retains finite strategy controls and arithmetic limits;
     * [theory] supplies open-linear controls. Extra CP components, open-source assumption pins and finite seeding
     * hooks explicitly decline. Deduction scopes and tolerance acceptance also decline; exact polynomial LS
     * declines finite seeding and event hooks. A heuristic rejection never refutes the source.
     */
    fun solve(
        polynomial: PolynomialTheoryParams = PolynomialTheoryParams(),
        theory: TheoryParams = TheoryParams(),
    ): OpenTheoryResult {
        val scope = scope(polynomial, theory)
        if (scope.stop()) return scope.unknown()
        if (!scope.supportsSource()) return scope.unsupported()
        val route = source.pipelineRoute(
            projectionProviders, routePureRealToTheory = true,
            boundCancellation = scope.stop,
        )
        if (scope.stop()) return scope.unknown()
        val result = when (route) {
            is SourceProblemRoute.PolynomialTheory -> {
                if (!scope.supportsPolynomial(engine)) return scope.unsupported()
                OpenTheoryPipeline.execute(route.request, scope.polynomial, engine).result
            }
            is SourceProblemRoute.Finite -> finiteSolve(route, scope)
            is SourceProblemRoute.OpenTheory -> {
                if (!scope.supportsOpen(engine)) {
                    return scope.unsupported()
                }
                (OpenTheoryPipeline.execute(route.request, scope.theory, engine) as OpenTheoryExecution.Satisfy).result
            }
            is SourceProblemRoute.UnsupportedOpen -> {
                if (route.preparedPlan.decline == PreparedPlanDecline.RESOURCE) {
                    return OpenTheoryResult.Unknown(TerminationReason.BudgetExhausted, SolveStats.EMPTY)
                }
                if (!scope.supportsOpen(engine)) {
                    return scope.unsupported()
                }
                (OpenTheoryPipeline.searchWithoutTheory(
                    route.preparedPlan, scope.theory, null, engine,
                ) as OpenTheoryExecution.Satisfy).result
            }
            SourceProblemRoute.Refuted -> OpenTheoryResult.Unsat(SolveStats.EMPTY)
        }
        return if (scope.stop()) scope.unknown(result.stats) else result
    }

    /** Solve with complete finite controls, retaining [polynomial]'s arithmetic limits and common assumptions. */
    fun solve(
        params: BacktrackParams,
        polynomial: PolynomialTheoryParams = PolynomialTheoryParams(),
        theory: TheoryParams = TheoryParams(),
    ): OpenTheoryResult {
        require(engine == FiniteEngine.BACKTRACK) { "BacktrackParams require a backtracking source strategy" }
        return solve(polynomial.copy(strategy = polynomial.strategy.copy(backtrack = params)), theory)
    }

    /** Solve with finite heuristic controls, retaining [polynomial]'s arithmetic limits and common assumptions. */
    fun solve(
        params: LocalSearchParams,
        polynomial: PolynomialTheoryParams = PolynomialTheoryParams(),
        theory: TheoryParams = TheoryParams(),
    ): OpenTheoryResult {
        require(engine == FiniteEngine.LOCAL_SEARCH) { "LocalSearchParams require a local-search source strategy" }
        return solve(polynomial.copy(strategy = polynomial.strategy.copy(localSearch = params)), theory)
    }

    /**
     * Optimize an affine objective over bounded integer declarations and exact continuous constraints.
     * The lossless shared bridge retains exact values, objective direction, bounds and attainment results.
     * Open integer optimization uses [optimizeOpen]; an unsupported polynomial encoding explicitly declines.
     */
    fun optimize(
        objective: LinearObjective,
        polynomial: PolynomialTheoryParams = PolynomialTheoryParams(),
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    ): QuadraticOptimizationExecution {
        val scope = scope(polynomial, TheoryParams())
        if (scope.stop()) return unsupportedQuadratic(sense, scope.unknown().reason)
        if (!scope.supportsPolynomial(engine)) return unsupportedQuadratic(sense)
        val plan = source.preparePolynomialExecutionPlan(providers = projectionProviders, cancellation = scope.stop)
            .withObjective(objective, maximize = sense == ObjectiveSense.MAXIMIZE)
        if (plan.kind != PreparedExecutionKind.POLYNOMIAL ||
            !plan.capabilities.admission(engine, ExecutionOperation.OPTIMIZE).runnable
        ) return unsupportedQuadratic(sense)
        return OpenTheoryPipeline.optimize(plan.polynomialRequest(), objective, scope.polynomial, engine, sense)
    }

    /** Optimize with complete finite controls and the shared exact affine-objective bridge. */
    fun optimize(
        objective: LinearObjective,
        params: BacktrackParams,
        polynomial: PolynomialTheoryParams = PolynomialTheoryParams(),
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    ): QuadraticOptimizationExecution {
        require(engine == FiniteEngine.BACKTRACK) { "BacktrackParams require a backtracking source strategy" }
        return optimize(objective, polynomial.copy(strategy = polynomial.strategy.copy(backtrack = params)), sense)
    }

    /** Optimize with finite heuristic controls and the shared exact affine-objective bridge. */
    fun optimize(
        objective: LinearObjective,
        params: LocalSearchParams,
        polynomial: PolynomialTheoryParams = PolynomialTheoryParams(),
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    ): QuadraticOptimizationExecution {
        require(engine == FiniteEngine.LOCAL_SEARCH) { "LocalSearchParams require a local-search source strategy" }
        return optimize(objective, polynomial.copy(strategy = polynomial.strategy.copy(localSearch = params)), sense)
    }

    /** Optimize a captured exact quadratic objective over the same bounded-integer source and real constraints. */
    fun optimize(
        objective: QuadraticObjectiveSpec,
        polynomial: PolynomialTheoryParams = PolynomialTheoryParams(),
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    ): QuadraticOptimizationExecution {
        val scope = scope(polynomial, TheoryParams())
        if (scope.stop()) return unsupportedQuadratic(sense, scope.unknown().reason)
        if (!scope.supportsPolynomial(engine)) return unsupportedQuadratic(sense)
        val plan = source.preparePolynomialExecutionPlan(providers = projectionProviders, cancellation = scope.stop)
            .withObjective(objective, sense)
        if (plan.kind != PreparedExecutionKind.POLYNOMIAL ||
            !plan.capabilities.admission(engine, ExecutionOperation.OPTIMIZE).runnable
        ) return unsupportedQuadratic(sense)
        return OpenTheoryPipeline.minimize(plan.polynomialRequest(), objective, scope.polynomial, engine, sense)
    }

    /**
     * Optimize a supported open-integer linear source through the existing native linear execution.
     * Native costs and bounds retain minimization sense; [sense] selects the source objective direction.
     * Unsupported fragment or invocation controls return the native incomplete result, without finite boxing.
     */
    fun optimizeOpen(
        objective: LinearObjective,
        polynomial: PolynomialTheoryParams = PolynomialTheoryParams(),
        theory: TheoryParams = TheoryParams(),
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    ): OpenTheoryExecution {
        val scope = scope(polynomial, theory)
        fun incomplete(reason: TerminationReason): OpenTheoryExecution = OpenTheoryExecution.Optimize(
            OpenTheoryOptimum.Bounded(null, null, reason, SolveStats.EMPTY),
        )
        if (scope.stop()) return incomplete(scope.unknown().reason)
        if (!scope.supportsOpen(engine)) return incomplete(TerminationReason.Unsupported)
        val route = source.pipelineRoute(
            projectionProviders, objective, maximize = sense == ObjectiveSense.MAXIMIZE,
            routePureRealToTheory = true, routeLinearToTheory = true, boundCancellation = scope.stop,
        )
        if (scope.stop()) return incomplete(scope.unknown().reason)
        return when (route) {
            is SourceProblemRoute.OpenTheory -> OpenTheoryPipeline.execute(route.request, scope.theory, engine)
            is SourceProblemRoute.UnsupportedOpen -> OpenTheoryPipeline.searchWithoutTheory(
                route.preparedPlan, scope.theory,
                if (sense == ObjectiveSense.MAXIMIZE) objective.negated() else objective, engine,
                maximize = sense == ObjectiveSense.MAXIMIZE,
            )
            SourceProblemRoute.Refuted -> OpenTheoryExecution.Optimize(OpenTheoryOptimum.Infeasible(SolveStats.EMPTY))
            else -> incomplete(TerminationReason.Unsupported)
        }
    }

    private fun unsupportedQuadratic(
        sense: ObjectiveSense,
        reason: TerminationReason = TerminationReason.Unsupported,
    ): QuadraticOptimizationExecution = QuadraticOptimizationExecution(
        QuadraticOptimizationResult.Unknown(null, reason, sense, SolveStats.EMPTY),
        PolynomialTheoryStats(), 0, 0,
    )

    private fun finiteSolve(route: SourceProblemRoute.Finite, scope: SourceInvocation): OpenTheoryResult {
        if (!route.preparedPlan.capabilities.admission(engine).runnable) return scope.unsupported()
        val preparation = FinitePipeline.prepare(FinitePipelineRequest(
            problem = route.problem, engine = engine, preparedPlan = route.preparedPlan,
            presolveConfig = PresolveConfig.NONE, explicitPresolveConfig = true, solutionSetSensitive = true,
            cancellation = scope.stop,
        ))
        if (scope.stop()) return scope.unknown()
        if (preparation.presolve?.infeasible == true) return OpenTheoryResult.Unsat(SolveStats.EMPTY)
        val model = preparation.executableProblem()
        val solved = when (engine) {
            FiniteEngine.BACKTRACK -> BacktrackSolver(model).solve(scope.backtrack)
            FiniteEngine.LOCAL_SEARCH -> LocalSearchSolver(
                model, completion = if (model.numRealVars > 0) LeafRealCompletion(model, null) else null,
            ).solve(scope.localSearch)
            else -> error("unsupported source strategy $engine")
        }
        return when (solved) {
            is SolveResult.Sat -> OpenTheoryResult.Sat(
                OpenTheoryAssignment.Sampled(preparation.reconstruct(solved.assignment)), solved.stats,
            )
            is SolveResult.Unsat -> OpenTheoryResult.Unsat(solved.stats)
            is SolveResult.Unknown -> OpenTheoryResult.Unknown(solved.reason, solved.stats)
        }
    }

    private fun scope(polynomial: PolynomialTheoryParams, theory: TheoryParams): SourceInvocation {
        val selected = if (engine == FiniteEngine.BACKTRACK) polynomial.strategy.backtrack.assumptions
        else polynomial.strategy.localSearch.assumptions
        val assumptions = polynomial.strategy.assumptions.mergedWith(selected)
        val selectedStop = if (engine == FiniteEngine.BACKTRACK) polynomial.strategy.backtrack.cancellation
        else polynomial.strategy.localSearch.cancellation
        val stop = polynomial.timeout or polynomial.cancellation or
            theory.timeout or theory.cancellation or selectedStop
        val backtrack = polynomial.strategy.backtrack.copy(assumptions = assumptions, cancellation = stop)
        val localSearch = polynomial.strategy.localSearch.copy(assumptions = assumptions, cancellation = stop)
        val selectedBudget = if (engine == FiniteEngine.BACKTRACK) backtrack.nodeBudget else localSearch.nodeBudget
        require(theory.nodeBudget == null || selectedBudget == null || theory.nodeBudget === selectedBudget) {
            "source execution requires one shared node allowance"
        }
        val open = when (engine) {
            FiniteEngine.BACKTRACK -> theory.copy(
                cancellation = stop,
                maxDecisions = minOf(
                    theory.maxDecisions, backtrack.maxDecisions, backtrack.maxInstructions ?: Long.MAX_VALUE,
                ),
                nodeBudget = selectedBudget ?: theory.nodeBudget,
                randomSeed = backtrack.randomSeed ?: theory.randomSeed,
            )
            FiniteEngine.LOCAL_SEARCH -> theory.copy(
                cancellation = stop,
                maxFlips = minOf(theory.maxFlips, localSearch.maxFlips),
                maxInstructions = minOf(
                    theory.maxInstructions ?: Long.MAX_VALUE, localSearch.maxInstructions ?: Long.MAX_VALUE,
                ).takeIf { it != Long.MAX_VALUE },
                nodeBudget = selectedBudget ?: theory.nodeBudget,
                randomSeed = localSearch.randomSeed ?: theory.randomSeed,
            )
            else -> error("unsupported source strategy $engine")
        }
        return SourceInvocation(
            assumptions, stop, polynomial.timeout or theory.timeout, backtrack, localSearch, open,
            polynomial.copy(cancellation = stop, strategy = polynomial.strategy.copy(
                backtrack = backtrack, localSearch = localSearch, assumptions = assumptions,
            )),
        )
    }

    private class SourceInvocation(
        val assumptions: Assumptions,
        val stop: Cancellation,
        val timeout: Cancellation,
        val backtrack: BacktrackParams,
        val localSearch: LocalSearchParams,
        val theory: TheoryParams,
        val polynomial: PolynomialTheoryParams,
    ) {
        fun unknown(stats: SolveStats = SolveStats.EMPTY): OpenTheoryResult.Unknown = OpenTheoryResult.Unknown(
            if (timeout() || stop.deadline()?.hasPassedNow() == true) TerminationReason.Timeout
            else TerminationReason.Cancelled,
            stats,
        )
        fun supportsSource(): Boolean = assumptions.deductions.isEmpty &&
            backtrack.toleranceCheck == null && backtrack.componentFactory == null
        fun supportsPolynomial(engine: FiniteEngine): Boolean = supportsSource() &&
            (engine != FiniteEngine.LOCAL_SEARCH || supportsLocalHooks())
        private fun supportsLocalHooks(): Boolean = localSearch.initialAssignment == null &&
            localSearch.pooledIncumbents == null && localSearch.lsObjective == null && localSearch.onEvent == null
        fun supportsOpen(engine: FiniteEngine): Boolean =
            supportsSource() && assumptions.isEmpty && when (engine) {
                FiniteEngine.BACKTRACK -> backtrack.nodeBudget == null && backtrack.onEvent == null
                FiniteEngine.LOCAL_SEARCH -> supportsLocalHooks()
                else -> false
            }
        fun unsupported(): OpenTheoryResult.Unknown =
            OpenTheoryResult.Unknown(TerminationReason.Unsupported, SolveStats.EMPTY)
    }
}
