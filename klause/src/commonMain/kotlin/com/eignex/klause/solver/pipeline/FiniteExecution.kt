package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.PooledGuidanceObservation
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpQueryObservation
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.lp.relaxation.LpSourcePreparation
import com.eignex.klause.lp.relaxation.provesUnbounded
import com.eignex.klause.lp.relaxation.provesUnboundedWithinTolerance
import com.eignex.klause.portfolio.ArmFault
import com.eignex.klause.portfolio.PoolClauseNeighborhoodObserver
import com.eignex.klause.presolve.ObjectiveAdjustment
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.effortStats
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.encodingPreparationScope
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.OptimalityGap
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.withPresolve
import com.eignex.klause.solver.validation.FiniteSourceAcceptance
import com.eignex.klause.solver.validation.LinearSourceCheck
import com.eignex.klause.solver.validation.capturedRealAcceptance
import com.eignex.klause.solver.validation.permitsToleranceOptimum
import com.eignex.klause.solver.validation.toleranceSearch
import com.eignex.klause.theory.SourceToleranceSearch
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.includingStop
import com.eignex.util.numbers.BigFraction
import kotlin.time.Duration
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
    /** ExecutionControl shared by every engine worker. */
    val cancellation: ExecutionControl,
    /** ExecutionControl applying only while the model is prepared. */
    val presolveCancellation: ExecutionControl = cancellation,
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

    /**
     * Finite LP dependencies. A source that accepts points within a positive feasibility tolerance accepts
     * LP deductions within tolerances too; every other source, and `--exact`, which zeroes the tolerance,
     * certifies them exactly.
     */
    internal val lpSolveContext: LpSolveContext get() = LpSolveContext.finite(
        numerical = shape.sourceInvocation?.source?.semantics?.effective?.feasibilityTolerance?.isExact == false &&
            shape.sourceInvocation?.linearSource?.acceptedModelExact != true,
    )

    /** Captured frontend `key=value` parameters; empty for directly typed options. */
    val engineParams: List<String> get() = executionOptions.originalPairs.toList()

    /** Build the request from repeatable frontend `key=value` parameters. */
    constructor(
        shape: FiniteSolveShape,
        engine: FiniteEngine,
        presolveConfig: PresolveConfig,
        explicitPresolveConfig: Boolean,
        solutionSetSensitive: Boolean,
        cancellation: ExecutionControl,
        presolveCancellation: ExecutionControl = cancellation,
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
    /** Terminal result in source terms. */
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
    internal var nativeObjectiveBound: FiniteObjectiveBound? = null

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
        /**
         * On [FiniteSolveVerdict.OPTIMAL] within the optimality gap, the proved source-model bound below the objective
         * of [bestSample] in minimization direction; null for an exact optimum.
         */
        val gapBound: BigFraction? = null,
    ) : FiniteSolveOutcome() {
        private var recordedReason: TerminationReason? = null

        /** Recorded cause of an incomplete result; absent for a definitive verdict. */
        val terminationReason: TerminationReason? get() = recordedReason

        internal fun withTerminationReason(reason: TerminationReason?): Completed = apply { recordedReason = reason }
    }
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

/** Prepare the canonical finite model and optional heuristic view, then execute the selected engine route. */
fun FinitePipeline.solve(request: FiniteSolveRequest, callbacks: FiniteSolveCallbacks): FiniteSolveResult =
    solve(request, callbacks, request.lpSolveContext)

internal fun FinitePipeline.solve(request: FiniteSolveRequest, callbacks: FiniteSolveCallbacks,
    numericalCompanions: TheoryParams): FiniteSolveResult =
    solve(request, callbacks, request.lpSolveContext, FiniteCompanionPolicy(numericalCompanions))

