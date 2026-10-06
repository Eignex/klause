package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.SearchOutcome
import com.eignex.klause.backtrack.toBacktrackParams
import com.eignex.klause.formats.flatzinc.FlatZincSearchHints
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.lp.relaxation.provesUnbounded
import com.eignex.klause.portfolio.ArmFault
import com.eignex.klause.portfolio.AttributedImprovement
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.withPresolve
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A complete finite solve request, from model preparation through engine execution. */
class FiniteSolveRequest(
    /** Source-independent model data supplied by the frontend. */
    val shape: FiniteSolveShape,
    /** Finite engine route selected by the frontend. */
    val engine: FiniteEngine,
    /** Presolve configuration requested by the caller. */
    val presolveConfig: PresolveConfig,
    /** Whether [presolveConfig] was selected explicitly. */
    val explicitPresolveConfig: Boolean,
    /** Whether the caller needs the exact solution set. */
    val solutionSetSensitive: Boolean,
    /** Cancellation shared by every engine worker. */
    val cancellation: Cancellation,
    /** Cancellation applying only while the model is prepared. */
    val presolveCancellation: Cancellation = cancellation,
    /** Optional budget allocated to preparation. */
    val presolveBudget: PresolveBudget?,
    /** Portfolio worker count. */
    val cores: Int,
    /** Repeatable engine tuning parameters. */
    val engineParams: List<String>,
    /** Optional random seed. */
    val randomSeed: Long?,
    /** Default portfolio arm count. */
    val defaultArms: Int,
    /** Maximum LP emphasis for the selected route. */
    val lpConfig: LpConfig,
    /** Entering-column policy for exactly zero-objective LP solves. */
    val zeroObjectivePricing: LpZeroObjectivePricing,
    /** Invocation-wide node allowance. */
    val nodeBudget: NodeBudget?,
    /** Advisory wall-clock allowance supplied to fixed-backtrack LP components. */
    val solveBudgetMillis: Long?,
    /** Whether satisfaction should enumerate models. */
    val allSolutions: Boolean,
    /** Optional cap applied to satisfaction enumeration. */
    val solutionCap: Long?,
    /** True after the frontend's shared wall-clock deadline has elapsed. */
    val deadlineExceeded: () -> Boolean,
    /** Optional fixed-backtrack engine event sink. */
    val onEvent: ((SearchEvent) -> Unit)?,
    /** Optional per-worker portfolio engine event sink. */
    val onPortfolioEvent: ((worker: String, event: SearchEvent) -> Unit)?,
    /** Told about each portfolio arm quarantined for a result the model refutes. */
    val onPortfolioFault: ((ArmFault) -> Unit)? = null,
    /** Whether to stop after preparation. */
    val prepareOnly: Boolean = false,
    /**
     * Tolerance semantics for continuous leaves: a check of a source-space sample (the model as the caller built
     * it, before presolve). When set, a float LP optimum may decide a leaf, and a float incumbent may stand, if the
     * check accepts it; `null` keeps exact semantics.
     */
    val toleranceCheck: ((Sample) -> Boolean)? = null,
)

/** The prepared model and terminal engine result of one finite solve. */
class FiniteSolveResult(
    /** Model preparation shared by the selected engine route. */
    val preparation: FinitePipelinePreparation,
    /** Time spent preparing [preparation]. */
    val preparationElapsed: Duration,
    /** Terminal result after preparation. */
    val outcome: FiniteSolveOutcome,
)

/** Streaming hooks exposed by the finite solve facade. */
class FiniteSolveCallbacks(
    /** A satisfying assignment or fixed-route improving incumbent. */
    val onSample: (Sample) -> Unit,
    /** A portfolio improving incumbent. */
    val onImprovement: (FiniteSolveImprovement) -> Unit,
)

/** A portfolio improvement exposed by the finite solve facade. */
class FiniteSolveImprovement(
    /** Improving assignment in the source model. */
    val sample: Sample,
    /** Label of the portfolio worker that produced the assignment. */
    val workerLabel: String,
    /** Elapsed milliseconds since portfolio minimization began. */
    val elapsedMs: Long,
    /** Engine-normalized objective value at [sample]. */
    val objective: Double,
)

