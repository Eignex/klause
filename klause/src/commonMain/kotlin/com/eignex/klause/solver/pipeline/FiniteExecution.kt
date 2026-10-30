package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.SearchOutcome
import com.eignex.klause.backtrack.toBacktrackParams
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.lp.relaxation.provesUnbounded
import com.eignex.klause.portfolio.ArmFault
import com.eignex.klause.portfolio.AttributedImprovement
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.LeafRealCompletion
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.effortStats
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.encodingPreparationScope
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.withPresolve
import com.eignex.klause.solver.validation.FiniteSourceAcceptance
import com.eignex.klause.solver.validation.LinearSourceCheck
import com.eignex.klause.solver.validation.permitsToleranceOptimum
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
    /** Immutable engine tuning settings. */
    val executionOptions: ExecutionOptions,
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
) {
    private var capturedSourceControls: SourceFiniteControls? = null
    internal val sourceControls: SourceFiniteControls? get() = capturedSourceControls

    internal constructor(shape: FiniteSolveShape, controls: SourceFiniteControls) : this(
        shape = shape,
        engine = controls.engine,
        presolveConfig = PresolveConfig.NONE,
        explicitPresolveConfig = true,
        solutionSetSensitive = true,
        cancellation = controls.cancellation,
        presolveBudget = null,
        cores = 1,
        executionOptions = ExecutionOptions(),
        randomSeed = controls.randomSeed,
        defaultArms = 1,
        lpConfig = LpConfig.OFF,
        zeroObjectivePricing = (controls as? SourceFiniteControls.Backtrack)?.params?.zeroObjectivePricing
            ?: LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
        nodeBudget = controls.nodeBudget,
        solveBudgetMillis = (controls as? SourceFiniteControls.Backtrack)?.params?.solveBudgetMillis,
        allSolutions = false,
        solutionCap = 1L,
        deadlineExceeded = { controls.cancellation.deadline()?.hasPassedNow() == true },
        onEvent = null,
        onPortfolioEvent = null,
    ) {
        require(shape.sourceInvocation?.source?.originalSource is Problem &&
            operation in setOf(ExecutionOperation.SATISFY, ExecutionOperation.OPTIMIZE)
        ) { "native finite controls require their captured factor-source operation" }
        capturedSourceControls = controls
    }

    /** Source operation; a solution cap bounds enumeration without changing its identity. */
    val operation: ExecutionOperation = shape.sourceInvocation?.operation ?: when {
        shape.optimize -> ExecutionOperation.OPTIMIZE
        allSolutions || (solutionCap ?: 1L) > 1L -> ExecutionOperation.ENUMERATE
        else -> ExecutionOperation.SATISFY
    }

    init {
        shape.sourceInvocation?.requireFiniteExecution(shape, engine, operation)
        require(
            shape.sourceInvocation == null || shape.optimize || operation != ExecutionOperation.SATISFY ||
                (!allSolutions && (solutionCap ?: 1L) <= 1L),
        ) { "enumeration needs its captured source operation" }
    }

    /** Captured frontend `key=value` parameters; empty for directly typed options. */
    val engineParams: List<String> get() = executionOptions.originalPairs.toList()

    /** Build the request from repeatable frontend `key=value` parameters. */
    constructor(
        shape: FiniteSolveShape,
        engine: FiniteEngine,
        presolveConfig: PresolveConfig,
        explicitPresolveConfig: Boolean,
        solutionSetSensitive: Boolean,
        cancellation: Cancellation,
        presolveCancellation: Cancellation = cancellation,
        presolveBudget: PresolveBudget?,
        cores: Int,
        engineParams: List<String>,
        randomSeed: Long?,
        defaultArms: Int,
        lpConfig: LpConfig,
        zeroObjectivePricing: LpZeroObjectivePricing,
        nodeBudget: NodeBudget?,
        solveBudgetMillis: Long?,
        allSolutions: Boolean,
        solutionCap: Long?,
        deadlineExceeded: () -> Boolean,
        onEvent: ((SearchEvent) -> Unit)?,
        onPortfolioEvent: ((worker: String, event: SearchEvent) -> Unit)?,
        onPortfolioFault: ((ArmFault) -> Unit)? = null,
        prepareOnly: Boolean = false,
        toleranceCheck: ((Sample) -> Boolean)? = null,
    ) : this(
        shape = shape,
        engine = engine,
        presolveConfig = presolveConfig,
        explicitPresolveConfig = explicitPresolveConfig,
        solutionSetSensitive = solutionSetSensitive,
        cancellation = cancellation,
        presolveCancellation = presolveCancellation,
        presolveBudget = presolveBudget,
        cores = cores,
        executionOptions = ExecutionOptions.parse(engineParams),
        randomSeed = randomSeed,
        defaultArms = defaultArms,
        lpConfig = lpConfig,
        zeroObjectivePricing = zeroObjectivePricing,
        nodeBudget = nodeBudget,
        solveBudgetMillis = solveBudgetMillis,
        allSolutions = allSolutions,
        solutionCap = solutionCap,
        deadlineExceeded = deadlineExceeded,
        onEvent = onEvent,
        onPortfolioEvent = onPortfolioEvent,
        onPortfolioFault = onPortfolioFault,
        prepareOnly = prepareOnly,
        toleranceCheck = toleranceCheck,
    )
}

