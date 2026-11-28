package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.PooledGuidanceObservation
import com.eignex.klause.backtrack.SearchOutcome
import com.eignex.klause.backtrack.toBacktrackParams
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpRootPointObservation
import com.eignex.klause.lp.engine.LpQueryObservation
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.portfolio.ArmFault
import com.eignex.klause.portfolio.AttributedImprovement
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.LeafRealCompletion
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.capLp
import com.eignex.klause.portfolio.editing
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.OptimalityGap
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.terminationReason
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.includingStop
import com.eignex.util.numbers.BigFraction
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class FiniteExecutionRequest(
    val problem: BakedProblem,
    val preparedPlan: PreparedSourcePlan,
    val engine: FiniteEngine,
    val optimize: Boolean,
    val objective: LinearObjective?,
    val localSearchObjective: IncrementalObjective?,
    val definitionalSweep: DefinitionalSweep?,
    val searchHints: SearchHints?,
    // Ignored by the fixed route.
    val cores: Int,
    val executionOptions: ExecutionOptions,
    val randomSeed: Long?,
    val defaultArms: Int,
    val lpConfig: LpConfig,
    val zeroObjectivePricing: LpZeroObjectivePricing,
    val cancellation: ExecutionControl,
    val nodeBudget: NodeBudget?,
    // Advisory, supplied to fixed-backtrack LP components.
    val solveBudgetMillis: Long?,
    val allSolutions: Boolean,
    val solutionCap: Long?,
    // True after the frontend's shared wall-clock deadline has elapsed.
    val deadlineExceeded: () -> Boolean,
    val onEvent: ((SearchEvent) -> Unit)?,
    val onPortfolioEvent: ((worker: String, event: SearchEvent) -> Unit)?,
    // Told about each portfolio arm quarantined for a result the model refutes.
    val onPortfolioFault: ((ArmFault) -> Unit)? = null,
    val lpSolveContext: LpSolveContext,
    val toleranceCheck: ((Sample) -> Boolean)? = null,
    val sourceInvocation: PreparedSourceInvocation? = null,
    val sourceMapping: SourceMapping? = null,
    val sourceControls: SourceFiniteControls? = null,
    val numericalCompanionParams: TheoryParams? = null,
    val objectiveCoordinates: ObjectiveCoordinateView? = null,
    val onPortfolioLpQuery: ((String, Double?, LpQueryObservation) -> Unit)? = null,
    val onPortfolioGuidance: ((String, PooledGuidanceObservation) -> Unit)? = null,
    val onPortfolioJointRepair: ((String, Long, JointRepairObservation) -> Unit)? = null,
    val onPortfolioSession: ((String, PropagationSession) -> Unit)? = null,
    val onPortfolioRootPoint: ((String, LpRootPointObservation) -> Unit)? = null,
    val optimalityGap: OptimalityGap? = null,
)

internal class FiniteExecutionCallbacks(
    val onSample: (Sample) -> Unit,
    val onImprovement: (FiniteImprovement) -> Unit,
)

internal class FiniteImprovement(
    val sample: Sample,
    val workerLabel: String,
    // Since portfolio minimization began.
    val elapsedMs: Long,
    // Engine-normalized.
    val objective: Double,
)

internal sealed class FiniteExecutionResult {
    class Completed(
        val verdict: FiniteExecutionVerdict,
        val stats: SolveStats,
        val solutions: Long,
        val bestSample: Sample?,
        // Excludes route planning and worker construction.
        val elapsedMs: Long,
        // On [FiniteExecutionVerdict.UNBOUNDED], the recession ray of the reals through [bestSample].
        val unboundedDirection: List<BigFraction>? = null,
        val nativeSourceResult: SolveResult? = null,
        val nativeSourceMinimum: MinimizeResult? = null,
        val terminationReason: TerminationReason? = null,
        // On [FiniteExecutionVerdict.OPTIMAL] within the optimality gap, the proved working objective bound.
        val gapBound: Double? = null,
    ) : FiniteExecutionResult() {
        var completeSearchAdmitted: Boolean? = null
        var nativeObjectiveBound: FiniteObjectiveBound? = null
    }

    class DryRun(
        val heading: String,
        val lines: List<String>,
    ) : FiniteExecutionResult()
}