/** Terminal outcome exposed by the finite solve facade. */
sealed class FiniteSolveOutcome {
    /** Preparation was inspected without executing an engine. */
    data object PreparedOnly : FiniteSolveOutcome()

    /** The engine route was inspected instead of executed. */
    class DryRun(
        /** Heading printed before [lines]. */
        val heading: String,
        /** Render-ready dry-run detail lines. */
        val lines: List<String>,
    ) : FiniteSolveOutcome()

    /** A completed solve. */
    class Completed(
        /** Final finite-search verdict. */
        val verdict: FiniteSolveVerdict,
        /** Aggregate engine statistics. */
        val stats: SolveStats,
        /** Number of streamed models. */
        val solutions: Long,
        /** Best source-model assignment, when optimization found one. */
        val bestSample: Sample?,
        /** Elapsed engine execution time. */
        val elapsedMs: Long,
        /**
         * On [FiniteSolveVerdict.UNBOUNDED], the source-model ray of the continuous variables, indexed by real
         * variable id, along which [bestSample] stays feasible and the objective strictly decreases.
         */
        val unboundedDirection: List<BigFraction>? = null,
    ) : FiniteSolveOutcome()
}

/** Verdict vocabulary exposed by the finite solve facade. */
enum class FiniteSolveVerdict {
    /** A satisfying assignment was found. */
    SAT,

    /** The model was proven infeasible. */
    UNSAT,

    /** No definitive result was produced. */
    UNKNOWN,

    /** An optimum was proven. */
    OPTIMAL,

    /** A feasible incumbent was found without an optimality proof. */
    BEST_FOUND,

    /** A feasible assignment and a ray along which the objective descends without limit were proven. */
    UNBOUNDED,
}

/** One finite solve request after a frontend has translated its flags and source-model annotations. */
internal class FiniteExecutionRequest(
    /** Prepared finite model to solve. */
    val problem: BakedProblem,
    /** Finite engine route selected by the frontend. */
    val engine: FiniteEngine,
    /** Whether to minimize [objective]. */
    val optimize: Boolean,
    /** Canonical objective minimized by complete engines, or null for satisfaction. */
    val objective: LinearObjective?,
    /** Per-move objective view for local-search workers. */
    val localSearchObjective: IncrementalObjective?,
    /** Optional FlatZinc definitional sweep for local-search workers. */
    val definitionalSweep: DefinitionalSweep?,
    /** Optional decoded source search hints. */
    val searchHints: FlatZincSearchHints?,
    /** Portfolio worker count. Ignored by the fixed route. */
    val cores: Int,
    /** Repeatable engine tuning parameters. */
    val engineParams: List<String>,
    /** Optional random seed. */
    val randomSeed: Long?,
    /** Default portfolio arm count. */
    val defaultArms: Int,
    /** Maximum LP emphasis for the selected route. */
    val lpConfig: LpConfig,
    /** Entering-column policy for exactly zero-objective LP solves. */
    val zeroObjectivePricing: LpZeroObjectivePricing,
    /** Cancellation shared by every engine worker. */
    val cancellation: Cancellation,
    /** Invocation-wide node allowance. */
    val nodeBudget: NodeBudget?,
    /** Advisory wall-clock allowance supplied to fixed-backtrack LP components. */
    val solveBudgetMillis: Long?,
    /** Whether satisfaction should enumerate models. */
    val allSolutions: Boolean,
    /** Optional cap applied to satisfaction enumeration. */
    val solutionCap: Long?,
    /** True after the frontend's shared wall-clock deadline has elapsed. */
    val deadlineExceeded: () -> Boolean,
    /** Optional fixed-backtrack engine event sink. */
    val onEvent: ((SearchEvent) -> Unit)?,
    /** Optional per-worker portfolio engine event sink. */
    val onPortfolioEvent: ((worker: String, event: SearchEvent) -> Unit)?,
    /** Told about each portfolio arm quarantined for a result the model refutes. */
    val onPortfolioFault: ((ArmFault) -> Unit)? = null,
    /** LP dependencies for the fixed complete route. */
    val lpSolveContext: LpSolveContext,
    /** Tolerance semantics for continuous leaves; see [BacktrackParams.toleranceCheck]. */
    val toleranceCheck: ((Sample) -> Boolean)? = null,
)