/** Execute the fixed finite route with per-invocation LP dependencies. */
internal fun FinitePipeline.solve(
    request: FiniteSolveRequest,
    callbacks: FiniteSolveCallbacks,
    lpSolveContext: LpSolveContext,
    numericalCompanions: FiniteCompanionPolicy? = null,
    onPortfolioLpQuery: ((String, Double?, LpQueryObservation) -> Unit)? = null,
    onPortfolioGuidance: ((String, PooledGuidanceObservation) -> Unit)? = null,
    onPortfolioJointRepair: ((String, Long, JointRepairObservation) -> Unit)? = null,
    onPortfolioSession: ((String, PropagationSession) -> Unit)? = null,
    onPortfolioNeighborhood: PoolClauseNeighborhoodObserver? = null,
): FiniteSolveResult {
    require(request.engine == FiniteEngine.FIXED || lpSolveContext == request.lpSolveContext) {
        "custom LP dependencies require the fixed finite route"
    }
    val containingStop = request.shape.sourceInvocation?.let {
        val owner = if (request.cancellation.accounting != null) request.cancellation else it.cancellation
        request.cancellation.withAccounting(owner).includingStop(it.cancellation).encodingPreparationScope()
    } ?: request.cancellation
    val validation = request.shape.sourceInvocation?.source?.takeIf { it.witnessValidators.isNotEmpty() }?.let {
        SourceWitnessPublication(it, containingStop)
    }
    val publicationCallbacks = if (validation == null) callbacks else FiniteSolveCallbacks(
        { sample -> if (validation.accept(sample)) callbacks.onSample(sample) },
        { improvement -> if (validation.accept(improvement.sample)) callbacks.onImprovement(improvement) },
    )
    val stop = validation?.let { containingStop or it.stop } ?: containingStop
    fun result(
        preparation: FinitePipelinePreparation,
        elapsed: Duration,
        outcome: FiniteSolveOutcome,
        nativeSourceResult: SolveResult? = null,
        nativeSourceMinimum: MinimizeResult? = null,
        completeSearchAdmitted: Boolean? = null,
        nativeObjectiveBound: FiniteObjectiveBound? = null,
    ): FiniteSolveResult =
        FiniteSolveResult(preparation, elapsed, outcome, nativeSourceResult, nativeSourceMinimum)
            .also {
                it.bindCompleteSearchAdmission(completeSearchAdmitted)
                it.nativeObjectiveBound = nativeObjectiveBound
            }
            .let { validation?.qualify(it) ?: it }
    if (request.shape.sourceInvocation != null && stop()) {
        return result(
            FinitePipelinePreparation(request.shape.problem, request.shape.linearObjective, { it }, null),
            Duration.ZERO,
            FiniteSolveOutcome.Completed(FiniteSolveVerdict.UNKNOWN, SolveStats.EMPTY, 0L, null, 0L)
                .withTerminationReason(request.interruptedReason()),
        )
    }
    val sourcePlan = request.shape.preparedPlan
    if (request.shape.sourceInvocation != null && !request.prepareOnly &&
        sourcePlan.decline != PreparedPlanDecline.RESOURCE &&
        !sourcePlan.capabilities.admission(request.engine, request.operation).runnable
    ) {
        return result(
            FinitePipelinePreparation(request.shape.problem, request.shape.linearObjective, { it }, null),
            Duration.ZERO,
            FiniteSolveOutcome.Completed(FiniteSolveVerdict.UNKNOWN, SolveStats.EMPTY, 0L, null, 0L)
                .withTerminationReason(TerminationReason.Unsupported),
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
            FiniteSolveOutcome.Completed(FiniteSolveVerdict.UNKNOWN, SolveStats.EMPTY, 0L, null, 0L)
                .withTerminationReason(request.interruptedReason()),
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
        it.source.semantics.effective.feasibilityTolerance?.isExact == false &&
            it.linearSource?.acceptedModelExact != true
    } ?: true
    val sourceCheck: ((Sample) -> Boolean)? = if (linearSource == null) {
        (request.toleranceCheck ?: invocation?.let {
            capturedRealAcceptance(it.source.problem, stop)
        })?.let { check ->
            val source = invocation?.source?.problem
            val policy = source?.semantics?.effective?.feasibilityTolerance
            FiniteSourceAcceptance(check, permitsToleranceOptimum && check.permitsToleranceOptimum(),
                (check as? FiniteSourceAcceptance)?.objective,
                (check as? FiniteSourceAcceptance)?.search ?: policy?.takeIf {
                    !it.isExact && request.toleranceCheck == null && check is FiniteSourceAcceptance
                }?.let { SourceToleranceSearch(checkNotNull(source), it) })
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
            // A source that accepts points within its slack completes numerically in every worker; exact
            // completion in the incomplete workers would refuse the points the complete ones publish.
            executionOptions = if (linearSource == null &&
                (invocation == null || !sourceCheck.permitsToleranceOptimum())
            ) request.executionOptions else request.executionOptions.withNumericalCompletion(),
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
            onPortfolioLpQuery = onPortfolioLpQuery,
            onPortfolioGuidance = onPortfolioGuidance,
            onPortfolioJointRepair = onPortfolioJointRepair,
            onPortfolioSession = onPortfolioSession,
            onPortfolioNeighborhood = onPortfolioNeighborhood,
            toleranceCheck = sourceCheck?.let { check ->
                FiniteSourceAcceptance({ check(preparation.reconstruct(it)) }, check.permitsToleranceOptimum(),
                    (check as? FiniteSourceAcceptance)?.objective?.let { score ->
                        { sample -> score(preparation.reconstruct(sample)) }
                    }, check.toleranceSearch()?.reconstructedBy(preparation.reconstruct))
            },
            sourceInvocation = request.shape.sourceInvocation,
            sourceMapping = preparation.sourceMapping,
            sourceControls = request.sourceControls,
            numericalCompanionParams = numericalCompanions,
            objectiveCoordinates = objectiveCoordinates,
            optimalityGap = if (request.shape.optimize) {
                invocation?.let { sourceOptimalityGap(it, preparation) }
            } else null,
        ),
        FiniteExecutionCallbacks(
            onSample = { sample ->
                val lifted = preparation.reconstruct(sample)
                publicationCallbacks.onSample(lifted)
            },
            onImprovement = { improvement ->
                publicationCallbacks.onImprovement(
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
            .toSolveOutcome(preparation.sourceMapping?.objectiveAdjustment).withPresolve(preparation.presolve),
        (execution as? FiniteExecutionResult.Completed)?.nativeSourceResult,
        (execution as? FiniteExecutionResult.Completed)?.nativeSourceMinimum,
        (execution as? FiniteExecutionResult.Completed)?.completeSearchAdmitted,
        (execution as? FiniteExecutionResult.Completed)?.nativeObjectiveBound,
    )
}

// The source's optimality gap over the prepared objective: a working value reads in source units through the presolve
// adjustment and the linear source's objective scale. Null when the source closes bounds exactly or presolve does
// not preserve the objective's source value, and without a discrete coordinate, where the one leaf's relaxation
// decides the optimum and leaves no node for the gap to bound.
private fun sourceOptimalityGap(
    invocation: PreparedSourceInvocation,
    preparation: FinitePipelinePreparation,
): OptimalityGap? {
    val tolerance = invocation.source.semantics.effective.optimalityTolerance?.takeUnless { it.isExact } ?: return null
    val objective = preparation.objective ?: return null
    if (preparation.problem.numIntVars == 0 && preparation.problem.numBoolVars == 0) return null
    val mapping = preparation.sourceMapping
    if (mapping != null && (!mapping.guarantees.objective || !mapping.guarantees.reconstructability)) return null
    val adjustment = mapping?.objectiveAdjustment ?: ObjectiveAdjustment()
    val perSourceUnit = (invocation.linearSource?.view?.objectiveScale ?: BigFraction.ONE).reciprocal()
    return OptimalityGap(tolerance, adjustment.scale * perSourceUnit, adjustment.offset * perSourceUnit,
        integral = objective.realCoefficientValues.all { it == 0.0 })
}

private fun FiniteSolveRequest.preparationRequest(
    stop: ExecutionControl,
    protectObjective: Boolean = false,
): FinitePipelineRequest {
    val preparationStop =
        if (shape.sourceInvocation == null) presolveCancellation else presolveCancellation.includingStop( stop)
    val sourcePreparation = (sourceControls as? SourceFiniteControls.Backtrack)?.params?.let {
        it.lpConfig?.sourcePreparation ?: it.lpPlan.sourcePreparation
    } ?: lpConfig.sourcePreparation ?: LpSourcePreparation.ADMITTED
    return FinitePipelineRequest(
        problem = shape.finiteProblem, componentPlan = shape.componentPlan, preparedPlan = shape.preparedPlan,
        engine = engine, objective = shape.linearObjective,
        transformObjective = !protectObjective && shape.localSearchObjective == null,
        presolveConfig = presolveConfig, explicitPresolveConfig = explicitPresolveConfig,
        solutionSetSensitive = solutionSetSensitive ||
            (shape.sourceInvocation != null && operation == ExecutionOperation.ENUMERATE),
        cancellation = preparationStop,
        solveCancellation = if (protectObjective) {
            presolveBudget?.orSpent(preparationStop.includingStop( stop)) ?: preparationStop.includingStop( stop)
        } else stop,
        presolveBudget = presolveBudget, zeroObjectivePricing = zeroObjectivePricing, randomSeed = randomSeed,
        sourcePreparation = sourcePreparation,
        numericalLp = lpSolveContext.numericalDeductions,
    )
}

private fun FiniteSolveRequest.objectiveCoordinateHints(
    coordinates: FinitePipelinePreparation,
    stop: ExecutionControl,
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

// A working gap bound reads in source-model units through the presolve [adjustment].
private fun FiniteExecutionResult.toSolveOutcome(adjustment: ObjectiveAdjustment? = null): FiniteSolveOutcome =
    when (this) {
        is FiniteExecutionResult.DryRun -> FiniteSolveOutcome.DryRun(heading, lines)

        is FiniteExecutionResult.Completed -> FiniteSolveOutcome.Completed(
            FiniteSolveVerdict.valueOf(verdict.name),
            stats,
            solutions,
            bestSample,
            elapsedMs,
            unboundedDirection,
            gapBound?.let(BigFraction::ofDouble)?.let { adjustment?.sourceValue(it) ?: it },
        ).withTerminationReason(terminationReason)
    }

private fun FiniteSolveOutcome.withPresolve(presolve: PresolveStats?): FiniteSolveOutcome = when (this) {
    is FiniteSolveOutcome.Completed -> FiniteSolveOutcome.Completed(
        verdict,
        stats.withPresolve(presolve),
        solutions,
        bestSample,
        elapsedMs,
        unboundedDirection,
        gapBound,
    ).withTerminationReason(terminationReason)

    else -> this
}

/**
 * This result over the source [problem]. An unbounded verdict stands only if its witness and ray, lifted through
 * [reconstruct], still prove [objective] unbounded over [problem], exactly or within its declared tolerance; otherwise
 * the witness remains a feasible assignment with no optimality proof.
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
            liftedDirection(reconstruct, point)?.takeIf { direction ->
                problem.provesUnbounded(objective, point, direction) ||
                    problem.semantics.effective.feasibilityTolerance?.let { policy ->
                        problem.provesUnboundedWithinTolerance(objective, point, direction, policy)
                    } == true
            }
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
            terminationReason = terminationReason,
            gapBound = gapBound,
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

private fun FiniteSolveRequest.interruptedReason(): TerminationReason = when {
    deadlineExceeded() || cancellation.deadline()?.hasPassedNow() == true -> TerminationReason.Timeout
    nodeBudget?.exhausted() == true -> TerminationReason.BudgetExhausted
    cancellation() -> TerminationReason.Cancelled
    else -> TerminationReason.BudgetExhausted
}