internal enum class FiniteExecutionVerdict {
    SAT,

    UNSAT,

    UNKNOWN,

    OPTIMAL,

    BEST_FOUND,

    UNBOUNDED,
}

internal fun FinitePipeline.execute(
    request: FiniteExecutionRequest,
    callbacks: FiniteExecutionCallbacks,
): FiniteExecutionResult = if (request.sourceControls != null) {
    executeSourceFinite(request, callbacks).withCompleteSearch(request.sourceControls is SourceFiniteControls.Backtrack)
} else when (request.engine) {
    FiniteEngine.FIXED -> executeFixed(request, callbacks).withCompleteSearch(true)

    FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.MIXED, FiniteEngine.ALNS ->
        executePortfolio(request, callbacks)
}

private fun FiniteExecutionResult.withCompleteSearch(value: Boolean): FiniteExecutionResult {
    if (this is FiniteExecutionResult.Completed) completeSearchAdmitted = value
    return this
}

private fun executeSourceFinite(
    request: FiniteExecutionRequest,
    callbacks: FiniteExecutionCallbacks,
): FiniteExecutionResult {
    require(request.sourceInvocation != null)
    if (request.optimize) return executeSourceMinimum(request, callbacks)
    val start = TimeSource.Monotonic.markNow()
    if (request.allSolutions || (request.solutionCap ?: 1L) > 1L) {
        val controls = request.sourceControls as? SourceFiniteControls.Backtrack
            ?: pipelineConfigError("source enumeration requires backtrack controls")
        return executeFixedSatisfy(BacktrackSolver(request.problem, request.lpSolveContext),
            controls.params.copy(cancellation = controls.cancellation.includingStop(request.cancellation),
                toleranceCheck = request.toleranceCheck), request, callbacks, start)
    }
    val solved = when (val controls = requireNotNull(request.sourceControls)) {
        is SourceFiniteControls.Backtrack -> BacktrackSolver(request.problem, request.lpSolveContext).solve(
            controls.params.copy(cancellation = controls.cancellation.includingStop( request.cancellation),
                toleranceCheck = request.toleranceCheck,
            )
        )
        is SourceFiniteControls.LocalSearch -> LocalSearchSolver(
            request.problem,
            completion = if (request.problem.numRealVars > 0) {
                LeafRealCompletion(request.problem, null, request.toleranceCheck)
            } else null,
        ).solve(controls.params.copy(cancellation = controls.cancellation.includingStop( request.cancellation)))
    }
    val sample = (solved as? SolveResult.Sat)?.assignment
    if (sample != null) callbacks.onSample(sample)
    return FiniteExecutionResult.Completed(
        when (solved) {
            is SolveResult.Sat -> FiniteExecutionVerdict.SAT
            is SolveResult.Unsat -> FiniteExecutionVerdict.UNSAT
            is SolveResult.Unknown -> FiniteExecutionVerdict.UNKNOWN
        },
        solved.stats,
        if (sample == null) 0L else 1L,
        sample,
        start.elapsedNow().inWholeMilliseconds,
        nativeSourceResult = solved,
        terminationReason = (solved as? SolveResult.Unknown)?.reason,
    )
}

private fun executeSourceMinimum(
    request: FiniteExecutionRequest,
    callbacks: FiniteExecutionCallbacks,
): FiniteExecutionResult {
    val objective = requireNotNull(request.objective)
    val start = TimeSource.Monotonic.markNow()
    val result = when (val controls = requireNotNull(request.sourceControls)) {
        is SourceFiniteControls.Backtrack -> BacktrackSolver(request.problem, request.lpSolveContext).minimize(
            objective, controls.params.copy(cancellation = controls.cancellation.includingStop( request.cancellation),
                toleranceCheck = request.toleranceCheck, optimalityGap = request.optimalityGap,
            )
        )
        is SourceFiniteControls.LocalSearch -> LocalSearchSolver(
            request.problem,
            completion = if (request.problem.numRealVars > 0) {
                LeafRealCompletion(request.problem, objective, request.toleranceCheck)
            } else null,
        ).minimize(objective, controls.params.copy(cancellation =
            controls.cancellation.includingStop( request.cancellation)))
    }
    val sample = result.assignment
    if (sample != null) callbacks.onSample(sample)
    return FiniteExecutionResult.Completed(
        when (result) {
            is MinimizeResult.Optimal -> FiniteExecutionVerdict.OPTIMAL
            is MinimizeResult.BestFound -> FiniteExecutionVerdict.BEST_FOUND
            is MinimizeResult.Unbounded -> FiniteExecutionVerdict.UNBOUNDED
            is MinimizeResult.Infeasible -> FiniteExecutionVerdict.UNSAT
            is MinimizeResult.Unknown -> FiniteExecutionVerdict.UNKNOWN
        },
        result.stats,
        if (sample == null) 0L else 1L,
        sample,
        start.elapsedNow().inWholeMilliseconds,
        (result as? MinimizeResult.Unbounded)?.direction,
        nativeSourceMinimum = result,
        gapBound = (result as? MinimizeResult.Optimal)?.gapBound,
        terminationReason = result.incompleteReason(),
    )
}