/** Streaming hooks owned by a rendering frontend. */
private class FiniteExecutionCallbacks(
    /** A satisfaction model or a fixed-backtrack improving incumbent. */
    val onSample: (Sample) -> Unit,
    /** A portfolio improving incumbent, with its worker attribution. */
    val onImprovement: (FiniteImprovement) -> Unit,
)

/** A strict portfolio incumbent emitted while [FiniteExecutionRequest.optimize] is true. */
private class FiniteImprovement(
    /** Improving assignment. */
    val sample: Sample,
    /** Label of the portfolio worker that produced the assignment. */
    val workerLabel: String,
    /** Elapsed milliseconds since portfolio minimization began. */
    val elapsedMs: Long,
    /** Engine-normalized objective value at [sample]. */
    val objective: Double,
)

/** Terminal outcome of a finite execution. */
private sealed class FiniteExecutionResult {
    /** Execution completed and the frontend should render [verdict] plus [stats]. */
    class Completed(
        /** Final finite-search verdict. */
        val verdict: FiniteExecutionVerdict,
        /** Engine statistics for the execution. */
        val stats: SolveStats,
        /** Number of streamed models. */
        val solutions: Long,
        /** Best assignment, when optimization found one. */
        val bestSample: Sample?,
        /** Elapsed execution time, excluding route planning and worker construction. */
        val elapsedMs: Long,
        /** On [FiniteExecutionVerdict.UNBOUNDED], the recession ray of the reals through [bestSample]. */
        val unboundedDirection: List<BigFraction>? = null,
    ) : FiniteExecutionResult()

    /** The route was inspected instead of executed. */
    class DryRun(
        /** Heading printed before [lines]. */
        val heading: String,
        /** Render-ready dry-run detail lines. */
        val lines: List<String>,
    ) : FiniteExecutionResult()
}

/** Verdict vocabulary shared by finite engines, independent of a frontend protocol. */
private enum class FiniteExecutionVerdict {
    /** A satisfying assignment was found. */
    SAT,

    /** The model was proven infeasible. */
    UNSAT,

    /** No definitive result was produced. */
    UNKNOWN,

    /** An optimum was proven. */
    OPTIMAL,

    /**
     * A feasible incumbent was found without an optimality proof.
     */
    BEST_FOUND,

    /** A feasible assignment and a ray along which the objective descends without limit were proven. */
    UNBOUNDED,
}

/** Owns finite engine planning, construction, and execution. */
private fun FinitePipeline.execute(
    request: FiniteExecutionRequest,
    callbacks: FiniteExecutionCallbacks,
): FiniteExecutionResult = when (request.engine) {
    FiniteEngine.FIXED -> executeFixed(request, callbacks)

    FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.MIXED, FiniteEngine.ALNS ->
        executePortfolio(request, callbacks)
}

/** Prepare a finite model once, then execute the selected engine route. */
fun FinitePipeline.solve(request: FiniteSolveRequest, callbacks: FiniteSolveCallbacks): FiniteSolveResult =
    solve(request, callbacks, LpSolveContext.Production)