/** The prepared model and terminal engine result of one finite solve. */
class FiniteSolveResult(
    /** Canonical model preparation used for result qualification. */
    val preparation: FinitePipelinePreparation,
    /** Time spent preparing the canonical model and any private search view. */
    val preparationElapsed: Duration,
    /** Terminal result, scoped to the grid when [SourceFloatProjection.declaredGridSemantics] is true. */
    val outcome: FiniteSolveOutcome,
) {
    private var completeSearch: Boolean? = null

    /** Whether the resolved working strategy admits a complete search arm; absent before strategy resolution. */
    val completeSearchAdmitted: Boolean? get() = completeSearch

    internal fun bindCompleteSearchAdmission(value: Boolean?) {
        completeSearch = value
    }

    private var retainedSourceResult: SolveResult? = null
    internal val nativeSourceResult: SolveResult? get() = retainedSourceResult
    private var retainedSourceMinimum: MinimizeResult? = null
    internal val nativeSourceMinimum: MinimizeResult? get() = retainedSourceMinimum

    internal constructor(
        preparation: FinitePipelinePreparation,
        preparationElapsed: Duration,
        outcome: FiniteSolveOutcome,
        nativeSourceResult: SolveResult?,
    ) : this(preparation, preparationElapsed, outcome) {
        retainedSourceResult = nativeSourceResult
    }

    internal constructor(
        preparation: FinitePipelinePreparation,
        preparationElapsed: Duration,
        outcome: FiniteSolveOutcome,
        nativeSourceResult: SolveResult?,
        nativeSourceMinimum: MinimizeResult?,
    ) : this(preparation, preparationElapsed, outcome, nativeSourceResult) {
        retainedSourceMinimum = nativeSourceMinimum
    }

    private var grid: NativeGridOutcome? = null

    /** Native private-grid claims, retained independently of [outcome] publication qualification. */
    val nativeGridOutcome: NativeGridOutcome? get() = grid

    internal fun bindNativeGridOutcome(value: NativeGridOutcome) {
        require(grid == null) { "finite result already retains its native grid outcome" }
        grid = value
    }
}

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
    val preparedPlan: PreparedSourcePlan,
    /** Finite engine route selected by the frontend. */
    val engine: FiniteEngine,
    /** Whether to minimize [objective]. */
    val optimize: Boolean,
    /** Canonical objective minimized by complete engines, or null for satisfaction. */
    val objective: LinearObjective?,
    /** Per-move objective view for local-search workers. */
    val localSearchObjective: IncrementalObjective?,
    /** Optional source-level definitional sweep for local-search workers. */
    val definitionalSweep: DefinitionalSweep?,
    /** Optional decoded source search hints. */
    val searchHints: SearchHints?,
    /** Portfolio worker count. Ignored by the fixed route. */
    val cores: Int,
    /** Repeatable engine tuning parameters. */
    val executionOptions: ExecutionOptions,
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
    val sourceInvocation: PreparedSourceInvocation? = null,
    val sourceMapping: SourceMapping? = null,
    val sourceControls: SourceFiniteControls? = null,
    val numericalCompanionParams: TheoryParams? = null,
    val objectiveCoordinates: ObjectiveCoordinateView? = null,
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
        val nativeSourceResult: SolveResult? = null,
        val nativeSourceMinimum: MinimizeResult? = null,
    ) : FiniteExecutionResult() {
        var completeSearchAdmitted: Boolean? = null
    }

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

    /** A feasible incumbent was found without an optimality proof. */
    BEST_FOUND,

    /** A feasible assignment and a ray along which the objective descends without limit were proven. */
    UNBOUNDED,
}