private fun executeFixed(request: FiniteExecutionRequest, callbacks: FiniteExecutionCallbacks): FiniteExecutionResult {
    val options = if (request.optimize && request.problem.fixedCumulativeScheduling()) {
        ExecutionOptions(ExecutionOption.costAwarePropagation(true)).overriddenBy(request.executionOptions)
    } else request.executionOptions
    val plan = FinitePipeline.planFixedBacktrack(
        FixedBacktrackPlanRequest(
            annotatedParams = request.searchHints?.toBacktrackParams(
                request.problem.numBoolVars,
                request.problem.numIntVars,
            ),
            executionOptions = options,
            randomSeed = request.randomSeed,
            cancellation = request.cancellation,
            nodeBudget = request.nodeBudget,
            solveBudgetMillis = request.solveBudgetMillis,
            lpConfig = request.lpConfig,
            zeroObjectivePricing = request.zeroObjectivePricing,
            onEvent = request.onEvent,
        ),
    )
    val solver = BacktrackSolver(request.problem, request.lpSolveContext)
    if (plan.dryRun) return FiniteExecutionResult.DryRun("solver dry-run:", solver.describe(plan.params).lines())

    val params = plan.params.copy(toleranceCheck = request.toleranceCheck, optimalityGap = request.optimalityGap)
    val start = TimeSource.Monotonic.markNow()
    return if (request.optimize) {
        executeFixedOptimize(solver, params, requireNotNull(request.objective), callbacks, start)
    } else {
        executeFixedSatisfy(solver, params, request, callbacks, start)
    }
}

private fun Problem.fixedCumulativeScheduling(): Boolean {
    if (numRealVars != 0) return false
    var found = false
    for (factor in factors) {
        if (factor is Diffn) return false
        if (factor !is Cumulative) continue
        if (factor.presents.isNotEmpty() || factor.durationVars.isNotEmpty() ||
            factor.resourceVars.isNotEmpty() || factor.capacityVar >= 0
        ) return false
        found = true
    }
    return found
}