/** Execute the fixed finite route with per-invocation LP dependencies. */
internal fun FinitePipeline.solve(
    request: FiniteSolveRequest,
    callbacks: FiniteSolveCallbacks,
    lpSolveContext: LpSolveContext,
): FiniteSolveResult {
    require(request.engine == FiniteEngine.FIXED || lpSolveContext == LpSolveContext.Production) {
        "custom LP dependencies require the fixed finite route"
    }
    val preparationStart = TimeSource.Monotonic.markNow()
    val preparation = prepare(
        FinitePipelineRequest(
            problem = request.shape.finiteProblem,
            componentPlan = request.shape.componentPlan,
            engine = request.engine,
            objective = request.shape.linearObjective,
            presolveConfig = request.presolveConfig,
            explicitPresolveConfig = request.explicitPresolveConfig,
            solutionSetSensitive = request.solutionSetSensitive,
            cancellation = request.presolveCancellation,
            solveCancellation = request.cancellation,
            presolveBudget = request.presolveBudget,
            zeroObjectivePricing = request.zeroObjectivePricing,
            randomSeed = request.randomSeed,
        ),
    )
    val preparationElapsed = preparationStart.elapsedNow()
    if (request.prepareOnly) return FiniteSolveResult(preparation, preparationElapsed, FiniteSolveOutcome.PreparedOnly)
    if (preparation.presolve?.infeasible == true) {
        return FiniteSolveResult(
            preparation,
            preparationElapsed,
            FiniteExecutionResult.Completed(
                FiniteExecutionVerdict.UNSAT,
                SolveStats.EMPTY,
                0,
                null,
                0,
            ).toSolveOutcome().withPresolve(preparation.presolve),
        )
    }
    val execution = execute(
        FiniteExecutionRequest(
            problem = preparation.executableProblem(),
            engine = request.engine,
            optimize = request.shape.optimize,
            objective = preparation.objective,
            localSearchObjective = request.shape.localSearchObjective,
            definitionalSweep = request.shape.definitionalSweep,
            searchHints = request.shape.searchHints,
            cores = request.cores,
            engineParams = request.engineParams,
            randomSeed = request.randomSeed,
            defaultArms = request.defaultArms,
            lpConfig = request.lpConfig,
            zeroObjectivePricing = request.zeroObjectivePricing,
            cancellation = request.cancellation,
            nodeBudget = request.nodeBudget,
            solveBudgetMillis = request.solveBudgetMillis,
            allSolutions = request.allSolutions,
            solutionCap = request.solutionCap,
            deadlineExceeded = request.deadlineExceeded,
            onEvent = request.onEvent,
            onPortfolioEvent = request.onPortfolioEvent,
            onPortfolioFault = request.onPortfolioFault,
            lpSolveContext = lpSolveContext,
            toleranceCheck = request.toleranceCheck?.let { check -> { check(preparation.reconstruct(it)) } },
        ),
        FiniteExecutionCallbacks(
            onSample = { sample -> callbacks.onSample(preparation.reconstruct(sample)) },
            onImprovement = { improvement ->
                callbacks.onImprovement(
                    FiniteSolveImprovement(
                        preparation.reconstruct(improvement.sample),
                        improvement.workerLabel,
                        improvement.elapsedMs,
                        improvement.objective,
                    ),
                )
            },
        ),
    )
    return FiniteSolveResult(
        preparation,
        preparationElapsed,
        execution.reconstructed(preparation.reconstruct, request.shape.finiteProblem, request.shape.linearObjective)
            .toSolveOutcome().withPresolve(preparation.presolve),
    )
}

private fun FiniteExecutionResult.toSolveOutcome(): FiniteSolveOutcome = when (this) {
    is FiniteExecutionResult.DryRun -> FiniteSolveOutcome.DryRun(heading, lines)

    is FiniteExecutionResult.Completed -> FiniteSolveOutcome.Completed(
        FiniteSolveVerdict.valueOf(verdict.name),
        stats,
        solutions,
        bestSample,
        elapsedMs,
        unboundedDirection,
    )
}

private fun FiniteSolveOutcome.withPresolve(presolve: PresolveStats?): FiniteSolveOutcome = when (this) {
    is FiniteSolveOutcome.Completed -> FiniteSolveOutcome.Completed(
        verdict,
        stats.withPresolve(presolve),
        solutions,
        bestSample,
        elapsedMs,
        unboundedDirection,
    )

    else -> this
}

/**
 * This result over the source [problem]. An unbounded verdict stands only if its witness and ray, lifted through
 * [reconstruct], still prove [objective] unbounded over [problem]; otherwise the witness remains a feasible
 * assignment with no optimality proof.
 */
