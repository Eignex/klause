package com.eignex.klause.solver

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.compile.CompiledSchema
import com.eignex.klause.compile.bindResult
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toQuadraticObjectiveSpec
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionOptions
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FiniteSolveCallbacks
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.FiniteSolveRequest
import com.eignex.klause.solver.pipeline.FiniteSolveResult
import com.eignex.klause.solver.pipeline.FiniteSolveVerdict
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryOptimum
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryStats
import com.eignex.klause.solver.pipeline.PreparedPlanDecline
import com.eignex.klause.solver.pipeline.PreparedSourceInvocation
import com.eignex.klause.solver.pipeline.QuadraticOptimizationExecution
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionCallbacks
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceFiniteControls
import com.eignex.klause.solver.pipeline.SourceIncumbentPublication
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.admission
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.maximizeSourceObjective
import com.eignex.klause.solver.pipeline.withSourceSemantics
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter

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
    /** Default traversal or heuristic execution, without changing source constraints. */
    val engine: FiniteEngine,
    /** Captured schema names and typed source locations, when constructed from a schema or compilation. */
    val compiledSchema: CompiledSchema? = null,
    /** Factor mechanisms selected afresh for each operation's canonical source preparation. */
    val projectionProviders: FactorProjectionProviders = (source as? BakedProblem)?.projectionProviders
        ?: FactorProjectionProviders.Default,
) {
    init {
        require(engine == FiniteEngine.BACKTRACK || engine == FiniteEngine.LOCAL_SEARCH)
        require(compiledSchema == null || compiledSchema.sourceBinding.originalProblem === source)
    }

    /** Use this provider bundle for subsequent invocations over the same immutable source. */
    fun withProjectionProviders(providers: FactorProjectionProviders): SourceStrategy =
        SourceStrategy(source, engine, compiledSchema, providers)

    /** Run an explicit engine with common options and the containing source allowances. */
    fun solve(
        engine: FiniteEngine,
        executionOptions: ExecutionOptions = ExecutionOptions(),
        cores: Int = 1,
        timeout: Cancellation = Cancellation.Never,
        cancellation: Cancellation = Cancellation.Never,
        nodeBudget: NodeBudget? = null,
    ): OpenTheoryResult {
        val stop = timeout or cancellation
        fun stopped(stats: SolveStats = SolveStats.EMPTY): OpenTheoryResult.Unknown = OpenTheoryResult.Unknown(
            if (timeout() || stop.deadline()?.hasPassedNow() == true) TerminationReason.Timeout
            else TerminationReason.Cancelled, stats,
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            sourceAttachment(), ExecutionOperation.SATISFY, engine,
            providers = projectionProviders, cancellation = stop,
        ))
        compiledSchema?.sourceBinding?.selectView(invocation)
        var finiteWitness: Sample? = null
        val execution = SourceSetup.execute(
            SourceExecutionRequest(
                invocation, executionOptions, cores = cores, timeout = timeout, cancellation = cancellation,
                nodeBudget = nodeBudget,
            ),
            SourceExecutionCallbacks(finite = FiniteSolveCallbacks({ finiteWitness = it }, {})),
        )
        val result = when (execution) {
            is SourceExecutionResult.Polynomial -> execution.result.result
            is SourceExecutionResult.Transcendental -> execution.result.result
            is SourceExecutionResult.Linear ->
                (execution.result as OpenTheoryExecution.Satisfy).result
            is SourceExecutionResult.Finite -> {
                val outcome = execution.result.outcome as? FiniteSolveOutcome.Completed
                    ?: return OpenTheoryResult.Unknown(TerminationReason.Unsupported, SolveStats.EMPTY)
                        .withSourceSemantics(source.semantics)
                when (outcome.verdict) {
                    FiniteSolveVerdict.SAT -> OpenTheoryResult.Sat(
                        OpenTheoryAssignment.Sampled(requireNotNull(outcome.bestSample ?: finiteWitness)),
                        outcome.stats,
                    )
                    FiniteSolveVerdict.UNSAT -> OpenTheoryResult.Unsat(outcome.stats)
                    else -> OpenTheoryResult.Unknown(
                        (execution.result.nativeSourceResult as? SolveResult.Unknown)?.reason
                            ?: if (stop()) stopped().reason
                            else if (invocation.floatGrid != null) TerminationReason.Unsupported
                            else TerminationReason.BudgetExhausted,
                        outcome.stats,
                    )
                }
            }
            is SourceExecutionResult.Refuted -> OpenTheoryResult.Unsat(SolveStats.EMPTY)
            is SourceExecutionResult.Declined -> OpenTheoryResult.Unknown(execution.reason, SolveStats.EMPTY)
            is SourceExecutionResult.Prepared, is SourceExecutionResult.Numerical, is SourceExecutionResult.Quadratic ->
                OpenTheoryResult.Unknown(TerminationReason.Unsupported, SolveStats.EMPTY)
        }
        if (stop()) return stopped(result.stats).withSourceSemantics(source.semantics)
        val sample = when (val assignment = (result as? OpenTheoryResult.Sat)?.assignment) {
            is OpenTheoryAssignment.Sampled -> assignment.sample
            is OpenTheoryAssignment.Difference -> assignment.sample
            else -> null
        }
        val bound = if (sample != null && compiledSchema != null) {
            compiledSchema.sourceBinding.bindOriginalSample(sample, stop)
                ?: return stopped(result.stats).withSourceSemantics(source.semantics)
        } else null
        return (if (bound != null && result is OpenTheoryResult.Sat) {
            result.copy(assignment = OpenTheoryAssignment.Sampled(bound))
        } else result).withSourceSemantics(source.semantics)
    }

    /** Optimize an affine source objective with an explicit engine and common execution controls. */
    fun optimize(
        objective: LinearObjective,
        engine: FiniteEngine,
        executionOptions: ExecutionOptions = ExecutionOptions(),
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
        cores: Int = 1,
        timeout: Cancellation = Cancellation.Never,
        cancellation: Cancellation = Cancellation.Never,
        nodeBudget: NodeBudget? = null,
    ): QuadraticOptimizationExecution {
        val original = compiledSchema?.sourceBinding?.originalObjective(objective) ?: run {
            require(objective.sourcePairing == null) {
                "compiled helper objective requires its compiled source strategy"
            }
            objective.toQuadraticObjectiveSpec()
        }
        return optimize(original, engine, executionOptions, sense, cores, timeout, cancellation, nodeBudget)
    }

    /** Optimize an exact quadratic source objective with an explicit engine and common execution controls. */
    fun optimize(
        objective: QuadraticObjectiveSpec,
        engine: FiniteEngine,
        executionOptions: ExecutionOptions = ExecutionOptions(),
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
        cores: Int = 1,
        timeout: Cancellation = Cancellation.Never,
        cancellation: Cancellation = Cancellation.Never,
        nodeBudget: NodeBudget? = null,
    ): QuadraticOptimizationExecution {
        val stop = timeout or cancellation
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            sourceAttachment(), engine, objective, sense, projectionProviders, stop, stop,
            requiresQuadraticCompletion = true,
        ))
        val execution = SourceSetup.execute(SourceExecutionRequest(
            invocation, executionOptions, cores = cores, timeout = timeout, cancellation = cancellation,
            nodeBudget = nodeBudget,
        ))
        return when (execution) {
            is SourceExecutionResult.Quadratic -> compiledSchema?.sourceBinding?.bindResult(execution) {
                if (timeout() || stop.deadline()?.hasPassedNow() == true) TerminationReason.Timeout
                else TerminationReason.Cancelled
            } ?: execution.result
            is SourceExecutionResult.Declined -> unsupportedQuadratic(sense, execution.reason)
            else -> unsupportedQuadratic(sense)
        }.withSourceSemantics(source.semantics)
    }

    /**
     * Solve through the shared source route. [polynomial] retains finite strategy controls and arithmetic limits;
     * [theory] supplies open-linear controls. Extra CP components, open-source assumption pins and finite seeding
     * hooks explicitly decline. Deduction scopes and tolerance acceptance also decline; exact polynomial LS
     * declines finite seeding and event hooks. A heuristic rejection never refutes the source.
     */
    fun solve(
        polynomial: PolynomialTheoryParams = PolynomialTheoryParams(),
        theory: TheoryParams = TheoryParams(),
    ): OpenTheoryResult = solveSource(polynomial, theory).withSourceSemantics(source.semantics)

    private fun solveSource(polynomial: PolynomialTheoryParams, theory: TheoryParams): OpenTheoryResult {
        val scope = scope(polynomial, theory)
        if (scope.stop()) return scope.unknown()
        if (!scope.supportsSource()) return scope.unsupported()
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                sourceAttachment(),
                ExecutionOperation.SATISFY,
                engine,
                providers = projectionProviders,
                cancellation = scope.stop,
                boundCancellation = scope.stop,
            ),
            polynomialCancellation = scope.polynomialPreparation(),
        )
        compiledSchema?.sourceBinding?.selectView(invocation)
        val route = invocation.engineRoute
        if (scope.stop()) return scope.unknown()
        val result = when (route) {
            is SourceProblemRoute.NativeFormula -> {
                if (!scope.supportsPolynomial(engine)) return scope.unsupported()
                when (val execution = SourceSetup.execute(
                    SourceExecutionRequest(invocation, polynomial = scope.polynomial),
                )) {
                    is SourceExecutionResult.Polynomial -> execution.result.result
                    is SourceExecutionResult.Transcendental -> execution.result.result
                    is SourceExecutionResult.Declined -> OpenTheoryResult.Unknown(execution.reason, SolveStats.EMPTY)
                    else -> error("native factor satisfaction requires a satisfaction result")
                }
            }

            is SourceProblemRoute.PolynomialTheory -> {
                if (!scope.supportsPolynomial(engine)) return scope.unsupported()
                (
                    SourceSetup.execute(SourceExecutionRequest(invocation, polynomial = scope.polynomial))
                        as SourceExecutionResult.Polynomial
                    ).result.result
            }

            is SourceProblemRoute.Finite -> finiteSolve(invocation, scope)

            is SourceProblemRoute.OpenTheory -> {
                if (!scope.supportsOpen(engine)) {
                    return scope.unsupported()
                }
                val execution = (
                    SourceSetup.execute(SourceExecutionRequest(invocation, theory = scope.theory))
                        as SourceExecutionResult.Linear
                    ).result
                (execution as OpenTheoryExecution.Satisfy).result
            }

            is SourceProblemRoute.UnsupportedOpen -> {
                if (route.preparedPlan.decline == PreparedPlanDecline.RESOURCE) {
                    return OpenTheoryResult.Unknown(TerminationReason.BudgetExhausted, SolveStats.EMPTY)
                }
                if (!scope.supportsOpen(engine)) {
                    return scope.unsupported()
                }
                val execution = (
                    SourceSetup.execute(SourceExecutionRequest(invocation, theory = scope.theory))
                        as SourceExecutionResult.Linear
                    ).result
                (execution as OpenTheoryExecution.Satisfy).result
            }

            SourceProblemRoute.Refuted -> OpenTheoryResult.Unsat(SolveStats.EMPTY)
        }
        if (scope.stop()) return scope.unknown(result.stats)
        val binding = compiledSchema?.sourceBinding ?: return result
        if (result is OpenTheoryResult.Sat) {
            val sample = when (val assignment = result.assignment) {
                is OpenTheoryAssignment.Sampled -> assignment.sample
                is OpenTheoryAssignment.Difference -> assignment.sample
                else -> null
            }
            if (sample != null) {
                val bound = binding.bindOriginalSample(sample, scope.stop) ?: return scope.unknown(result.stats)
                return result.copy(assignment = OpenTheoryAssignment.Sampled(bound))
            }
        }
        return result
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
     * Optimize an affine objective over supported integer declarations and exact continuous constraints.
     * The lossless shared bridge retains exact values, objective direction, bounds and attainment results.
     * [optimizeOpen] retains the linear result surface; an unsupported polynomial encoding explicitly declines.
     */
    fun optimize(
        objective: LinearObjective,
        polynomial: PolynomialTheoryParams = PolynomialTheoryParams(),
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    ): QuadraticOptimizationExecution = optimizeSource(objective, polynomial, sense)
        .withSourceSemantics(source.semantics)

    private fun optimizeSource(
        objective: LinearObjective,
        polynomial: PolynomialTheoryParams,
        sense: ObjectiveSense,
    ): QuadraticOptimizationExecution {
        compiledSchema?.let { return optimizeSource(it.sourceBinding.originalObjective(objective), polynomial, sense) }
        require(objective.sourcePairing == null) { "compiled helper objective requires its compiled source strategy" }
        return optimizeSource(objective.toQuadraticObjectiveSpec(), polynomial, sense)
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

    /** Optimize a captured exact quadratic objective over the source's integer and real constraints. */
    fun optimize(
        objective: QuadraticObjectiveSpec,
        polynomial: PolynomialTheoryParams = PolynomialTheoryParams(),
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    ): QuadraticOptimizationExecution = optimizeSource(objective, polynomial, sense)
        .withSourceSemantics(source.semantics)

    private fun optimizeSource(
        objective: QuadraticObjectiveSpec,
        polynomial: PolynomialTheoryParams,
        sense: ObjectiveSense,
    ): QuadraticOptimizationExecution {
        val scope = scope(polynomial, TheoryParams())
        if (scope.stop()) return unsupportedQuadratic(sense, scope.unknown().reason)
        if (!scope.supportsPolynomial(engine)) return unsupportedQuadratic(sense)
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            sourceAttachment(), engine, objective, sense, projectionProviders, scope.stop, scope.stop,
            requiresQuadraticCompletion = true,
        ), polynomialCancellation = scope.polynomialPreparation())
        return when (val execution = SourceSetup.execute(SourceExecutionRequest(invocation,
            theory = scope.theory, polynomial = scope.polynomial))) {
            is SourceExecutionResult.Quadratic -> compiledSchema?.sourceBinding?.bindResult(execution) {
                scope.unknown().reason
            } ?: execution.result
            is SourceExecutionResult.Declined -> unsupportedQuadratic(sense, execution.reason)
            else -> unsupportedQuadratic(sense)
        }
    }

    /**
     * Optimize an affine source through its captured finite or native linear execution.
     * Native costs and bounds retain minimization sense; [sense] selects the source objective direction.
     * Unsupported fragment or invocation controls return the native incomplete result, without finite boxing.
     */
    fun optimizeOpen(
        objective: LinearObjective,
        polynomial: PolynomialTheoryParams = PolynomialTheoryParams(),
        theory: TheoryParams = TheoryParams(),
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    ): OpenTheoryExecution = optimizeOpenSource(objective, polynomial, theory, sense)
        .withSourceSemantics(source.semantics)

    private fun optimizeOpenSource(
        objective: LinearObjective,
        polynomial: PolynomialTheoryParams,
        theory: TheoryParams,
        sense: ObjectiveSense,
    ): OpenTheoryExecution {
        require(compiledSchema != null || objective.sourcePairing == null) {
            "compiled helper objective requires its compiled source strategy"
        }
        val sourceObjective = compiledSchema?.sourceBinding?.openObjective(objective)
            ?: if (compiledSchema == null) objective else return OpenTheoryExecution.Optimize(
                OpenTheoryOptimum.Bounded(null, null, TerminationReason.Unsupported, SolveStats.EMPTY),
            )
        val scope = scope(polynomial, theory)
        fun incomplete(reason: TerminationReason): OpenTheoryExecution = OpenTheoryExecution.Optimize(
            OpenTheoryOptimum.Bounded(null, null, reason, SolveStats.EMPTY),
        )
        if (scope.stop()) return incomplete(scope.unknown().reason)
        if (!scope.supportsSource()) return incomplete(TerminationReason.Unsupported)
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                sourceAttachment(),
                ExecutionOperation.OPTIMIZE,
                engine,
                sourceObjective,
                maximize = sense == ObjectiveSense.MAXIMIZE,
                providers = projectionProviders,
                cancellation = scope.stop,
                boundCancellation = scope.stop,
            ),
            polynomialCancellation = scope.polynomialPreparation(),
        )
        if (scope.stop()) return incomplete(scope.unknown().reason)
        compiledSchema?.sourceBinding?.selectView(invocation)
        val route = invocation.engineRoute
        if (route is SourceProblemRoute.UnsupportedOpen &&
            route.preparedPlan.decline == PreparedPlanDecline.RESOURCE
        ) {
            return incomplete(TerminationReason.BudgetExhausted)
        }
        return when (route) {
            is SourceProblemRoute.Finite -> {
                if (invocation.enginePlan?.capabilities?.admission(engine, invocation.operation)?.runnable != true) {
                    return incomplete(TerminationReason.Unsupported)
                }
                finiteOptimize(invocation, scope, sourceObjective)
            }

            is SourceProblemRoute.OpenTheory, is SourceProblemRoute.UnsupportedOpen -> {
                if (!scope.supportsOpen(engine)) return incomplete(TerminationReason.Unsupported)
                (SourceSetup.execute(SourceExecutionRequest(invocation, theory = scope.theory))
                    as SourceExecutionResult.Linear).result
            }

            SourceProblemRoute.Refuted -> {
                OpenTheoryExecution.Optimize(OpenTheoryOptimum.Infeasible(SolveStats.EMPTY))
            }

            else -> incomplete(TerminationReason.Unsupported)
        }
    }

    private fun sourceAttachment(): SourceAttachment = compiledSchema?.sourceBinding?.attachment()
        ?: SourceAttachment(source)

    private fun unsupportedQuadratic(
        sense: ObjectiveSense,
        reason: TerminationReason = TerminationReason.Unsupported,
    ): QuadraticOptimizationExecution = QuadraticOptimizationExecution(
        QuadraticOptimizationResult.Unknown(null, reason, sense, SolveStats.EMPTY),
        PolynomialTheoryStats(),
        0,
        0,
    )

    private fun finiteSolve(invocation: PreparedSourceInvocation, scope: SourceInvocation): OpenTheoryResult {
        if (invocation.enginePlan?.capabilities?.admission(engine, invocation.operation)?.runnable != true) {
            return scope.unsupported()
        }
        val result = executeFinite(invocation, scope)
        val outcome = result.outcome as FiniteSolveOutcome.Completed
        return when (outcome.verdict) {
            FiniteSolveVerdict.SAT -> OpenTheoryResult.Sat(
                OpenTheoryAssignment.Sampled(requireNotNull(outcome.bestSample)), outcome.stats,
            )
            FiniteSolveVerdict.UNSAT -> OpenTheoryResult.Unsat(outcome.stats)
            FiniteSolveVerdict.UNKNOWN -> OpenTheoryResult.Unknown(
                (result.nativeSourceResult as? SolveResult.Unknown)?.reason
                    ?: if (scope.stop()) scope.unknown().reason
                    else if (invocation.floatGrid != null) TerminationReason.Unsupported
                    else TerminationReason.BudgetExhausted,
                outcome.stats,
            )
            else -> error("source satisfaction returned an optimization verdict")
        }
    }

    private fun executeFinite(invocation: PreparedSourceInvocation, scope: SourceInvocation): FiniteSolveResult {
        val execution = SourceSetup.execute(SourceExecutionRequest(invocation, finiteRequest = { shape ->
            val controls = when (engine) {
                FiniteEngine.BACKTRACK -> SourceFiniteControls.Backtrack(scope.backtrack)
                FiniteEngine.LOCAL_SEARCH -> SourceFiniteControls.LocalSearch(scope.localSearch)
                else -> error("unsupported source strategy $engine")
            }
            FiniteSolveRequest(shape, controls)
        }))
        return (execution as SourceExecutionResult.Finite).result
    }

    private fun finiteOptimize(
        invocation: PreparedSourceInvocation,
        scope: SourceInvocation,
        objective: LinearObjective,
    ): OpenTheoryExecution {
        val result = executeFinite(invocation, scope)
        val outcome = result.outcome as FiniteSolveOutcome.Completed
        val point = outcome.bestSample?.let { OpenTheoryAssignment.Sampled(it) }
        val sourceValue = outcome.bestSample?.let(objective::evaluateExact)
        val value = sourceValue?.let {
            if (invocation.maximizeSourceObjective) it.negated() else it
        }
        val reason = when (val native = result.nativeSourceMinimum) {
            is MinimizeResult.Unknown -> native.reason
            is MinimizeResult.BestFound -> native.reason
            is MinimizeResult.Unbounded -> TerminationReason.Unsupported
            else -> if (scope.stop()) scope.unknown().reason
                else if (invocation.floatGrid != null) TerminationReason.Unsupported
                else TerminationReason.BudgetExhausted
        }
        val optimum = when (outcome.verdict) {
            FiniteSolveVerdict.OPTIMAL -> OpenTheoryOptimum.Optimal(
                requireNotNull(point), requireNotNull(value), outcome.stats,
            )
            FiniteSolveVerdict.UNBOUNDED -> OpenTheoryOptimum.Unbounded(
                requireNotNull(point), requireNotNull(value), outcome.stats,
            )
            FiniteSolveVerdict.UNSAT -> OpenTheoryOptimum.Infeasible(outcome.stats)
            FiniteSolveVerdict.BEST_FOUND, FiniteSolveVerdict.UNKNOWN -> OpenTheoryOptimum.Bounded(
                point, value, reason,
                outcome.stats,
            )
            FiniteSolveVerdict.SAT -> error("source optimization returned a satisfaction verdict")
        }
        return OpenTheoryExecution.Optimize(optimum).also { execution ->
            outcome.bestSample?.let { sample ->
                execution.retainPublication(SourceIncumbentPublication(
                    1L, sample, SourceValue.Rational(requireNotNull(sourceValue)),
                ))
            }
        }
    }

    private fun scope(polynomial: PolynomialTheoryParams, theory: TheoryParams): SourceInvocation {
        val selected = if (engine == FiniteEngine.BACKTRACK) {
            polynomial.strategy.backtrack.assumptions
        } else {
            polynomial.strategy.localSearch.assumptions
        }
        val assumptions = polynomial.strategy.assumptions.mergedWith(selected)
        val selectedStop = if (engine == FiniteEngine.BACKTRACK) {
            polynomial.strategy.backtrack.cancellation
        } else {
            polynomial.strategy.localSearch.cancellation
        }
        val stop = polynomial.timeout or polynomial.cancellation or
            theory.timeout or theory.cancellation or selectedStop
        val selectedBudget = if (engine == FiniteEngine.BACKTRACK) {
            polynomial.strategy.backtrack.nodeBudget
        } else {
            polynomial.strategy.localSearch.nodeBudget
        }
        require(theory.nodeBudget == null || selectedBudget == null || theory.nodeBudget === selectedBudget) {
            "source execution requires one shared node allowance"
        }
        val budget = selectedBudget ?: theory.nodeBudget
        val backtrack = polynomial.strategy.backtrack.copy(
            assumptions = assumptions, cancellation = stop, nodeBudget = budget,
        )
        val localSearch = polynomial.strategy.localSearch.copy(
            assumptions = assumptions, cancellation = stop, nodeBudget = budget,
        )
        val open = when (engine) {
            FiniteEngine.BACKTRACK -> theory.copy(
                cancellation = stop,
                maxDecisions = minOf(
                    theory.maxDecisions,
                    backtrack.maxDecisions,
                    backtrack.maxInstructions ?: Long.MAX_VALUE,
                ),
                nodeBudget = budget,
                randomSeed = backtrack.randomSeed ?: theory.randomSeed,
            )

            FiniteEngine.LOCAL_SEARCH -> theory.copy(
                cancellation = stop,
                maxFlips = minOf(theory.maxFlips, localSearch.maxFlips),
                maxInstructions = minOf(
                    theory.maxInstructions ?: Long.MAX_VALUE,
                    localSearch.maxInstructions ?: Long.MAX_VALUE,
                ).takeIf { it != Long.MAX_VALUE },
                nodeBudget = budget,
                randomSeed = localSearch.randomSeed ?: theory.randomSeed,
            )

            else -> error("unsupported source strategy $engine")
        }
        return SourceInvocation(
            assumptions,
            stop,
            polynomial.timeout or theory.timeout,
            backtrack,
            localSearch,
            open,
            polynomial.copy(
                cancellation = stop,
                strategy = polynomial.strategy.copy(
                backtrack = backtrack,
                localSearch = localSearch,
                assumptions = assumptions,
            )
            ),
        )
    }

    private class SourceInvocation(
        val assumptions: Assumptions,
        val stop: Cancellation,
        val timeout: Cancellation,
        val backtrack: BacktrackParams,
        val localSearch: LocalSearchParams,
        val theory: TheoryParams,
        private val originalPolynomial: PolynomialTheoryParams,
    ) {
        private var preparationWork = 0L
        val polynomial: PolynomialTheoryParams get() = originalPolynomial.copy(
            maximumWork = originalPolynomial.maximumWork?.let { (it - preparationWork).coerceAtLeast(0) },
        )
        fun polynomialPreparation(): Cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = stop() ||
                originalPolynomial.maximumWork?.let { preparationWork >= it } == true
            override fun deadline() = stop.deadline()
            override fun workMeter(): WorkMeter = WorkMeter { units ->
                require(units >= 0)
                preparationWork = if (units > Long.MAX_VALUE - preparationWork) {
                    Long.MAX_VALUE
                } else {
                    preparationWork + units
                }
                stop.charge(units)
            }
        }
        fun unknown(stats: SolveStats = SolveStats.EMPTY): OpenTheoryResult.Unknown = OpenTheoryResult.Unknown(
            if (timeout() || stop.deadline()?.hasPassedNow() == true) {
                TerminationReason.Timeout
            } else {
                TerminationReason.Cancelled
            },
            stats,
        )
        fun supportsSource(): Boolean = assumptions.deductions.isEmpty &&
            backtrack.toleranceCheck == null && backtrack.componentFactory == null
        fun supportsPolynomial(engine: FiniteEngine): Boolean = supportsSource() &&
            (engine != FiniteEngine.LOCAL_SEARCH || supportsLocalHooks())
        private fun supportsLocalHooks(): Boolean = localSearch.initialAssignment == null &&
            localSearch.pooledIncumbents == null && localSearch.lsObjective == null && localSearch.onEvent == null
        fun supportsOpen(engine: FiniteEngine): Boolean = supportsSource() && assumptions.isEmpty && when (engine) {
            FiniteEngine.BACKTRACK -> backtrack.onEvent == null
            FiniteEngine.LOCAL_SEARCH -> supportsLocalHooks()
            else -> false
        }
        fun unsupported(): OpenTheoryResult.Unknown =
            OpenTheoryResult.Unknown(TerminationReason.Unsupported, SolveStats.EMPTY)
    }
}