private fun executeFixedSatisfy(
    solver: BacktrackSolver,
    params: BacktrackParams,
    request: FiniteExecutionRequest,
    callbacks: FiniteExecutionCallbacks,
    start: TimeMark,
): FiniteExecutionResult.Completed {
    val enumerating = request.allSolutions || (request.solutionCap ?: 1L) > 1L
    val limit = if (enumerating) request.solutionCap ?: Long.MAX_VALUE else 1L
    if (!enumerating) {
        val result = solver.solve(params)
        val verdict = when (result) {
            is SolveResult.Sat -> {
                callbacks.onSample(result.assignment)
                FiniteExecutionVerdict.SAT
            }

            is SolveResult.Unsat -> FiniteExecutionVerdict.UNSAT

            is SolveResult.Unknown -> FiniteExecutionVerdict.UNKNOWN
        }
        return FiniteExecutionResult.Completed(
            verdict = verdict,
            stats = result.stats,
            solutions = if (result is SolveResult.Sat) 1 else 0,
            bestSample = null,
            elapsedMs = start.elapsedNow().inWholeMilliseconds,
            terminationReason = (result as? SolveResult.Unknown)?.reason,
        )
    }

    var solutions = 0L
    var timedOut = false
    var indeterminate = false
    var exhausted = false
    var reason: TerminationReason? = null
    solver.enumerationOutcomes(params).use { outcomes ->
        for (outcome in outcomes) {
            when (outcome) {
                is SearchOutcome.Found -> {
                    if (request.deadlineExceeded()) {
                        timedOut = true
                        reason = TerminationReason.Timeout
                        break
                    }
                    callbacks.onSample(outcome.sample)
                    solutions++
                    if (solutions >= limit) break
                }

                is SearchOutcome.Exhausted -> {
                    indeterminate = outcome.indeterminate
                    exhausted = !indeterminate
                    if (indeterminate) reason = outcome.stopCause.terminationReason()
                    break
                }

                is SearchOutcome.BudgetCapped -> {
                    timedOut = true
                    reason = outcome.stopCause?.terminationReason() ?: when {
                        request.deadlineExceeded() -> TerminationReason.Timeout
                        request.nodeBudget?.exhausted() == true -> TerminationReason.BudgetExhausted
                        request.cancellation() -> TerminationReason.Cancelled
                        else -> TerminationReason.BudgetExhausted
                    }
                    break
                }
            }
        }
    }
    val verdict = when {
        (timedOut || indeterminate) && solutions == 0L -> FiniteExecutionVerdict.UNKNOWN
        solutions == 0L -> FiniteExecutionVerdict.UNSAT
        exhausted -> FiniteExecutionVerdict.SAT
        else -> FiniteExecutionVerdict.BEST_FOUND
    }
    return FiniteExecutionResult.Completed(
        verdict = verdict,
        stats = SolveStats.EMPTY,
        solutions = solutions,
        bestSample = null,
        elapsedMs = start.elapsedNow().inWholeMilliseconds,
        terminationReason = reason,
    )
}

private fun executeFixedOptimize(
    solver: BacktrackSolver,
    params: BacktrackParams,
    objective: LinearObjective,
    callbacks: FiniteExecutionCallbacks,
    start: TimeMark,
): FiniteExecutionResult.Completed {
    var solutions = 0L
    var stats = SolveStats.EMPTY
    var bestSample: Sample? = null
    var reason: TerminationReason? = null
    for (step in solver.improvements(objective, params)) {
        stats = step.stats
        reason = step.incompleteReason()
        when (step) {
            is MinimizeResult.WithSample -> {
                callbacks.onSample(step.sample)
                bestSample = step.sample
                solutions++
                if (step is MinimizeResult.Optimal) {
                    return FiniteExecutionResult.Completed(
                        verdict = FiniteExecutionVerdict.OPTIMAL,
                        stats = step.stats,
                        solutions = solutions,
                        bestSample = step.sample,
                        elapsedMs = start.elapsedNow().inWholeMilliseconds,
                        gapBound = step.gapBound,
                    )
                }
                if (step is MinimizeResult.Unbounded) {
                    return FiniteExecutionResult.Completed(
                        verdict = FiniteExecutionVerdict.UNBOUNDED,
                        stats = step.stats,
                        solutions = solutions,
                        bestSample = step.sample,
                        elapsedMs = start.elapsedNow().inWholeMilliseconds,
                        unboundedDirection = step.direction,
                    )
                }
            }

            is MinimizeResult.Infeasible -> return FiniteExecutionResult.Completed(
                verdict = FiniteExecutionVerdict.UNSAT,
                stats = step.stats,
                solutions = solutions,
                bestSample = null,
                elapsedMs = start.elapsedNow().inWholeMilliseconds,
            )

            is MinimizeResult.Unknown -> return FiniteExecutionResult.Completed(
                verdict = FiniteExecutionVerdict.UNKNOWN,
                stats = step.stats,
                solutions = solutions,
                bestSample = bestSample,
                elapsedMs = start.elapsedNow().inWholeMilliseconds,
                terminationReason = step.reason,
            )
        }
    }
    return FiniteExecutionResult.Completed(
        verdict = if (solutions == 0L) {
            FiniteExecutionVerdict.UNKNOWN
        } else {
            FiniteExecutionVerdict.BEST_FOUND
        },
        stats = stats,
        solutions = solutions,
        bestSample = bestSample,
        elapsedMs = start.elapsedNow().inWholeMilliseconds,
        terminationReason = reason,
    )
}