private fun FiniteExecutionResult.reconstructed(
    reconstruct: (Sample) -> Sample,
    problem: Problem,
    objective: LinearObjective?,
): FiniteExecutionResult = when (this) {
    is FiniteExecutionResult.DryRun -> this

    is FiniteExecutionResult.Completed -> {
        val point = bestSample?.let(reconstruct)
        val direction = if (verdict == FiniteExecutionVerdict.UNBOUNDED && point != null && objective != null) {
            liftedDirection(reconstruct, point)?.takeIf { problem.provesUnbounded(objective, point, it) }
        } else {
            null
        }
        FiniteExecutionResult.Completed(
            if (verdict == FiniteExecutionVerdict.UNBOUNDED && direction == null) {
                FiniteExecutionVerdict.BEST_FOUND
            } else {
                verdict
            },
            stats,
            solutions,
            point,
            elapsedMs,
            direction,
        )
    }
}

/**
 * The ray through the lifted [point] that [reconstruct] maps the prepared ray onto, as the difference of the two
 * lifted assignments one step apart. The source check, not this lift, is what proves it a ray; a lift that moves
 * a discrete value or loses the exact reals yields none.
 */
private fun FiniteExecutionResult.Completed.liftedDirection(
    reconstruct: (Sample) -> Sample,
    point: Sample,
): List<BigFraction>? {
    val origin = bestSample ?: return null
    val reals = origin.exactReals ?: return null
    val direction = unboundedDirection ?: return null
    if (direction.size != reals.size) return null
    val shifted = List(reals.size) { reals[it] + direction[it] }
    val stepped = reconstruct(
        origin.copy(reals = DoubleArray(shifted.size) { shifted[it].toDouble() }, exactReals = shifted),
    )
    val from = point.exactReals ?: return null
    val to = stepped.exactReals ?: return null
    if (!stepped.bools.contentEquals(point.bools) || !stepped.ints.contentEquals(point.ints)) return null
    if (from.size != to.size) return null
    return List(from.size) { to[it] - from[it] }
}