/** Owns finite engine planning, construction, and execution. */
private fun FinitePipeline.execute(
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

/** Prepare the canonical finite model and optional heuristic view, then execute the selected engine route. */
fun FinitePipeline.solve(request: FiniteSolveRequest, callbacks: FiniteSolveCallbacks): FiniteSolveResult =
    solve(request, callbacks, LpSolveContext.Production)

internal fun FinitePipeline.solve(request: FiniteSolveRequest, callbacks: FiniteSolveCallbacks,
    numericalCompanions: TheoryParams): FiniteSolveResult =
    solve(request, callbacks, LpSolveContext.Production, numericalCompanions)

/** Execute the fixed finite route with per-invocation LP dependencies. */
internal fun FinitePipeline.solve(
    request: FiniteSolveRequest,
    callbacks: FiniteSolveCallbacks,
    lpSolveContext: LpSolveContext,
    numericalCompanions: TheoryParams? = null,
): FiniteSolveResult {
    require(request.engine == FiniteEngine.FIXED || lpSolveContext == LpSolveContext.Production) {
        "custom LP dependencies require the fixed finite route"
    }
    request.shape.sourceInvocation?.freezeExecution()
    val stop = request.shape.sourceInvocation?.let {
        (request.cancellation or it.cancellation).encodingPreparationScope()
    } ?: request.cancellation
    val gridPublication = request.shape.sourceInvocation?.floatGrid?.let {
        FloatGridPublication(it, stop, request.toleranceCheck, callbacks)
    }
    fun result(
        preparation: FinitePipelinePreparation,
        elapsed: Duration,
        outcome: FiniteSolveOutcome,
        nativeSourceResult: SolveResult? = null,
        nativeSourceMinimum: MinimizeResult? = null,
        completeSearchAdmitted: Boolean? = null,
    ): FiniteSolveResult =
        (gridPublication?.result(preparation, elapsed, outcome, nativeSourceResult, nativeSourceMinimum)
            ?: FiniteSolveResult(preparation, elapsed, outcome, nativeSourceResult, nativeSourceMinimum))
            .also { it.bindCompleteSearchAdmission(completeSearchAdmitted) }
    if (request.shape.sourceInvocation != null && stop()) {
        return result(
            FinitePipelinePreparation(request.shape.problem, request.shape.linearObjective, { it }, null),
            Duration.ZERO,
            FiniteSolveOutcome.Completed(FiniteSolveVerdict.UNKNOWN, SolveStats.EMPTY, 0L, null, 0L),
        )
    }
    val preparationStart = TimeSource.Monotonic.markNow()
    var preparation = prepare(request.preparationRequest(stop))
    var preparationElapsed = preparationStart.elapsedNow()
    if (request.prepareOnly) return result(preparation, preparationElapsed, FiniteSolveOutcome.PreparedOnly)
    if (preparation.presolve?.infeasible == true) {
        return result(
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
    val executionPlan = preparation.executionPlan ?: request.shape.preparedPlan
    val searchHints = if (request.shape.sourceInvocation == null) {
        request.shape.searchHints
    } else {
        request.shape.searchHints?.let { hints ->
            preparation.sourceMapping?.let { hints.remapped(it, stop) }
        }
    }
    if ((request.shape.sourceInvocation != null && stop()) || executionPlan.decline == PreparedPlanDecline.RESOURCE) {
        return result(
            preparation,
            preparationElapsed,
            FiniteSolveOutcome.Completed(FiniteSolveVerdict.UNKNOWN, SolveStats.EMPTY, 0L, null, 0L),
        )
    }
    val objectiveCoordinates = if (!stop() && !request.presolveCancellation() &&
        request.presolveBudget?.remaining() != 0L && request.admitsObjectiveCoordinates(preparation)
    ) {
        val coordinateRequest = request.preparationRequest(stop, protectObjective = true)
        val coordinateStop = coordinateRequest.solveCancellation
        val coordinates = prepare(coordinateRequest)
        val hints = request.objectiveCoordinateHints(coordinates, coordinateStop)
        val view = if (hints == null) null else
            ObjectiveCoordinateView.create(preparation, coordinates, hints, stop)
        preparationElapsed = preparationStart.elapsedNow()
        val previous = preparation
        preparation = FinitePipelinePreparation(
            previous.problem, previous.objective, previous.reconstruct,
            previous.presolve?.let { stats ->
                stats.copy(
                    bakeElapsed = stats.bakeElapsed + (coordinates.presolve?.bakeElapsed ?: Duration.ZERO),
                    lpStats = stats.lpStats.mergedWith(coordinates.presolve?.lpStats ?: SolveStats.EMPTY.lp),
                    effort = request.presolveConfig.effortStats(request.presolveBudget, preparationElapsed),
                )
            },
            previous.constructionBakeElapsed,
        ).also {
            it.sourceMapping = previous.sourceMapping
            it.executionPlan = previous.executionPlan
        }
        view
    } else null
    val invocation = request.shape.sourceInvocation
    val linearSource = invocation?.linearSource
    val permitsToleranceOptimum = invocation?.let {
        it.source.semantics.effective.feasibilityTolerance?.isExact == false
    } ?: true
    val sourceCheck: ((Sample) -> Boolean)? = if (linearSource == null) {
        request.toleranceCheck?.let { check ->
            FiniteSourceAcceptance(check, permitsToleranceOptimum && check.permitsToleranceOptimum(),
                (check as? FiniteSourceAcceptance)?.objective)
        }
    } else {
        FiniteSourceAcceptance(
            { sample ->
                linearSource.check(sample, stop) is LinearSourceCheck.Accepted &&
                    request.toleranceCheck?.invoke(sample) != false
            },
            permitsToleranceOptimum =
                permitsToleranceOptimum &&
                    (request.toleranceCheck == null || request.toleranceCheck.permitsToleranceOptimum()),
            objective = if (linearSource.view.source.objective == null) null else { sample ->
                val checked = linearSource.check(sample, stop) as? LinearSourceCheck.Accepted
                checked?.witness?.objective?.let { value ->
                    val scaled = value * linearSource.view.objectiveScale
                    if (linearSource.view.source.objective.sense == ObjectiveSense.MAXIMIZE)
                        scaled.negated() else scaled
                }
            },
        )
    }
    val execution = execute(
        FiniteExecutionRequest(
            problem = preparation.executableProblem(),
            preparedPlan = executionPlan,
            engine = request.engine,
            optimize = request.shape.optimize,
            objective = preparation.objective,
            localSearchObjective = request.shape.localSearchObjective,
            definitionalSweep = request.shape.definitionalSweep,
            searchHints = searchHints,
            cores = request.cores,
            executionOptions = if (linearSource == null) request.executionOptions else
                request.executionOptions.withNumericalCompletion(),
            randomSeed = request.randomSeed,
            defaultArms = request.defaultArms,
            lpConfig = request.lpConfig,
            zeroObjectivePricing = request.zeroObjectivePricing,
            cancellation = stop,
            nodeBudget = request.nodeBudget,
            solveBudgetMillis = request.solveBudgetMillis,
            allSolutions = request.allSolutions ||
                (request.shape.sourceInvocation != null && request.operation == ExecutionOperation.ENUMERATE),
            solutionCap = request.solutionCap,
            deadlineExceeded = request.deadlineExceeded,
            onEvent = request.onEvent,
            onPortfolioEvent = request.onPortfolioEvent,
            onPortfolioFault = request.onPortfolioFault,
            lpSolveContext = lpSolveContext,
            toleranceCheck = if (gridPublication == null) {
                sourceCheck?.let { check ->
                    FiniteSourceAcceptance({ check(preparation.reconstruct(it)) }, check.permitsToleranceOptimum(),
                        (check as? FiniteSourceAcceptance)?.objective?.let { score ->
                            { sample -> score(preparation.reconstruct(sample)) }
                        })
                }
            } else null,
            sourceInvocation = request.shape.sourceInvocation,
            sourceMapping = preparation.sourceMapping,
            sourceControls = request.sourceControls,
            numericalCompanionParams = numericalCompanions,
            objectiveCoordinates = objectiveCoordinates,
        ),
        FiniteExecutionCallbacks(
            onSample = { sample ->
                val lifted = preparation.reconstruct(sample)
                if (gridPublication == null) callbacks.onSample(lifted) else gridPublication.onSample(lifted)
            },
            onImprovement = { improvement ->
                if (gridPublication != null) {
                    gridPublication.onImprovement(preparation.reconstruct(improvement.sample),
                        improvement.workerLabel, improvement.elapsedMs)
                } else callbacks.onImprovement(
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
    return result(
        preparation,
        preparationElapsed,
        execution.reconstructed(preparation.reconstruct, request.shape.finiteProblem, request.shape.linearObjective)
            .toSolveOutcome().withPresolve(preparation.presolve),
        (execution as? FiniteExecutionResult.Completed)?.nativeSourceResult,
        (execution as? FiniteExecutionResult.Completed)?.nativeSourceMinimum,
        (execution as? FiniteExecutionResult.Completed)?.completeSearchAdmitted,
    )
}

private fun FiniteSolveRequest.preparationRequest(
    stop: Cancellation,
    protectObjective: Boolean = false,
): FinitePipelineRequest {
    val preparationStop = if (shape.sourceInvocation == null) presolveCancellation else presolveCancellation or stop
    return FinitePipelineRequest(
        problem = shape.finiteProblem, componentPlan = shape.componentPlan, preparedPlan = shape.preparedPlan,
        engine = engine, objective = shape.linearObjective,
        transformObjective = !protectObjective && shape.localSearchObjective == null,
        presolveConfig = presolveConfig, explicitPresolveConfig = explicitPresolveConfig,
        solutionSetSensitive = solutionSetSensitive ||
            (shape.sourceInvocation != null && operation == ExecutionOperation.ENUMERATE),
        cancellation = preparationStop,
        solveCancellation = if (protectObjective) {
            presolveBudget?.orSpent(preparationStop or stop) ?: (preparationStop or stop)
        } else stop,
        presolveBudget = presolveBudget, zeroObjectivePricing = zeroObjectivePricing, randomSeed = randomSeed,
    )
}

private fun FiniteSolveRequest.objectiveCoordinateHints(
    coordinates: FinitePipelinePreparation,
    stop: Cancellation,
): PreparedSourceHints? {
    val plan = coordinates.executionPlan ?: return null
    if (coordinates.presolve?.infeasible == true || plan.decline != null || stop() ||
        !plan.capabilities.admission(ExecutionStrategy.LOCAL_SEARCH, operation).runnable
    ) return null
    val invocation = shape.sourceInvocation ?: return PreparedSourceHints(
        shape.definitionalSweep, shape.localSearchObjective,
    )
    return invocation.heuristicHints(
        coordinates.problem, coordinates.objective, coordinates.sourceMapping, stop,
        setOf(ExecutionStrategy.LOCAL_SEARCH), plan.capabilities,
    )
}

private fun FiniteSolveRequest.admitsObjectiveCoordinates(preparation: FinitePipelinePreparation): Boolean {
    if (!shape.optimize || engine != FiniteEngine.MIXED || cores != 1 || explicitPresolveConfig ||
        shape.localSearchObjective != null || executionOptions.explicitArms || preparation.problem.numRealVars != 0
    ) return false
    // Without Element alternatives the primary and full neighborhoods offer the same repairs.
    if (preparation.problem.factors.none { it is Diffn } || preparation.problem.factors.any { it is Element }) {
        return false
    }
    val sourceWeights = shape.linearObjective?.intCoefficientValues ?: return false
    val weights = preparation.objective?.intCoefficientValues ?: return false
    val substituted = preparation.sourceMapping?.stages?.any { stage ->
        stage.rebuild.steps.any { step ->
            step is RebuildStep.AffineValue && sourceWeights.getOrElse(step.variable) { 0L } != 0L &&
                weights.getOrElse(step.variable) { 0L } == 0L
        }
    } == true
    if (!substituted) return false
    val options = executionOptions.reader()
    val recipes = resolveLocalSearchRecipes(options)
    return recipes.pool == null && !recipes.dryRunSolver && options[ExecutionOption.backtrackArmsByLabel] == null
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

private fun executeSourceFinite(
    request: FiniteExecutionRequest,
    callbacks: FiniteExecutionCallbacks,
): FiniteExecutionResult {
    require(request.sourceInvocation != null && request.toleranceCheck == null)
    if (request.optimize) return executeSourceMinimum(request, callbacks)
    val start = TimeSource.Monotonic.markNow()
    val solved = when (val controls = requireNotNull(request.sourceControls)) {
        is SourceFiniteControls.Backtrack -> BacktrackSolver(request.problem, request.lpSolveContext).solve(
            controls.params.copy(cancellation = controls.cancellation or request.cancellation),
        )
        is SourceFiniteControls.LocalSearch -> LocalSearchSolver(
            request.problem,
            completion = if (request.problem.numRealVars > 0) LeafRealCompletion(request.problem, null) else null,
        ).solve(controls.params.copy(cancellation = controls.cancellation or request.cancellation))
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
            objective, controls.params.copy(cancellation = controls.cancellation or request.cancellation),
        )
        is SourceFiniteControls.LocalSearch -> LocalSearchSolver(
            request.problem,
            completion = if (request.problem.numRealVars > 0) LeafRealCompletion(request.problem, objective) else null,
        ).minimize(objective, controls.params.copy(cancellation = controls.cancellation or request.cancellation))
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

    val params = plan.params.copy(toleranceCheck = request.toleranceCheck)
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
    val proofShare = request.executionOptions.reader()[ExecutionOption.portfolioProofShare]
    if (proofShare != null && (!proofShare.isFinite() || proofShare !in 0.5..1.0)) {
        pipelineConfigError("portfolio-proof-share must be finite and in [0.5, 1]")
    }
    if (proofShare != null && (!request.optimize || request.cores != 1 ||
            request.engine !in setOf(FiniteEngine.MIXED, FiniteEngine.BACKTRACK))) {
        pipelineConfigError("portfolio-proof-share requires curated single-core finite optimization")
    }
    val plan = FinitePipeline.planPortfolio(
        PortfolioPlanRequest(
            engine = request.engine,
            optimize = request.optimize,
            cores = request.cores,
            executionOptions = request.executionOptions.without("portfolio-proof-share"),
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
    if (proofShare != null && (plan !is PortfolioPlan.Execute || plan.scenario.explicitArms ||
            plan.scenario.btPool != null || plan.scenario.lsPool != null || plan.scenario.btEdit != null ||
            plan.scenario.annotationArm != null)) {
        pipelineConfigError("portfolio-proof-share requires a curated recipe pool")
    }
    when (plan) {
        is PortfolioPlan.LocalSearchDryRun -> return localSearchDryRun(plan.pool)

        is PortfolioPlan.BacktrackDryRun ->
            return backtrackDryRun(request.problem, plan.pool, plan.kind, plan.zeroObjectivePricing)

        is PortfolioPlan.Execute -> Unit
    }
    val scenario = clausalDefaultScenario(
        plan.scenario,
        ProblemProfile.of(request.problem, request.optimize),
        request.defaultArms,
        explicitArms = request.executionOptions.explicitArms,
    )
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
    )
    val start = TimeSource.Monotonic.markNow()
    executor.use {
        val completed = if (request.optimize) {
            var streamed = 0L
            val result = it.minimize(request.cancellation) { improvement ->
                callbacks.onImprovement(improvement.toFiniteImprovement())
                streamed++
            }
            executePortfolioOptimize(result, streamed, !request.engine.pureLocalSearch, callbacks, start)
        } else {
            executePortfolioSatisfy(it.solve(request.cancellation), !request.engine.pureLocalSearch, callbacks, start)
        }
        return completed.withCompleteSearch(it.completeSearchAdmitted)
    }
}

internal fun clausalDefaultScenario(
    scenario: PortfolioScenario,
    profile: ProblemProfile,
    defaultArms: Int,
    explicitArms: Boolean,
): PortfolioScenario {
    if (explicitArms || defaultArms != autoArms(1) || scenario.arms != defaultArms) return scenario
    if (scenario.cores != 1 || scenario.kind != Kind.CSP || scenario.engine != EngineMix.MIXED) return scenario
    if (profile.problemClass != ProblemClass.Sat || scenario.btPool != null || scenario.lsPool != null ||
        scenario.btEdit != null || scenario.annotationArm != null
    ) {
        return scenario
    }
    return scenario.copy(arms = 2)
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