private fun executePortfolio(
    request: FiniteExecutionRequest,
    callbacks: FiniteExecutionCallbacks,
): FiniteExecutionResult {
    val admission = request.executionOptions.reader()[ExecutionOption.companionAdmission]
    val adaptiveCompanionRepair = when (val repair =
        request.executionOptions.reader()[ExecutionOption.companionRepair]) {
        null, "fixed" -> false
        "adaptive" -> true
        else -> pipelineConfigError("companion-repair expects fixed|adaptive, got `$repair`")
    }
    val bufferedPublications = when (val publications =
        request.executionOptions.reader()[ExecutionOption.portfolioPublications]) {
        null, "immediate" -> false
        "segment" -> true
        else -> pipelineConfigError("portfolio-publications expects immediate|segment, got `$publications`")
    }
    if (bufferedPublications && (!request.optimize || request.cores != 1 || request.engine != FiniteEngine.MIXED)) {
        pipelineConfigError("portfolio-publications requires curated single-core mixed optimization")
    }
    val fundedCompanions = when (admission) {
        null, "ordinary" -> false
        "funded", "progress-funded" -> true
        else -> pipelineConfigError("companion-admission expects ordinary|funded|progress-funded, got `$admission`")
    }
    val probePolicy = when (val probes = request.executionOptions.reader()[ExecutionOption.portfolioProbes]) {
        null, "ordinary" -> InitialProbePolicy.ORDINARY
        "leader-half" -> InitialProbePolicy.LEADER_HALF
        "lp-transfer" -> InitialProbePolicy.LP_TRANSFER
        "heuristic-second" -> InitialProbePolicy.HEURISTIC_SECOND
        "family-work" -> InitialProbePolicy.FAMILY_WORK
        "heuristic-work" -> InitialProbePolicy.HEURISTIC_WORK
        "alns-work" -> InitialProbePolicy.ALNS_WORK
        else -> pipelineConfigError(
            "portfolio-probes expects ordinary|leader-half|lp-transfer|heuristic-second|family-work|" +
                "heuristic-work|alns-work, got `$probes`")
    }
    if (probePolicy != InitialProbePolicy.ORDINARY &&
        (!request.optimize || request.cores != 1 || request.engine != FiniteEngine.MIXED)) {
        pipelineConfigError("portfolio-probes requires curated single-core mixed optimization")
    }
    val deferLpProbes = when (val start = request.executionOptions.reader()[ExecutionOption.portfolioLpStart]) {
        null, "ordinary" -> false
        "after-probes" -> true
        else -> pipelineConfigError("portfolio-lp-start expects ordinary|after-probes, got `$start`")
    }
    if (deferLpProbes && (!request.optimize || request.cores != 1 || request.engine != FiniteEngine.MIXED)) {
        pipelineConfigError("portfolio-lp-start requires curated single-core mixed optimization")
    }
    val requestedBooleanTasks = request.executionOptions.reader()[ExecutionOption.booleanProofTasks]
    val booleanTaskScope = !request.optimize && request.cores == 1 &&
        (request.engine == FiniteEngine.MIXED || request.engine == FiniteEngine.BACKTRACK)
    if (requestedBooleanTasks == true && !booleanTaskScope) {
        pipelineConfigError("boolean-proof-tasks requires single-core finite satisfaction")
    }
    val booleanProofTasks = if (booleanTaskScope) requestedBooleanTasks else false
    val proofShare = request.executionOptions.reader()[ExecutionOption.portfolioProofShare]
    val proofWorkers = request.executionOptions.reader()[ExecutionOption.portfolioProofWorkers]
    val includeRestartingProof = when (proofWorkers) {
        null, "retained" -> false
        "complete" -> true
        else -> pipelineConfigError("portfolio-proof-workers expects retained|complete, got `$proofWorkers`")
    }
    val proofCoverageConfigured = proofShare != null || proofWorkers != null
    if (proofShare != null && (!proofShare.isFinite() || proofShare !in 0.5..1.0)) {
        pipelineConfigError("portfolio-proof-share must be finite and in [0.5, 1]")
    }
    if (proofCoverageConfigured && (!request.optimize || request.cores != 1 ||
            request.engine !in setOf(FiniteEngine.MIXED, FiniteEngine.BACKTRACK))) {
        pipelineConfigError("portfolio proof coverage requires curated single-core finite optimization")
    }
    val plan = FinitePipeline.planPortfolio(
        PortfolioPlanRequest(
            engine = request.engine,
            optimize = request.optimize,
            cores = request.cores,
            executionOptions = request.executionOptions.without(
                "portfolio-proof-share", "portfolio-proof-workers", "portfolio-lp-start", "portfolio-probes",
                "boolean-proof-tasks", "companion-admission", "companion-repair", "portfolio-publications",
            ),
            randomSeed = request.randomSeed,
            defaultArms = request.defaultArms,
            lpCeiling = request.lpConfig,
            zeroObjectivePricing = request.zeroObjectivePricing,
            nodeBudget = request.nodeBudget,
            annotationArm = request.searchHints?.toBacktrackParams(
                request.problem.numBoolVars,
                request.problem.numIntVars,
            ),
            toleranceCheck = request.toleranceCheck,
        ),
    )
    if (proofCoverageConfigured && (plan !is PortfolioPlan.Execute || plan.scenario.explicitArms ||
            plan.scenario.btPool != null || plan.scenario.lsPool != null || plan.scenario.btEdit != null)) {
        pipelineConfigError("portfolio proof coverage requires a curated recipe pool")
    }
    if (deferLpProbes && (plan !is PortfolioPlan.Execute || plan.scenario.explicitArms ||
            plan.scenario.btPool != null || plan.scenario.lsPool != null || plan.scenario.btEdit != null ||
            plan.scenario.annotationArm != null)) {
        pipelineConfigError("portfolio-lp-start requires a curated recipe pool")
    }
    if (probePolicy != InitialProbePolicy.ORDINARY && (plan !is PortfolioPlan.Execute || plan.scenario.explicitArms ||
            plan.scenario.btPool != null || plan.scenario.lsPool != null || plan.scenario.btEdit != null ||
            (probePolicy != InitialProbePolicy.HEURISTIC_SECOND && plan.scenario.annotationArm != null))) {
        pipelineConfigError("portfolio-probes requires a curated recipe pool")
    }
    if (bufferedPublications && (plan !is PortfolioPlan.Execute || plan.scenario.explicitArms ||
            plan.scenario.btPool != null || plan.scenario.lsPool != null || plan.scenario.btEdit != null ||
            plan.scenario.annotationArm != null)) {
        pipelineConfigError("portfolio-publications requires a curated recipe pool")
    }
    when (plan) {
        is PortfolioPlan.LocalSearchDryRun -> return localSearchDryRun(plan.pool)

        is PortfolioPlan.BacktrackDryRun ->
            return backtrackDryRun(request.problem, plan.pool, plan.kind, plan.zeroObjectivePricing)

        is PortfolioPlan.Execute -> Unit
    }
    if (!request.optimize && (request.allSolutions || (request.solutionCap ?: 1L) > 1L)) {
        val scenario = plan.scenario
        val recipe = (scenario.btPool?.first()?.invoke() ?: BacktrackCatalog.ranked(Kind.CSP).first())
            .editing(scenario.btEdit).capLp(request.lpConfig)
        val params = recipe.build(request.randomSeed ?: 0L, request.onEvent).copy(
            cancellation = request.cancellation, nodeBudget = request.nodeBudget,
            solveBudgetMillis = request.solveBudgetMillis, toleranceCheck = request.toleranceCheck,
            zeroObjectivePricing = request.zeroObjectivePricing,
        )
        return executeFixedSatisfy(BacktrackSolver(request.problem, request.lpSolveContext), params,
            request, callbacks, TimeSource.Monotonic.markNow()).withCompleteSearch(true)
    }
    val profile = ProblemProfile.of(request.problem, request.optimize)
    val baseScenario = clausalDefaultScenario(
        plan.scenario.copy(numericalLp = request.lpSolveContext.numericalDeductions,
            optimalityGap = request.optimalityGap),
        profile,
        request.defaultArms,
        explicitArms = request.executionOptions.explicitArms,
        privateRebuildBudgetOnly = request.executionOptions.privateRebuildBudgetOnly,
    )
    val privateScenario = privateRebuildDefaultScenario(
        baseScenario, request.problem, profile, request.executionOptions,
        enumerating = request.allSolutions || (request.solutionCap ?: 1L) > 1L)
    val scenario = weightedCoreDefaultScenario(privateScenario, request.problem, request.objective,
        request.executionOptions, request.cancellation)
    if (scenario !== privateScenario) {
        request.onPortfolioEvent?.invoke("portfolio", SearchEvent.WorkerAdmission("core-guided/weighted-default",
            admitted = true, requirements = "bounded early trial with historical-reason refinement",
            declines = emptyList(), selected = true))
    }
    val executor = FinitePipeline.portfolioExecutor(
        request.problem,
        scenario,
        objective = request.objective,
        lsObjective = request.localSearchObjective,
        definitionalSweep = request.definitionalSweep,
        onEvent = request.onPortfolioEvent,
        onFault = request.onPortfolioFault,
        preparedPlan = request.preparedPlan,
        cancellation = request.cancellation,
        numericalCompletion = request.executionOptions.numericalCompletion,
        onLpQuery = request.onPortfolioLpQuery,
        onGuidance = request.onPortfolioGuidance,
        onJointRepair = request.onPortfolioJointRepair,
        onSession = request.onPortfolioSession,
        onRootPoint = request.onPortfolioRootPoint,
        numericalCompanions = request.numericalCompanionParams,
        sourceHints = request.sourceInvocation?.let { invocation ->
            { strategies ->
                invocation.heuristicHints(
                    request.problem,
                    request.objective,
                    request.sourceMapping,
                    request.cancellation,
                    strategies,
                    request.preparedPlan.capabilities,
                )
            }
        },
        objectiveCoordinates = request.objectiveCoordinates,
        incumbentCompletionCheck = request.incumbentCompletionCheck(),
        improvementProofShare = proofShare ?: 0.5,
        deferLpProbes = deferLpProbes,
        includeRestartingProof = includeRestartingProof,
        probePolicy = probePolicy,
        booleanProofTasks = booleanProofTasks,
        fundedCompanions = fundedCompanions,
        waitForCompanionImprovement = admission == "progress-funded",
        adaptiveCompanionRepair = adaptiveCompanionRepair,
        bufferedPublications = bufferedPublications,
        privateRebuildBudgetOnly = request.executionOptions.privateRebuildBudgetOnly ||
            privateScenario !== baseScenario,
    )
    val start = TimeSource.Monotonic.markNow()
    executor.use {
        val completed = if (request.optimize) {
            var streamed = 0L
            val result = it.minimize(request.cancellation) { improvement ->
                callbacks.onImprovement(improvement.toFiniteImprovement())
                streamed++
            }
            executePortfolioOptimize(result, streamed, callbacks, start)
        } else {
            executePortfolioSatisfy(it.solve(request.cancellation), callbacks, start)
        }
        completed.nativeObjectiveBound = finiteObjectiveBound(it.nativeObjectiveBound, request.sourceMapping)
        return completed.withCompleteSearch(it.completeSearchAdmitted)
    }
}