private fun executeFixed(request: FiniteExecutionRequest, callbacks: FiniteExecutionCallbacks): FiniteExecutionResult {
    val plan = FinitePipeline.planFixedBacktrack(
        FixedBacktrackPlanRequest(
            annotatedParams = request.searchHints?.toBacktrackParams(
                request.problem.numBoolVars,
                request.problem.numIntVars,
            ),
            engineParams = request.engineParams,
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

    val params = plan.params.copy(toleranceCheck = request.toleranceCheck)
    val start = TimeSource.Monotonic.markNow()
    return if (request.optimize) {
        executeFixedOptimize(solver, params, requireNotNull(request.objective), callbacks, start)
    } else {
        executeFixedSatisfy(solver, params, request, callbacks, start)
    }
}

private fun executeFixedSatisfy(
    solver: BacktrackSolver,
    params: BacktrackParams,
    request: FiniteExecutionRequest,
    callbacks: FiniteExecutionCallbacks,
    start: TimeMark,
): FiniteExecutionResult.Completed {
    val limit = if (request.allSolutions) request.solutionCap ?: Long.MAX_VALUE else 1L
    if (limit == 1L) {
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
        )
    }

    var solutions = 0L
    var timedOut = false
    var indeterminate = false
    for (outcome in solver.enumerationOutcomes(params)) {
        when (outcome) {
            is SearchOutcome.Found -> {
                if (request.deadlineExceeded()) {
                    timedOut = true
                    break
                }
                callbacks.onSample(outcome.sample)
                solutions++
                if (solutions >= limit) break
            }

            is SearchOutcome.Exhausted -> {
                indeterminate = outcome.indeterminate
                break
            }

            SearchOutcome.BudgetCapped -> {
                timedOut = true
                break
            }
        }
    }
    val verdict = when {
        (timedOut || indeterminate) && solutions == 0L -> FiniteExecutionVerdict.UNKNOWN
        solutions == 0L -> FiniteExecutionVerdict.UNSAT
        else -> FiniteExecutionVerdict.SAT
    }
    return FiniteExecutionResult.Completed(
        verdict = verdict,
        stats = SolveStats.EMPTY,
        solutions = solutions,
        bestSample = null,
        elapsedMs = start.elapsedNow().inWholeMilliseconds,
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
    for (step in solver.improvements(objective, params)) {
        stats = step.stats
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
    )
}

private fun executePortfolio(
    request: FiniteExecutionRequest,
    callbacks: FiniteExecutionCallbacks,
): FiniteExecutionResult {
    val plan = FinitePipeline.planPortfolio(
        PortfolioPlanRequest(
            engine = request.engine,
            optimize = request.optimize,
            cores = request.cores,
            engineParams = request.engineParams,
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
            realColumns = request.problem.numRealVars > 0,
        ),
    )
    when (plan) {
        is PortfolioPlan.LocalSearchDryRun -> return localSearchDryRun(plan.pool)

        is PortfolioPlan.BacktrackDryRun ->
            return backtrackDryRun(request.problem, plan.pool, plan.kind, plan.zeroObjectivePricing)

        is PortfolioPlan.Execute -> Unit
    }
    val scenario = plan.scenario
    val executor = FinitePipeline.portfolioExecutor(
        request.problem,
        scenario,
        objective = request.objective,
        lsObjective = request.localSearchObjective,
        definitionalSweep = request.definitionalSweep,
        onEvent = request.onPortfolioEvent,
        onFault = request.onPortfolioFault,
    )
    val start = TimeSource.Monotonic.markNow()
    executor.use {
        return if (request.optimize) {
            var streamed = 0L
            val result = it.minimize(request.cancellation) { improvement ->
                callbacks.onImprovement(improvement.toFiniteImprovement())
                streamed++
            }
            executePortfolioOptimize(result, streamed, !request.engine.pureLocalSearch, callbacks, start)
        } else {
            executePortfolioSatisfy(it.solve(request.cancellation), !request.engine.pureLocalSearch, callbacks, start)
        }
    }
}

private fun executePortfolioSatisfy(
    result: SolveResult,
    complete: Boolean,
    callbacks: FiniteExecutionCallbacks,
    start: TimeMark,
): FiniteExecutionResult.Completed {
    val verdict = when (result) {
        is SolveResult.Sat -> {
            callbacks.onSample(result.assignment)
            FiniteExecutionVerdict.SAT
        }

        is SolveResult.Unsat -> if (complete) FiniteExecutionVerdict.UNSAT else FiniteExecutionVerdict.UNKNOWN

        is SolveResult.Unknown -> FiniteExecutionVerdict.UNKNOWN
    }
    return FiniteExecutionResult.Completed(
        verdict = verdict,
        stats = result.stats,
        solutions = if (result is SolveResult.Sat) 1 else 0,
        bestSample = null,
        elapsedMs = start.elapsedNow().inWholeMilliseconds,
    )
}

private fun executePortfolioOptimize(
    result: MinimizeResult,
    streamed: Long,
    complete: Boolean,
    callbacks: FiniteExecutionCallbacks,
    start: TimeMark,
): FiniteExecutionResult.Completed {
    val verdict = when (result) {
        is MinimizeResult.Optimal -> FiniteExecutionVerdict.OPTIMAL
        is MinimizeResult.Unbounded -> FiniteExecutionVerdict.UNBOUNDED
        is MinimizeResult.BestFound -> FiniteExecutionVerdict.BEST_FOUND
        is MinimizeResult.Infeasible -> if (complete) FiniteExecutionVerdict.UNSAT else FiniteExecutionVerdict.UNKNOWN
        is MinimizeResult.Unknown -> FiniteExecutionVerdict.UNKNOWN
    }
    val sample = (result as? MinimizeResult.WithSample)?.sample
    if (streamed == 0L && sample != null) callbacks.onSample(sample)
    return FiniteExecutionResult.Completed(
        verdict = verdict,
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
    )
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