internal fun clausalDefaultScenario(
    scenario: PortfolioScenario,
    profile: ProblemProfile,
    defaultArms: Int,
    explicitArms: Boolean,
    privateRebuildBudgetOnly: Boolean = false,
): PortfolioScenario {
    if (explicitArms || defaultArms != autoArms(1) || scenario.arms != defaultArms) return scenario
    if (scenario.cores != 1 || scenario.kind != Kind.CSP || scenario.engine != EngineMix.MIXED) return scenario
    if (profile.problemClass != ProblemClass.Sat || scenario.btPool != null || scenario.lsPool != null ||
        (scenario.btEdit != null && !privateRebuildBudgetOnly) || scenario.annotationArm != null
    ) {
        return scenario
    }
    return scenario.copy(arms = 2)
}

private fun executePortfolioSatisfy(
    result: SolveResult,
    callbacks: FiniteExecutionCallbacks,
    start: TimeMark,
): FiniteExecutionResult.Completed {
    if (result is SolveResult.Sat) callbacks.onSample(result.assignment)
    return FiniteExecutionResult.Completed(
        verdict = result.finitePortfolioVerdict(),
        stats = result.stats,
        solutions = if (result is SolveResult.Sat) 1 else 0,
        bestSample = null,
        elapsedMs = start.elapsedNow().inWholeMilliseconds,
        terminationReason = (result as? SolveResult.Unknown)?.reason,
    )
}

private fun executePortfolioOptimize(
    result: MinimizeResult,
    streamed: Long,
    callbacks: FiniteExecutionCallbacks,
    start: TimeMark,
): FiniteExecutionResult.Completed {
    val sample = (result as? MinimizeResult.WithSample)?.sample
    if (streamed == 0L && sample != null) callbacks.onSample(sample)
    return FiniteExecutionResult.Completed(
        verdict = result.finitePortfolioVerdict(),
        stats = result.stats,
        solutions = if (streamed > 0L) {
            streamed
        } else if (sample == null) {
            0
        } else {
            1
        },
        bestSample = sample,
        elapsedMs = start.elapsedNow().inWholeMilliseconds,
        unboundedDirection = (result as? MinimizeResult.Unbounded)?.direction,
        terminationReason = result.incompleteReason(),
        gapBound = (result as? MinimizeResult.Optimal)?.gapBound,
    )
}

internal fun SolveResult.finitePortfolioVerdict(): FiniteExecutionVerdict = when (this) {
    is SolveResult.Sat -> FiniteExecutionVerdict.SAT
    is SolveResult.Unsat -> FiniteExecutionVerdict.UNSAT
    is SolveResult.Unknown -> FiniteExecutionVerdict.UNKNOWN
}

internal fun MinimizeResult.finitePortfolioVerdict(): FiniteExecutionVerdict = when (this) {
    is MinimizeResult.Optimal -> FiniteExecutionVerdict.OPTIMAL
    is MinimizeResult.Unbounded -> FiniteExecutionVerdict.UNBOUNDED
    is MinimizeResult.BestFound -> FiniteExecutionVerdict.BEST_FOUND
    is MinimizeResult.Infeasible -> FiniteExecutionVerdict.UNSAT
    is MinimizeResult.Unknown -> FiniteExecutionVerdict.UNKNOWN
}

private fun MinimizeResult.incompleteReason(): TerminationReason? = when (this) {
    is MinimizeResult.Unknown -> reason
    is MinimizeResult.BestFound -> reason
    else -> null
}

private fun localSearchDryRun(pool: List<() -> LocalSearchRecipe>?): FiniteExecutionResult.DryRun {
    val recipes = pool?.map { it() } ?: LocalSearchCatalog.ranked(Kind.COP)
    return FiniteExecutionResult.DryRun(
        heading = "ls dry-run: ${recipes.size} arm(s)",
        lines = recipes.map { recipe ->
            val sources = recipe.strategy.sources.joinToString(",") { it.source.id.label }
            val restart = recipe.strategy.schedule.restart?.let { it::class.simpleName } ?: "default"
            val temperature = recipe.strategy.schedule.temperature?.let { it::class.simpleName } ?: "none"
            "  ${recipe.label}: sources=[$sources] scoring=${recipe.strategy.scoring} " +
                "acceptance=${recipe.strategy.acceptance} restart=$restart temperature=$temperature"
        },
    )
}

private fun backtrackDryRun(
    problem: BakedProblem,
    pool: List<() -> BacktrackRecipe>?,
    kind: Kind,
    zeroObjectivePricing: LpZeroObjectivePricing,
): FiniteExecutionResult.DryRun {
    val recipes = pool?.map { it() } ?: BacktrackCatalog.ranked(kind)
    val solver = BacktrackSolver(problem)
    return FiniteExecutionResult.DryRun(
        heading = "solver dry-run: ${recipes.size} backtrack arm(s)",
        lines = buildList {
            for (recipe in recipes) {
                add("  ${recipe.label}:")
                solver.describe(
                    recipe.build(0L, null).copy(zeroObjectivePricing = zeroObjectivePricing),
                ).lines().forEach { add("    $it") }
            }
        },
    )
}

private fun AttributedImprovement.toFiniteImprovement(): FiniteImprovement {
    val improvement = result as MinimizeResult.WithSample
    return FiniteImprovement(improvement.sample, workerLabel, elapsed.inWholeMilliseconds, improvement.objective)
}
