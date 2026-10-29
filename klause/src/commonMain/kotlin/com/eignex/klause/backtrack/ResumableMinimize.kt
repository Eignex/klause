package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.lp.LpHints
import com.eignex.klause.backtrack.lp.lbTreeSearch
import com.eignex.klause.backtrack.lp.lpBranchPick
import com.eignex.klause.backtrack.lp.lpFeasibilityPump
import com.eignex.klause.backtrack.lp.lpRoundingProbe
import com.eignex.klause.backtrack.selector.IndomainBest
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.harvestRootRelaxation
import com.eignex.klause.lp.bounding.rootLpRelaxationBound
import com.eignex.klause.lp.bounding.shaveObjectiveLb
import com.eignex.klause.lp.bounding.shaveVariableBounds
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveCursor
import com.eignex.klause.lp.engine.LpSolveStep
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.relaxation.LeafRealResult
import com.eignex.klause.lp.relaxation.LeafRealSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.LearnedConstraint
import com.eignex.klause.propagation.CpBranching
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.baked
import com.eignex.klause.propagation.conditionedRoot
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.IncumbentSubscription
import com.eignex.klause.solver.incumbent.Publication
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.lifetimeCancellation
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.SingleIntObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.UnsatCore
import com.eignex.klause.solver.result.UnsoundnessException
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.FiniteFactorEnforcer
import com.eignex.klause.solver.search.PreparingSearchComponent
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchDecisionBudget
import com.eignex.klause.solver.search.SearchInvocationResources
import com.eignex.klause.solver.search.SearchLearnedConflict
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchModelContinuation
import com.eignex.klause.solver.search.SearchModelDisposition
import com.eignex.klause.solver.search.SearchModelPolicy
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchRun
import com.eignex.klause.solver.search.SearchRunDisposition
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchRunLifecycle
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.SearchTraversalPolicy
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.solver.search.completedSearchSample
import com.eignex.klause.solver.validation.composedFixpoint
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.solver.withSourceSemantics
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.cancelledWhen
import com.eignex.klause.util.workScheduled
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** One step of [ResumableMinimize.runUntilEvent]: a new incumbent, the terminal verdict, or a
 *  slice-boundary pause (only in pausable mode). */
internal sealed interface StepEvent {
    data class Incumbent(val result: MinimizeResult.WithSample) : StepEvent
    data class Terminal(val result: MinimizeResult) : StepEvent
    data object Paused : StepEvent
}

/**
 * Cap on one LP proposal's composed verification, composed with the run's own token rather than
 * standing in for it — verification must stop at a cancellation or a slice boundary like everything
 * else the engine does. This bounds the case that token cannot: a solve with budget left to spare
 * whose fixpoint over a fully-fixed assignment runs away inside one propagator.
 */
private val LP_VERIFY_BUDGET = 100.milliseconds

/**
 * The optimize driver: branch-and-bound minimisation over the shared [SearchRun]. A thin wrapper that
 * owns the incumbent, the LP-relaxation family (built once; persists across slices = rolling warm
 * starts), the objective-bound propagation, and the stats sink lifecycle; the DFS orchestration itself
 * lives in [SearchRun], driven through typed model, node, and lifecycle policies. [runUntilEvent] advances to the next
 * reportable event (a new incumbent, the terminal verdict, or — in pausable mode — a slice-boundary
 * pause) and returns; the engine retains all state so the next call resumes.
 *
 * Two callers drive it, differing only in [pausable]:
 *  - [BacktrackSolver.improvements] streams it lazily — one [StepEvent.Incumbent] yielded per call, then the terminal;
 *    a fired cancellation is a hard stop.
 *  - [runSlice] runs it one time slice at a time for [com.eignex.klause.portfolio.Portfolio]:
 *    a fired slice deadline pauses ([StepEvent.Paused]); a later call resumes mid-tree so the
 *    arm never cold-restarts.
 */
@Suppress("TooGenericExceptionCaught") // ownership boundaries preserve arbitrary primary and cleanup failures
internal class ResumableMinimize(
    // Carries the immutable problem and shared search helpers; the solver holds no per-run state.
    private val solver: BacktrackSolver,
    private val objective: LinearObjective,
    params0: BacktrackParams,
    // true: a fired cancellation is a slice boundary the caller may resume past ([runSlice]); false:
    // it is a hard terminal stop ([BacktrackSolver.improvements] one-shot). The single difference between the two
    // ways this one engine is driven.
    private val pausable: Boolean = true,
    // Repair owns the handle across fragment terminals and explicitly closes it after the final fragment.
    private val rebindable: Boolean = false,
    private val exactObjectiveBoundSupplier: (() -> BigFraction?)? = null,
    // A containing search charges private work and owns its overspend debt.
    private val ownsWorkDebt: Boolean = true,
    initialCandidate: Sample? = null,
    private val problem: BakedProblem = solver.problem,
    preparedSession: PropagationSession? = null,
    preparationBudget: SliceBudget? = null,
    private val sink: SolveStatsSink = SolveStatsSink(backend = "backtrack"),
    private val deferredInstallation: Boolean = false,
) : ResumableSearch, ReseedGuard {
    private val hasPreparation = preparationBudget != null
    override val canReseed: Boolean
        get() = !preparationPending && clausePrimal?.isDone != false && !rootRoundingPending &&
            pendingRealLeaf == null && pendingRealResult == null
    override val canContinuePreparation: Boolean get() = clausePrimal?.isDone == false
    private var initialCandidate = initialCandidate
    private var replaced = false

    // Unproved leaf coverage cannot become infeasibility or optimality, including under shared cutoffs.
    private var sawIndeterminateLeaf = false

    // The LP-relaxation family is resolved from [BacktrackParams.lpConfig] inside [LpEngine]
    // (the single intent→plan home), so this carries the caller's params verbatim — only the slice
    // cancellation and the Hamming/recent-window knobs are fixed here; the per-slice deadline is re-armed in
    // runSlice.
    // One-shot calls retain their invocation token. Resumed calls replace it while keeping a bound lifetime scope.
    private val baseCancellation: Cancellation = params0.cancellation
    private var params: BacktrackParams = params0.copy(
        cancellation = cancelledWhen(::runDeadline, { params0.cancellation.workScheduled() }) { sliceCancelled() },
        minHammingDistance = 0,
        recentWindow = 0,
    )

    // Slice control without a coroutine: a re-armable deadline plus the current global token.
    private var globalToken: Cancellation = params0.cancellation

    // Wall-clock anchor for sizing the LP sub-budgets against [BacktrackParams.solveBudgetMillis] on the
    // non-pausable one-shot path, where no slice deadline is armed. Captured at construction,
    // which is the solve start for that path.
    private val startMark = TimeSource.Monotonic.markNow()

    // The allowance includes LP and propagation work so costly nodes consume proportionally more of a slice.
    private var proposalPropagationWork = 0L
    private var clausePrimal: MonotoneClausePrimal? = null
    private var clausePrimalStarted = false
    private var clausePrimalCandidate: Sample? = null
    private val slice = SliceBudget(
        { sink.search.searchWork + (clausePrimal?.work ?: 0L) +
            (if (::lpEngine.isInitialized) lpEngine.primalSearchWork else 0L) },
        { (if (::lpEngine.isInitialized) lpEngine.totalSolveWork() else 0L) + sink.lp.standaloneWork },
        {
            (if (::cp.isInitialized) session.work else preparedSession?.work ?: 0L) +
                (if (::lpEngine.isInitialized) lpEngine.primalPropagationWork else 0L) + proposalPropagationWork
        },
        preparationBudget,
        ownsWorkDebt = ownsWorkDebt,
    )

    private fun sliceCancelled(): Boolean = solveCancelled() || (pausable && sliceExpired())

    /**
     * What ends the whole run, as opposed to [sliceCancelled]'s slice boundary. Root propagation and
     * deductions at retained levels use this token. Shared decision assertions add the search context's
     * allowance: a cut assertion retracts its fresh level before a later slice retries it.
     */
    private fun solveCancelled(): Boolean = if (pausable) globalToken() else baseCancellation()

    // solveCancelled as a token that also states the run's deadline, so a phase it is handed can budget a share of
    // the time left (Cancellation.shorten) rather than read a bare predicate as having no time limit at all.
    private fun runEndToken(): Cancellation = cancelledWhen(::runDeadline, { baseCancellation.workScheduled() }) {
        solveCancelled()
    }

    private fun runDeadline(): ComparableTimeMark? = (if (pausable) globalToken else baseCancellation).deadline()

    /**
     * The node budget when one is armed, and *only* the node budget.
     *
     * Leaving the per-slice clock as an outer bound defeats the purpose: whenever it binds first the
     * pause point is a function of machine speed again, and the run stops being comparable to itself.
     * The whole solve is still bounded — [globalToken] carries the real deadline and is checked
     * alongside this — so dropping the per-slice deadline cannot overrun anything.
     */
    private fun sliceExpired(): Boolean = slice.expired()

    // The run's single verified incumbent. Every producer offers into it and none of them writes the
    // best directly, so one admission rule and one strict-improvement test decide what stands — and the
    // version the exchange stamps tells the offering producer whether *it* installed the improvement.
    // Replaced wholesale by [rebind], whose fragment incumbent starts empty again.
    private var incumbents = minimizingSampleExchange(problem, params.toleranceCheck)
    private val bestObj: Double get() = incumbents.current()?.objective?.pruningBound ?: Double.POSITIVE_INFINITY
    private var singleObj: SingleIntObjective? = null
    private var discreteObjective = true
    private var objVarBest: Long? = null
    private val externalShared = params.objectiveBoundSupplier != null
    private var lastExternalCutoff = Double.POSITIVE_INFINITY
    private var lastObjBoundAsserted: Long? = null
    private var lastBoolCutoffValue: BigFraction? = null
    private var lastOpenCutoff: Long? = null

    private fun externalCutoff(): Double {
        val bound = params.objectiveBoundSupplier?.invoke() ?: Double.POSITIVE_INFINITY
        if (rebindable) {
            require(!bound.isNaN() && bound <= lastExternalCutoff) {
                "repair objective cutoff must be non-increasing"
            }
            lastExternalCutoff = bound
        }
        return bound
    }

    private fun beatsCutoff(exact: BigFraction, cutoff: Double): Boolean {
        if (cutoff == Double.NEGATIVE_INFINITY) return false
        if (cutoff == Double.POSITIVE_INFINITY) return true
        return exact < checkNotNull(BigFraction.ofDouble(cutoff))
    }

    private fun beatsExternalCutoff(exact: BigFraction?, reported: Double): Boolean {
        val cutoff = externalCutoff()
        val exactCutoff = exactObjectiveBoundSupplier?.invoke()
        return when {
            exactCutoff != null -> exact != null && exact < exactCutoff
            exact != null -> beatsCutoff(exact, cutoff)
            else -> reported < cutoff
        }
    }

    private val resources = SearchInvocationResources().also { owner ->
        owner.own(AutoCloseable { sink.stop() })
    }
    // Built once; persists across slices, so warm starts roll forward. Always constructed so the
    // always-on linear lower bound runs; its internal bounds are null/empty when their feature flag
    // is off.
    private lateinit var lpEngine: LpEngine
    private var leafSolver: LeafRealSolver? = null
    private var pendingRealLeaf: LpSolveCursor<LeafRealResult>? = null
    private var pendingRealResult: LeafRealResult? = null
    private val rootLpDutyCycle = RootLpDutyCycle()

    private fun completeLeaf(
        sample: Sample,
        cancellation: Cancellation,
        workLimit: Long = 0L,
    ): LeafRealResult {
        val result = realLeafOwner().solve(sample, cancellation, completionToleranceCheck(), workLimit)
        return checkedLeafResult(sample, result)
    }

    private fun completionToleranceCheck(): ((Sample) -> Boolean)? =
        if (exactObjectiveBoundSupplier?.invoke() != null) null else params.toleranceCheck

    private fun realLeafOwner(): LeafRealSolver = leafSolver ?: LeafRealSolver(
        problem, objective, params.lpPlan.componentSplit, sink.lp, solver.lpSolveContext,
        LpPricingOptions(params.zeroObjectivePricing, params.randomSeed ?: 0L),
    ).also {
        resources.own(it)
        resources.own(AutoCloseable {
            val pending = pendingRealLeaf
            pendingRealLeaf = null
            pendingRealResult = null
            pending?.close()
        })
        leafSolver = it
    }

    private fun advanceRealLeaf(sample: Sample): LeafRealResult? {
        pendingRealResult?.let { return it }
        val cursor = pendingRealLeaf ?: realLeafOwner().openSolve(sample, runEndToken(), completionToleranceCheck())
            .also { pendingRealLeaf = it }
        val allowance = slice.remainingLpWork()
        if (allowance == 0L) return null
        return when (val step = cursor.advance(allowance ?: 0L, { !slice.workBounded && sliceExpired() })) {
            LpSolveStep.Paused -> null
            is LpSolveStep.Completed -> {
                pendingRealLeaf = null
                cursor.close()
                checkedLeafResult(sample, step.value).also { pendingRealResult = it }
            }
        }
    }

    private fun checkedLeafResult(sample: Sample, result: LeafRealResult): LeafRealResult {
        if (problem.factorProjections?.requiresRealSourceCheck != true || result.verdict !in setOf(
                LpVerdict.FEASIBLE, LpVerdict.ATTAINED_OPTIMUM, LpVerdict.TOLERANCE_OPTIMUM, LpVerdict.UNBOUNDED,
            )
        ) return result
        val completed = sample.copy(reals = result.reals, exactReals = result.exactReals)
        val tolerated = result.verdict == LpVerdict.TOLERANCE_OPTIMUM &&
            completionToleranceCheck()?.invoke(completed) == true
        return if (tolerated || verifyRealCoordinates(problem, completed) is Verification.Accepted) result
        else LeafRealResult(LpVerdict.INDETERMINATE, doubleArrayOf())
    }

    // LP-guided branching hints (search-only): owned here so the engine depends only on the record sink.
    // The engine records each node's LP solution into it; the descent reads it for variable/value order.
    private val lpHints: LpHints? =
        if (params.lpPlan.branching) {
            LpHints(problem.numIntVars, problem.numBoolVars, params.lpDirectionalGains,
                sink.lp::observeDirectionalBranch, charge = { lpEngine.noteNodeOverhead(it) })
        } else null

    private lateinit var cp: CpSearchComponent
    private val session: PropagationSession get() = cp.session
    private val restart = RestartSchedule.from(params)
    private var decisionLimit = minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE)
    private lateinit var brancher: BacktrackBrancher
    private lateinit var searchSession: SearchSession
    private lateinit var run: SearchRun
    private var rootExhausted: UnsatCore? = null
    private var rootIsExhausted = false
    private var firstRun = true
    private var rootRoundingPending = false
    private var rootRoundingAttempted = false
    private var treeBudgetExhausted = false
    private val inprocessing = Inprocessing.from(params, sink.search)
    private val learnedMemory = LearnedMemoryMaintenance()
    private val pooledIncumbents = params.pooledIncumbents?.let { IncumbentSubscription(it) }
    private val traversal by lazy(LazyThreadSafetyMode.NONE) { OptimizationTraversalPolicy() }

    // Built lazily so it binds the engine's session (created inside the engine's constructor). Its first
    // use is the first-run boundExchange work, well after construction.
    private val boundExchange by lazy { PortfolioBoundExchange(problem, session, params, singleObj) }

    /** Terminal verdict once the search completes; null while still pending. */
    private var done: MinimizeResult? = null
    private var pendingIncumbent: MinimizeResult.WithSample? = null

    // A leaf whose reals descend without limit, as its completion and checked ray; it ends the run.
    private var unboundedLeaf: Pair<Sample, List<BigFraction>>? = null
    private var closed = false
    override val isDone: Boolean get() = done != null

    /** Live counters; [SolveStatsSink.snapshot] reads elapsed time without needing the sink stopped, so
     *  this is meaningful mid-search as well as after one. */
    override val stats: SolveStats get() = sink.snapshot()

    override val work: Long get() = slice.spent()

    override var initialWork: Long = 0L
        private set

    private var installation: Iterator<Unit>? = sequence {
        var boolWeighted = false
        var intWeighted = false
        var single = -1
        var multiple = false
        for (v in objective.boolWeightValues.indices) {
            boolWeighted = boolWeighted || objective.boolWeightValues[v] != 0L
            if ((v + 1) % 256 == 0) yield(Unit)
        }
        for (v in objective.intCoefficientValues.indices) {
            if (objective.intCoefficientValues[v] != 0L) {
                intWeighted = true
                if (single >= 0) multiple = true else single = v
            }
            if ((v + 1) % 256 == 0) yield(Unit)
        }
        for (v in objective.realCoefficientValues.indices) {
            discreteObjective = discreteObjective && objective.realCoefficientValues[v] == 0.0
            if ((v + 1) % 256 == 0) yield(Unit)
        }
        if (!boolWeighted && discreteObjective && single >= 0 && !multiple) {
            singleObj = SingleIntObjective(single, objective.intCoefficientValues[single] > 0L)
        }
        if (params.objectiveGuidedValues && (boolWeighted || intWeighted)) {
            params = params.copy(valueSelector = SolutionGuided(IndomainBest(objective)))
        }
        cp = CpSearchComponent(
            (preparedSession ?: PropagationSession(
                solver.propagationProjection(params.nativeSat ?: true, problem),
                cancelledWhen(::runDeadline, { baseCancellation.workScheduled() }) { solveCancelled() },
                params.propagationCancelFloor,
                nativeSat = params.nativeSat ?: true,
                pbLearning = params.pbLearning ?: (problem.numIntVars == 0),
                attribution = params.propagationAttribution,
                historicalTables = params.historicalTableReasons,
                costAware = params.costAwarePropagation,
                cumulativeUnarySubsets = problem.numRealVars == 0 && params.useCumulativeUnarySubsets,
            )).also {
                if (params.integerRowLearning) it.enableIntegerRowLearning()
                it.configureAttribution(params.propagationAttribution, params.historicalTableReasons,
                    params.costAwarePropagation)
            },
            branching = CpBranching.None,
        ).also {
            it.conflictStats = sink.ca
            resources.own(AutoCloseable {
                inprocessing?.reset()
                if (::searchSession.isInitialized) searchSession.releaseLearning()
                it.releaseRetainedState()
            })
            resources.own(AutoCloseable {
                try {
                    params.clauseExchange?.onSearchClose(it.session)
                } finally {
                    it.session.sharedLearningMemory = null
                    it.session.sharedLearningPosition = null
                }
            })
            sink.search.propagationWork = { it.session.work }
            sink.search.learning = { it.session.learnedLifecycle() }
            sink.search.valueOrdering = { it.session.valueOrdering.snapshot() }
            sink.search.rootPropagationWork = { it.session.rootWork }
            sink.search.propagationNanos = { it.session.propagationNanos }
            sink.search.rootPropagationNanos = { it.session.rootPropagationNanos }
        }
        yield(Unit)
        lpEngine = resources.own(
            LpEngine(
                problem, objective, params.lpParams(), sink, solver.lpSolveContext, deferred = true,
                nodeWorkAllowance = {
                    if (pausable && !preparationPending && externalCutoff().isFinite()) {
                        slice.remainingLpWork()
                    } else null
                },
            ),
        )
        lpEngine.preparation().forEach { yield(Unit) }
        lpEngine.lpHints = lpHints
        yield(Unit)
        brancher = BacktrackBrancher(
            session, params, sink, restart, LpGuidedBranching(), nativeConflict = { cp.lastConflict },
            leafColumns = params.leafColumns(session, objective),
        ).also {
            cp.propagationObserver = it::onPropagation
            cp.cascadeConflictObserver = it::onCascadeConflict
        }
        yield(Unit)
        searchSession = SearchComponentSet(
            buildList {
                add(cp)
                addAll(params.componentFactory?.invoke().orEmpty().also { components ->
                    components.forEach(resources::ownComponent)
                    cp.useEnforcingPeers(components.filterIsInstance<FiniteFactorEnforcer>()
                        .flatMap { it.enforcingFiniteFactors(problem) }.toSet())
                })
                if (lpEngine.lpRelaxer != null) {
                    lpEngine.cpAdapter.attach(session, feasibility = false)
                    add(lpEngine.propagator)
                }
            },
            branchers = listOf(brancher),
        ).session(
            cancellation = params.cancellation,
            learnedDb = params.sharedLearnedDb(),
            atoms = if (params.integerRowLearning) SearchAtomRegistry(problem.numBoolVars) else null,
        )
        if (params.clauseExchange != null || params.learnedMemoryBytes != null) {
            session.sharedLearningMemory = searchSession::retainedLearningMemory
            session.sharedLearningPosition = searchSession::retainedLearningPosition
        }
        yield(Unit)
        val seeded = if (deferredInstallation) {
            var result: PropagationResult? = null
            val seed = session.prepareSeed(params.assumptions)
            while (result == null) {
                result = seed.next()
                if (result == null) yield(Unit)
            }
            result
        } else {
            session.seed(params.assumptions)
        }
        cp.rebase()
        if (seeded is PropagationResult.Unsat || session.isUnsatAtRoot || problem.baked is PropagationResult.Unsat) {
            rootExhausted = (problem.baked as? PropagationResult.Unsat)?.let(::coreOf)
            rootIsExhausted = true
        } else {
            var initialized: ComponentResult? = null
            val initializer = searchSession.prepareInitialization { component, context ->
                if (deferredInstallation && component === cp) {
                    cp.prepareInitialization(context)
                } else if (deferredInstallation && component is PreparingSearchComponent) {
                    component.prepareInitialization(context)
                } else {
                    sequence { yield(component.initialize(context)) }.iterator()
                }
            }
            while (initialized == null) {
                initialized = initializer.next()
                if (initialized == null) yield(Unit)
            }
            when (initialized) {
                ComponentResult.Consistent -> params.clauseExchange?.onSearchStart(session)
                is ComponentResult.Conflict -> rootExhausted = null
                ComponentResult.Indeterminate -> Unit
            }
        }
        if (!deferredInstallation) checkInitialization()
        run = searchSession.openRun(problem.numBoolVars, traversal)
        initialWork = slice.spent()
    }.iterator()

    internal fun inheritPreparationBudget(preparation: SliceBudget) {
        check(installation == null)
        slice.inheritPreparation(preparation)
    }

    override val preparationPending: Boolean get() = installation != null && !closed

    internal fun advanceInstallation(): Boolean {
        check(!closed) { "search is closed" }
        val pending = installation ?: return true
        try {
            if (pending.hasNext()) pending.next() else installation = null
            return installation == null
        } catch (failure: Throwable) {
            closeAfter(failure)
            throw failure
        }
    }

    init {
        if (!deferredInstallation) {
            try {
                while (!advanceInstallation()) checkInitialization()
            } catch (failure: Throwable) {
                closeAfter(failure)
                throw failure
            }
        }
    }

    private fun checkInitialization() {
        if (pausable && ((::cp.isInitialized && session.fixpointCancelled) || solveCancelled())) {
            throw SearchInitializationCancelled(stats, work)
        }
    }

    override fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? {
        done?.let { return it }
        check(!closed) { "search is closed" }
        globalToken = baseCancellation.lifetimeCancellation() or global
        publishClausePrimalCandidate()?.let { onIncumbent(it.withSourceSemantics(problem.semantics)) }
        if (!rootIsExhausted && !slice.begin(sliceMillis, sliceNodes)) return null
        rootRoundingAttempted = false
        // A counted budget only means something if the search polls on a counted cadence: the run stops
        // where it polls, and the default cadence is tuned by elapsed time, so the pause would land on a
        // different node on a faster machine and every counter downstream would follow.
        run.fixedCancellationCadence = slice.workBounded
        try {
            if (rebindable) externalCutoff()
            while (true) {
                when (val e = runUntilEvent()) {
                    is StepEvent.Incumbent -> onIncumbent(e.result)

                    is StepEvent.Terminal -> return e.result

                    StepEvent.Paused -> {
                        params.clauseExchange?.onSearchEnd(session)
                        slice.noteOverspend()
                        // Restarts sample root fixings too, but an arm that restarts rarely would show none of its own.
                        sink.search.observeRootFixed(session.rootFixedVariableCount())
                        return null
                    }
                }
            }
        } catch (failure: Throwable) {
            closeAfter(failure)
            throw failure
        }
    }

    internal fun replacingObjective(next: LinearObjective, freshParams: BacktrackParams): ResumableMinimize {
        check(!replaced) { "search has been replaced" }
        val sample = incumbents.current()?.assignment?.let {
            Sample.fromOwned(it.bools, it.ints)
        }
        val root = solver.problem.conditionedRoot(freshParams.assumptions, freshParams.cancellation)
        val rootPins = (root.baked as? PropagationResult.Implied)?.toAssumptions() ?: Assumptions.None
        val replacement = ResumableMinimize(
            solver,
            next,
            freshParams.copy(assumptions = rootPins.mergedWith(freshParams.assumptions)),
            pausable,
            ownsWorkDebt = ownsWorkDebt,
            initialCandidate = sample,
            problem = root,
        )
        try {
            if (freshParams.cancellation()) throw CancellationException("objective replacement cancelled")
            replaced = true
            close()
        } catch (primary: Throwable) {
            replacement.closeAfter(primary)
            throw primary
        }
        return replacement
    }

    override fun close() {
        if (closed) return
        closed = true
        installation = null
        lpHints?.resetHistory()
        resources.close()
    }

    // A lazy sequence can be abandoned while suspended, so it must not carry native factors across a yield.
    internal fun releaseForSequenceYield() {
        check(!closed) { "search is closed" }
        var failure: Throwable? = null
        try {
            lpEngine.releasePersistentSolvers()
        } catch (closeFailure: Throwable) {
            failure = closeFailure
        }
        try {
            leafSolver?.releaseSolvers()
        } catch (closeFailure: Throwable) {
            failure?.addSuppressed(closeFailure) ?: run { failure = closeFailure }
        }
        failure?.let { throw it }
    }

    private fun closeAfter(failure: Throwable) {
        if (closed) return
        closed = true
        installation = null
        lpHints?.resetHistory()
        resources.closeAfter(failure)
    }

    /**
     * Re-drive this search on a new [assumptions] set with a fresh [decisionBudget] (LNS repair):
     * Re-seeds the persistent session and resets the fragment incumbent, while the LP
     * relaxation warm start and the session's learned-clause database survive. The caller must drive
     * successive repairs against a monotone non-increasing objective cutoff.
     */
    fun rebind(assumptions: Assumptions, decisionBudget: Long) {
        check(rebindable) { "search is not rebindable" }
        check(!closed) { "search is closed" }
        globalToken = baseCancellation.lifetimeCancellation()
        slice.begin(Long.MAX_VALUE, -1L)
        val pending = pendingRealLeaf
        pendingRealLeaf = null
        pendingRealResult = null
        pending?.close()
        searchSession.popTo(0)
        val seeded = session.reseedFrom(assumptions)
        lpEngine.cpAdapter.resetRoot()
        lpHints?.resetHistory()
        cp.rebase()
        searchSession.resetRootFacts()
        rootIsExhausted = seeded is PropagationResult.Unsat || session.isUnsatAtRoot
        rootExhausted = null
        if (!rootIsExhausted) {
            when (searchSession.initialize()) {
                ComponentResult.Consistent -> params.clauseExchange?.onSearchStart(session)
                is ComponentResult.Conflict -> rootExhausted = null
                ComponentResult.Indeterminate -> Unit
            }
        }
        decisionLimit = decisionBudget
        run.reset()
        inprocessing?.reset()
        learnedMemory.reset()
        firstRun = true
        rootRoundingPending = false
        lpEngine.rootRoundingWarmStart = null
        lpEngine.rootRoundingInterrupted = false
        treeBudgetExhausted = false
        incumbents = minimizingSampleExchange(problem, params.toleranceCheck)
        objVarBest = null
        lastObjBoundAsserted = null
        lastBoolCutoffValue = null
        lastOpenCutoff = null
        done = null
        pendingIncumbent = null
        unboundedLeaf = null
        sawIndeterminateLeaf = false
    }

    /** Advance the search to the next reportable [StepEvent].
     *
     * Visible to the enclosing solver, which streams it from [BacktrackSolver.improvements].
     */
    fun runUntilEvent(): StepEvent {
        try {
            done?.let { return StepEvent.Terminal(it) }
            check(!closed) { "search is closed" }
            if (rootIsExhausted) return terminal(terminalExhausted(rootExhausted))
            if (pendingRealLeaf != null || pendingRealResult != null) return traversalEvent()
            if (!clausePrimalStarted) {
                clausePrimalStarted = true
                if (!rebindable && initialCandidate == null && externalCutoff() == Double.POSITIVE_INFINITY) {
                    val remaining = runDeadline()?.let {
                        (it - TimeSource.Monotonic.markNow()).inWholeMilliseconds
                    } ?: params.solveBudgetMillis ?: 10_000L
                    clausePrimal = MonotoneClausePrimal.create(
                        solver, objective, params, runEndToken(), minOf(remaining / 2L, 5_000L).coerceAtLeast(0L),
                    )?.also { resources.own(it) }
                    if (clausePrimal != null) sink.search.clausalPrimalStarts++
                }
            }
            clausePrimal?.let { primal ->
                if (clausePrimalCandidate == null) {
                    val millis = slice.deadline?.let {
                        (it - TimeSource.Monotonic.markNow()).inWholeMilliseconds.coerceAtLeast(0L)
                    } ?: 5_000L
                    clausePrimalCandidate = primal.advance(millis, if (pausable) slice.remainingNodes() else -1L)
                    sink.search.clausalPrimalTrials = primal.trials
                    sink.search.clausalPrimalModels = primal.models
                    sink.search.clausalPrimalProposals = primal.proposals
                    sink.search.clausalPrimalRejected = primal.rejected
                    sink.search.clausalPrimalInfeasible = primal.infeasible
                    sink.search.clausalPrimalIncomplete = primal.incomplete
                }
                publishClausePrimalCandidate()?.let {
                    return StepEvent.Incumbent(it.withSourceSemantics(problem.semantics))
                }
                if (!primal.isDone && pausable && sliceCancelled()) return StepEvent.Paused
                if (primal.isDone) primal.close()
            }
            if (firstRun) {
                firstRun = false
                firstRunWork()?.let {
                    return StepEvent.Incumbent(it.withSourceSemantics(problem.semantics))
                }
            } else if (rootRoundingPending && !rootRoundingAttempted) {
                rootPrimalWork(rootLpBudget())?.let {
                    return StepEvent.Incumbent(it.withSourceSemantics(problem.semantics))
                }
            }
            if (rootRoundingPending) {
                slice.charge()
                if (sliceExpired()) return StepEvent.Paused
            }
            unboundedLeaf?.let { return terminal(terminalUnbounded(it)) }
            if (treeBudgetExhausted) return terminal(terminalBudget())
            return traversalEvent()
        } catch (failure: Throwable) {
            closeAfter(failure)
            throw failure
        }
    }

    private fun traversalEvent(): StepEvent {
        return when (val e = run.next()) {
            is SearchRunEvent.Satisfied -> {
                unboundedLeaf?.let { return terminal(terminalUnbounded(it)) }
                val incumbent = checkNotNull(pendingIncumbent)
                pendingIncumbent = null
                StepEvent.Incumbent(incumbent.withSourceSemantics(problem.semantics))
            }

            SearchRunEvent.Exhausted -> terminal(terminalExhausted(null))

            SearchRunEvent.Paused -> StepEvent.Paused

            is SearchRunEvent.Indeterminate -> when {
                sawIndeterminateLeaf -> {
                    val incumbent = pendingIncumbent
                    pendingIncumbent = null
                    if (incumbent != null) StepEvent.Incumbent(incumbent.withSourceSemantics(problem.semantics))
                    else terminal(terminalExhausted(null))
                }

                pausable && sliceCancelled() -> StepEvent.Paused

                // A component that could not decide its part ends the run incomplete, not out of budget.
                e == SearchRunEvent.Indeterminate.Component -> terminal(terminalUndecided())

                else -> terminal(terminalBudget())
            }
        }
    }

    private fun terminal(result: MinimizeResult): StepEvent {
        val qualified = result.withSourceSemantics(problem.semantics)
        done = qualified
        if (!rebindable) {
            close()
            val finalStats = qualified.stats.copy(lp = sink.lp.snapshot())
            done = when (qualified) {
                is MinimizeResult.Optimal -> qualified.copy(stats = finalStats)
                is MinimizeResult.BestFound -> qualified.copy(stats = finalStats)
                is MinimizeResult.Infeasible -> qualified.copy(stats = finalStats)
                is MinimizeResult.Unknown -> qualified.copy(stats = finalStats)
                is MinimizeResult.Unbounded -> qualified.copy(stats = finalStats)
            }
        }
        return StepEvent.Terminal(checkNotNull(done))
    }

    private fun terminalExhausted(core: UnsatCore?): MinimizeResult {
        sink.stop()
        val stats = sink.snapshot()
        val b = incumbents.current()
        return when {
            b != null && sawIndeterminateLeaf ->
                MinimizeResult.BestFound(b.assignment, b.objective.display, TerminationReason.Unsupported, stats)

            sawIndeterminateLeaf -> MinimizeResult.Unknown(TerminationReason.Unsupported, stats)

            externalShared && b != null ->
                MinimizeResult.BestFound(b.assignment, b.objective.display, TerminationReason.SearchExhausted, stats)

            externalShared -> MinimizeResult.Unknown(TerminationReason.SearchExhausted, stats)

            b != null -> MinimizeResult.Optimal(b.assignment, b.objective.display, stats)

            else -> MinimizeResult.Infeasible(core, stats)
        }
    }

    private fun terminalUnbounded(leaf: Pair<Sample, List<BigFraction>>): MinimizeResult {
        sink.stop()
        val (sample, direction) = leaf
        return MinimizeResult.Unbounded(sample, objective.evaluate(sample), direction, sink.snapshot())
    }

    private fun terminalUndecided(): MinimizeResult {
        sink.stop()
        val stats = sink.snapshot()
        val b = incumbents.current()
        return if (b != null) {
            MinimizeResult.BestFound(b.assignment, b.objective.display, TerminationReason.Unsupported, stats)
        } else {
            MinimizeResult.Unknown(TerminationReason.Unsupported, stats)
        }
    }

    private fun terminalBudget(): MinimizeResult {
        sink.stop()
        sink.timedOut = true
        val stats = sink.snapshot()
        val b = incumbents.current()
        return if (b != null) {
            MinimizeResult.BestFound(b.assignment, b.objective.display, TerminationReason.BudgetExhausted, stats)
        } else {
            MinimizeResult.Unknown(TerminationReason.BudgetExhausted, stats)
        }
    }

    /** Assert the incumbent bound on the objective var at the root; true iff that empties the root
     *  (optimum proven). */
    private fun assertObjectiveBoundAtRoot(): Boolean {
        val selected = singleObj ?: return postBoolObjectiveCutoffAtRoot()
        val objectiveVar = selected.varId
        val b = objVarBest ?: return false
        val ascending = selected.ascending
        val threshold = if (ascending) b - 1 else b + 1
        if (threshold == lastObjBoundAsserted) return false
        lastObjBoundAsserted = threshold
        return session.assertObjectiveBound(objectiveVar, threshold, atMost = ascending) is PropagationResult.Unsat
    }

    /*
     * Bounds open columns from the incumbent cutoff at level 0; see objectiveCutoffBounds.
     * Re-runs only after an improvement. An empty root proves the incumbent optimal.
     * This removes non-improving solutions, so only optimization reaches it, never enumeration.
     */
    private fun tightenOpenColumnsAtRoot(): Boolean {
        if (!bestObj.isFinite()) return false
        val value = bestObj.toLong()
        if (value.toDouble() != bestObj) return false // non-integral incumbent: no exact integer cutoff
        val last = lastOpenCutoff
        if (last != null && value >= last) return false
        lastOpenCutoff = value
        for (b in objectiveCutoffBounds(problem, objective, value)) {
            if (session.implyIntAtMost(b.varId, b.hi) is PropagationResult.Unsat) return true
        }
        return false
    }

    /**
     * For a pure-Boolean weighted objective (no single objective variable) post the incumbent cutoff
     * `Σ boolWeights_b·x_b ≤ bestObj − constant − 1` as a permanent pseudo-Boolean constraint, so CDCL and
     * pseudo-Boolean cutting-planes learning can *refute* it — the mechanism that proves optimality (a
     * weighted-Boolean objective has no objective variable to bound, so tree exhaustion alone rarely
     * closes it). Fired at the root on each restart, re-posting only when the incumbent improved. The
     * cutoff normalizes to `Σ |w_b|·ℓ_b ≥ degree` with `ℓ_b = x_b` when `w_b < 0` else `¬x_b`,
     * `degree = Σ_{w_b>0} w_b − (bestObj − constant − 1)`. Returns true iff it empties the root
     * (optimum proven). Integer constraints are independent of this Boolean cost view.
     */
    private fun postBoolObjectiveCutoffAtRoot(): Boolean {
        if (!params.pbObjectiveCutoff) return false
        val best = incumbents.current()?.objective?.exact ?: return false
        if (best == lastBoolCutoffValue) return false
        val cutoff = booleanObjectiveCutoff(problem, objective, best) ?: return false
        lastBoolCutoffValue = best
        return session.addLearnedPb(
            cutoff.weights, cutoff.literals, cutoff.degree, lbd = 1, permanent = true,
        ) is PropagationResult.Unsat
    }

    /** Offer [sample] (objective [o]) to [incumbents]; fires inline telemetry and returns the incumbent
     *  to surface when this call installed it, else null. Admission — a finite score, and the certified
     *  continuous values only a leaf's residual LP can attach — is [sampleAdmission]'s to decide. */
    private fun recordIfImproving(
        sample: Sample,
        o: Double,
        startingWitness: Boolean = false,
    ): MinimizeResult.WithSample? {
        val exact = exactSourceObjective(objective, sample, params.toleranceCheck)
        if (rebindable && exact == null) {
            sawIndeterminateLeaf = true
            return null
        }
        val cutoff = externalCutoff()
        val matchesCutoff = startingWitness && cutoff.isFinite() &&
            (exact?.let { it == BigFraction.ofDouble(cutoff) } ?: (o == cutoff))
        if (cutoff.isNaN() || (!matchesCutoff && !beatsExternalCutoff(exact, o))) {
            return null
        }
        val score = SourceObjectiveScore(o, exact)
        if (rebindable && !score.display.isFinite()) {
            sawIndeterminateLeaf = true
            return null
        }
        if (incumbents.offer(sample, score) !is Publication.Installed) return null
        singleObj?.let { objVarBest = sample.intValue(it.varId) }
        if (!startingWitness) {
            brancher.onSolution(sample)
            params.improvedSolutionSink?.invoke(sample, score.display)
            params.onEvent?.invoke(SearchEvent.Incumbent(score.display))
        }
        // Carry the counters as they stand. A caller that consumes the improvement stream and stops when
        // it dries up holds only the last step it saw, so an incumbent without stats leaves the whole run
        // reporting nothing — search and LP alike. Snapshotting mid-search is sound: it reads elapsed time
        // live rather than requiring the sink to have been stopped.
        return MinimizeResult.BestFound(sample, score.display, TerminationReason.BudgetExhausted, sink.snapshot())
    }

    /**
     * Publish an LP primal heuristic's [proposal]: the LP heuristics propose from the relaxation's view
     * of the model, so what they hand back is untrusted here however carefully they built it, and
     * [ComposedSampleVerifier] re-derives its feasibility and its objective from the whole factor set
     * before the exchange weighs it. Null unless this call installed a new incumbent — a refuted
     * proposal is one bad rounding and an undecided one a fixpoint the budget cut short, and neither says
     * anything about the model, so both simply leave the search to find its own first solution.
     *
     * Verification runs under the run's token capped by [LP_VERIFY_BUDGET], so a cancellation or a
     * slice boundary stops it where it stops the rest of the engine.
     */
    private fun publishLpProposal(proposal: Sample): MinimizeResult.WithSample? {
        val accepted = verifiedLpProposal(proposal) ?: return null
        return recordIfImproving(accepted.assignment, accepted.objective)
    }

    private fun publishClausePrimalCandidate(): MinimizeResult.WithSample? {
        val proposal = clausePrimalCandidate ?: return null
        clausePrimalCandidate = null
        val accepted = verifiedLpProposal(proposal, runEndToken()) ?: return null
        val published = recordIfImproving(accepted.assignment, accepted.objective) ?: return null
        sink.search.clausalPrimalAccepted++
        return published
    }

    private fun verifiedLpProposal(
        proposal: Sample,
        cancellation: Cancellation = params.cancellation,
        onDecline: ((String) -> Unit)? = null,
    ): Candidate<Sample, Double>? {
        fun decline(outcome: String): Candidate<Sample, Double>? {
            onDecline?.invoke(outcome)
            return null
        }
        if (params.componentFactory != null) return decline("EXTRA_COMPONENTS")
        if (cancellation()) return decline("CANCELLED")
        if (proposal.numBoolVars != problem.numBoolVars || proposal.numIntVars != problem.numIntVars) {
            return decline("INVALID_COORDINATES")
        }
        val onWork: (Long) -> Unit = { proposalPropagationWork += it }
        val sample = if (problem.numRealVars > 0 && proposal.numRealVars == 0) {
            val discrete = composedFixpoint(problem, Candidate(proposal, Unit), cancellation,
                params.assumptions, onWork)
            if (discrete !is Verification.Accepted) {
                return decline(if (discrete is Verification.Rejected) "DISCRETE_REJECTED" else "DISCRETE_INDETERMINATE")
            }
            if (cancellation()) return decline("CANCELLED")
            val real = completeLeaf(proposal, cancellation)
            if (real.verdict !in listOf(LpVerdict.FEASIBLE, LpVerdict.ATTAINED_OPTIMUM, LpVerdict.TOLERANCE_OPTIMUM)) {
                return decline("REAL_${real.verdict.name}")
            }
            proposal.copy(reals = real.reals, exactReals = real.exactReals)
        } else proposal
        val budget = if (cancellation.workScheduled()) cancellation else
            cancellation or Cancellation.after(LP_VERIFY_BUDGET)
        if (budget()) return decline("CANCELLED")
        val verifier = ComposedSampleVerifier(problem, objective, budget,
            params.assumptions, onWork, params.toleranceCheck)
        val verdict = verifier.verify(Candidate(sample, objective.evaluate(sample)))
        if (budget()) return decline("CANCELLED")
        return when (verdict) {
            is Verification.Accepted -> verdict.candidate
            is Verification.Rejected -> decline("VERIFICATION_REJECTED")
            is Verification.Indeterminate -> decline("VERIFICATION_INDETERMINATE")
        }
    }

    /**
     * Initial pre-search root work (harvest the global cut pool, capture the root relaxation bound for
     * the integrality-gap metric, root shaving, bound exchange, and the primal probes). Run on the first
     * engine step so the live cancellation gates the LP solves. Returns the first incumbent if a probe
     * seeds one (in which case the tree-search probe is skipped this call), else null. The root-LP work
     * is time-boxed by [rootLpBudget]. Interrupted rounding retains its basis for later slices under
     * the remaining root and LP work allowances.
     */
    private fun firstRunWork(): MinimizeResult.WithSample? {
        if (!hasPreparation) sink.start()
        return firstRunWorkBody()
    }

    private fun revalidateInitialCandidate(): MinimizeResult.WithSample? {
        val sample = initialCandidate ?: return null
        initialCandidate = null
        if (params.componentFactory != null) return null
        val token = if (params.cancellation.workScheduled()) params.cancellation else
            params.cancellation or Cancellation.after(LP_VERIFY_BUDGET)
        if (token() || sample.numBoolVars != problem.numBoolVars || sample.numIntVars != problem.numIntVars) return null
        val validation = PropagationSession(problem, token)
        if (validation.seed(params.assumptions) is PropagationResult.Unsat || validation.isUnsatAtRoot) return null
        for (variable in (0 until sample.numBoolVars)) {
            if (validation.pinBool(variable, sample.boolValue(variable)) is PropagationResult.Unsat) return null
        }
        for (variable in (0 until sample.numIntVars)) {
            if (validation.pinInt(variable, sample.intValue(variable)) is PropagationResult.Unsat) return null
        }
        if (validation.fixpointCancelled || token()) return null
        val accepted = if (problem.numRealVars == 0) {
            sample
        } else {
            val real = completeLeaf(sample, token)
            if (real.verdict !in listOf(
                    LpVerdict.FEASIBLE,
                    LpVerdict.ATTAINED_OPTIMUM,
                    LpVerdict.TOLERANCE_OPTIMUM,
                    LpVerdict.UNBOUNDED,
                )
            ) {
                return null
            }
            val completed = sample.copy(reals = real.reals, exactReals = real.exactReals)
            if (real.verdict == LpVerdict.UNBOUNDED) {
                if (!token()) unboundedLeaf = completed to checkNotNull(real.direction)
                return null
            }
            completed
        }
        if (token()) return null
        return recordIfImproving(accepted, objective.evaluate(accepted), startingWitness = !rebindable)
    }

    private fun firstRunWorkBody(): MinimizeResult.WithSample? {
        if (initialCandidate == null && pausable && !rebindable && externalShared && restart === NoRestartSchedule) {
            initialCandidate = params.pooledIncumbents?.current()?.assignment
        }
        revalidateInitialCandidate()?.let {
            if (applyObjectiveBound() == SearchRunDisposition.Exhausted) rootIsExhausted = true
            return it
        }
        if (unboundedLeaf != null) return null
        val rootToken = rootLpBudget()
        if (rootLpDutyCycle.allows(lpEngine.totalSolveWork())) {
            val before = lpEngine.totalSolveWork()
            initRootLp(rootToken)
            rootLpDutyCycle.record(before, lpEngine.totalSolveWork())
        }
        // Objective shaving: raise the objective's proven lower bound before search when the LP +
        // propagation prove lower values infeasible. Sound — every raise is a proof.
        if (lpEngine.params.lpPlan.objectiveShaving) {
            singleObj?.let { obj ->
                lpEngine.shaveObjectiveLb(obj.varId, obj.ascending, rootToken)?.let { lb ->
                    session.implyIntAtLeast(obj.varId, lb)
                }
            }
        }
        // Variable shaving: tighten integer domains the LP + propagation prove cannot reach their
        // declared bounds. Sound — each tightening is a proof.
        if (lpEngine.params.lpPlan.variableShaving) {
            for (sb in lpEngine.shaveVariableBounds(rootToken)) {
                session.implyIntAtLeast(sb.varId, sb.lo)
                session.implyIntAtMost(sb.varId, sb.hi)
            }
        }
        // Strip-energy floor: the tasks proven to end by the objective need room for their energy.
        // At the root the raise is a level-0 fact; under assumptions it keeps its reason, whose bound atoms
        // are built only then so the root search sees no extra atoms.
        singleObj?.let { obj ->
            lpEngine.stripEnergyFloor(session)?.let { floor ->
                if (floor.value > session.intDomain(obj.varId).min) {
                    if (session.decisionLevel == 0) session.implyIntAtLeast(obj.varId, floor.value)
                    else session.implyIntAtLeastWithReason(obj.varId, floor.value, floor.reason)
                }
            }
        }
        boundExchange.applySharedFloor()
        // Publish the root floor (post-shaving) so peers see the bound this arm proved up front.
        boundExchange.publishFloor()
        // Exchange globally-valid level-0 variable tightenings: import peers' first, then publish this
        // arm's (root propagation + shaving), before any incumbent-relative fixing runs.
        boundExchange.importGlobalVarBounds()
        boundExchange.publishGlobalVarBounds()
        rootPrimalWork(rootToken)?.let { return it }
        if (rootRoundingPending) return null
        if (lpEngine.params.lpPlan.lbTreeSearch && lpEngine.lpRelaxer != null && params.componentFactory == null) {
            val before = lpEngine.primalSearchWork
            val seed = lpEngine.lbTreeSearch(objective, rootToken, params.assumptions, params.toleranceCheck,
                complete = ::completeLeaf,
                accept = { verifiedLpProposal(it, rootToken)?.assignment },
                nodeBudget = params.nodeBudget)
            treeBudgetExhausted = lpEngine.primalSearchWork > before && params.nodeBudget?.exhausted() == true
            if (seed != null && !params.cancellation()) {
                recordIfImproving(seed.sample, objective.evaluate(seed.sample))?.let { return it }
            }
        }
        return null
    }

    private fun rootPrimalWork(rootToken: Cancellation): MinimizeResult.WithSample? {
        rootRoundingAttempted = true
        if (!lpEngine.params.lpPlan.probe || lpEngine.lpRelaxer == null) {
            rootRoundingPending = false
            lpEngine.rootRoundingWarmStart = null
            return null
        }
        fun accept(proposal: Sample): Sample? {
            val started = TimeSource.Monotonic.markNow()
            var outcome = "FAILED"
            try {
                val accepted = verifiedLpProposal(proposal, rootToken) { outcome = it } ?: return null
                if (externalCutoff().isNaN()) {
                    outcome = "INVALID_CUTOFF"
                    return null
                }
                val exact = exactSourceObjective(objective, accepted.assignment, completionToleranceCheck())
                val standing = incumbents.current()?.objective
                val improvesLocal = if (exact != null && standing?.exact != null) {
                    exact < standing.exact
                } else accepted.objective < bestObj
                val improving = improvesLocal && beatsExternalCutoff(exact, accepted.objective)
                outcome = when {
                    !improving -> "NON_IMPROVING"
                    rootToken() -> "CANCELLED"
                    else -> "IMPROVING"
                }
                return accepted.assignment.takeIf { outcome == "IMPROVING" }
            } finally {
                sink.lp.observePrimalPhase("PRIMAL_PROPOSAL", outcome, started.elapsedNow().inWholeNanoseconds)
            }
        }
        val rounded = lpEngine.lpRoundingProbe(objective, rootToken,
            initial = if (problem.numRealVars > 0) params.pooledIncumbents?.current()?.assignment else null,
            toleranceReals = params.toleranceCheck != null,
            accept = ::accept)
        rootRoundingPending = rounded == null && canResumeRootRounding()
        if (rootRoundingPending) return null
        lpEngine.rootRoundingWarmStart = null
        val seed = rounded ?: lpEngine.lpFeasibilityPump(objective, rootToken,
            initial = if (problem.numRealVars > 0) params.pooledIncumbents?.current()?.assignment else null,
            accept = ::accept)
        if (seed != null) recordIfImproving(seed, objective.evaluate(seed))?.let { return it }
        return null
    }

    private fun canResumeRootRounding(): Boolean {
        val plan = lpEngine.params.lpPlan
        return pausable && lpEngine.rootRoundingInterrupted && !solveCancelled() &&
            (plan.rootBudgetFraction <= 0.0 ||
                !lpEngine.workSpentExceeds(plan.rootMaxWork, sink.lp.standaloneWork)) &&
            !lpEngine.lpWorkAllowanceSpent(sink.lp.standaloneWork)
    }

    /**
     * One-shot pre-search LP work: harvest the global cut pool and capture the root relaxation bound for
     * the integrality-gap metric (search only bounds from level 1 down, so this is the sole root
     * capture). [token] is the shared root-LP budget — the slice/global cancellation time-boxed to
     * `LpPlan.rootBudgetFraction`.
     */
    private fun initRootLp(token: Cancellation) {
        val relaxer = lpEngine.harvestRootRelaxation(token) ?: return
        val rootBound = lpEngine.rootLpRelaxationBound(relaxer, lpEngine.lpGlobalCuts, token)
        sink.lp.observeRootBound(0, rootBound)
        // Publish the root LP bound to the portfolio's shared lower-bound manager: a sound global lower
        // bound on the optimum, so a peer arm can pair it with its own incumbent to prove optimality. A
        // NaN (no LP structure) carries no information and the sink ignores it.
        if (!rootBound.isNaN()) params.objectiveLowerBoundSink?.invoke(rootBound)
    }

    /**
     * The shared cooperative-cancellation budget for the pre-search root LP work: the
     * slice/global [BacktrackParams.cancellation] OR-ed with a wall-clock deadline of
     * `LpPlan.rootBudgetFraction` of the time remaining (capped at `LpPlan.rootBudgetMillis`). The time
     * remaining is read from the current slice deadline when one is armed, else from
     * [BacktrackParams.solveBudgetMillis] and [startMark] on the non-pausable one-shot path — so
     * the cap tracks the real deadline on both paths rather than the absolute ceiling, which exceeds a
     * short budget and would let root work consume the whole solve. Only the absolute cap
     * applies when neither the slice end nor the budget is known. It also stops once the LP has spent
     * `LpPlan.rootMaxWork` or the LP work allowance the node solves share. A non-positive fraction
     * disables the cap.
     */
    private fun rootLpBudget(): Cancellation {
        val fraction = params.lpPlan.rootBudgetFraction
        if (fraction <= 0.0) return params.cancellation
        val cap = params.lpPlan.rootBudgetMillis
        val remaining = remainingBudgetMillis()
        val budgetMillis = if (remaining != null) minOf((remaining * fraction).toLong(), cap) else cap
        // Work first, clock second. A harvest bounded by time keeps a different set of cuts on every
        // run, and the search inherits them, so nothing downstream is comparable to itself; bounding it
        // by work makes the harvest a property of the model. The deadline stays as the backstop for
        // cost the meter cannot see.
        val opsBudget = lpEngine.params.lpPlan.rootMaxWork
        // The shared LP work allowance caps it too, so an expensive-but-useless root relaxation cannot spend
        // more than the whole LP subsystem is allotted before search starts.
        return params.cancellation or
            Cancellation { lpEngine.workSpentExceeds(opsBudget, sink.lp.standaloneWork) ||
                lpEngine.lpWorkAllowanceSpent(sink.lp.standaloneWork) } or
            if (params.cancellation.workScheduled()) Cancellation.Never else
                Cancellation.after(budgetMillis.coerceAtLeast(0).milliseconds)
    }

    /** Milliseconds left until the effective deadline: the armed slice end (pausable portfolio path),
     *  else [BacktrackParams.solveBudgetMillis] minus the elapsed since [startMark] (the one-shot path),
     *  else null when no budget is known. */
    private fun remainingBudgetMillis(): Long? {
        slice.deadline?.let { return (it - TimeSource.Monotonic.markNow()).inWholeMilliseconds }
        return params.solveBudgetMillis?.let { it - startMark.elapsedNow().inWholeMilliseconds }
    }

    private fun drainLpNogoods(): Boolean {
        val lpNogoods = lpEngine.lpNogoods ?: return false
        for (entry in lpNogoods.drain()) {
            val nogood = entry.literals
            if (!entry.registered) {
                val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                if (res is PropagationResult.Unsat) return true
            }
            // Complete source premises make these feasibility clauses independent of the incumbent.
            params.clauseExchange?.publishGlobal(session.asSharedClause(nogood, nogood.size))
        }
        return false
    }

    /** LP guidance affects only branch order; the shared runner still owns every frame and retraction. */
    private inner class LpGuidedBranching : BacktrackBranching {
        override fun onBranch(variable: VarRef) { lpHints?.branch(variable) }

        override fun onCommit(decision: SearchDecision, depth: Int) { lpHints?.commit(decision, depth) }

        override fun onRestart() { lpHints?.resetHistory() }

        override fun pick(session: PropagationSession): VarRef? = lpEngine.lpBranchPick(session, lpHints)

        override fun orderValues(variable: VarRef, values: Sequence<Long>): Sequence<Long> =
            lpHints?.order(variable, values) ?: values
    }

    /** Refutes LP-dominated partial assignments through the shared frame stack. */
    private inner class LpNodePolicy : SearchNodePolicy {
        override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
            slice.charge()
            if (pausable && slice.workExpired()) return SearchNodeDisposition.Pause
            when (simplify(context, false)) {
                SearchRunDisposition.Exhausted -> return SearchNodeDisposition.Prune
                SearchRunDisposition.Pause -> return SearchNodeDisposition.Pause
                SearchRunDisposition.Indeterminate -> return SearchNodeDisposition.Indeterminate
                SearchRunDisposition.Continue -> Unit
            }
            val externalBound = externalCutoff()
            val effectiveBound = if (externalBound < bestObj) externalBound else bestObj
            if (rebindable && discreteObjective) {
                val externalExact = exactObjectiveBoundSupplier?.invoke()
                val localExact = incumbents.current()?.objective?.exact
                val exactBound = when {
                    externalExact == null -> localExact
                    localExact == null || externalExact < localExact -> externalExact
                    else -> localExact
                }
                val dominated = if (exactBound != null) lpEngine.linearBoundDominates(session, exactBound)
                    else lpEngine.linearBoundDominates(session, effectiveBound)
                if (dominated) return SearchNodeDisposition.Prune
            }
            // Persistent LP fixings may use only a cutoff that survives every later repair.
            val lpBound = if (rebindable) externalBound else effectiveBound
            lpHints?.beginNode(context.decisionLevel, lpBound)
            val pruned = lpEngine.pruneNode(session, lpBound, singleObj?.varId ?: -1, singleObj?.ascending ?: true)
            lpHints?.finishNode(lpEngine.cpAdapter.currentModel?.exactState)
            if (!pruned) {
                return SearchNodeDisposition.Expand
            }
            val learned = cp.mappedLearnedConstraint(lpEngine.lastBackjump())
            return if (learned != null &&
                learned.asserting &&
                !session.learnedConstraintSatisfied(learned)
            ) {
                sink.lp.observeBackjump()
                SearchNodeDisposition.Backjump(LpLearnedConflict(learned))
            } else {
                SearchNodeDisposition.Prune
            }
        }
    }

    /** Adapts an asserting LP Farkas consequence to the shared traversal's native backjump contract. */
    private inner class LpLearnedConflict(private val learned: LearnedConstraint) : SearchLearnedConflict {
        override val decisionLevel: Int get() = cp.sharedLevelForNative(learned.backjumpLevel)
        override val lbd: Int get() = learned.lbd
        override val guardLiterals: IntArray get() = learned.guardLiterals
        override val decisionLevels: IntArray get() = learned.decisionLevels

        override fun apply(session: com.eignex.klause.solver.search.SearchSession): SearchLearnedConflictResult {
            if (!learned.asserting || learned.isContradiction) {
                return SearchLearnedConflictResult.Chronological
            }
            val result = when (learned) {
                is com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.Learned -> {
                    this@ResumableMinimize.session.addLearnedClause(Clause(learned.literals), learned.lbd)
                }

                is com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.LearnedPb -> {
                    this@ResumableMinimize.session.addLearnedPb(
                        learned.weights,
                        learned.literals,
                        learned.degree,
                        learned.lbd,
                    )
                }

                is com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.LearnedIntegerRow -> {
                    this@ResumableMinimize.session.addLearnedIntegerRow(learned)
                }
            }
            return when (result) {
                is PropagationResult.Implied -> {
                    // The clause stays in the CP database; see the note in CpSearchComponent.
                    when (cp.import(result, session)) {
                        ComponentResult.Consistent -> when (session.propagate()) {
                            ComponentResult.Consistent -> SearchLearnedConflictResult.Resume
                            is ComponentResult.Conflict -> SearchLearnedConflictResult.Chronological
                            ComponentResult.Indeterminate -> SearchLearnedConflictResult.Indeterminate
                        }

                        is ComponentResult.Conflict -> SearchLearnedConflictResult.Chronological

                        ComponentResult.Indeterminate -> SearchLearnedConflictResult.Indeterminate
                    }
                }

                is PropagationResult.Unsat -> {
                    val next = cp.mappedLearnedConstraint(result.learnedClause as? LearnedConstraint)
                    when {
                        next == null -> SearchLearnedConflictResult.Chronological

                        next.backjumpLevel == 0 && next.isContradiction -> {
                            SearchLearnedConflictResult.Exhausted
                        }

                        else -> SearchLearnedConflictResult.Backjump(LpLearnedConflict(next))
                    }
                }
            }
        }
    }

    /** Turns feasible shared models into strictly improving optimization incumbents. */
    private inner class IncumbentPolicy : SearchModelPolicy {
        override fun onModel(
            model: com.eignex.klause.solver.search.AssembledSearchModel,
            context: SearchContext,
        ): SearchModelDisposition {
            val sample = model.valueOf(completedSearchSample) ?: checkNotNull(model.valueOf(cp.assignmentKey))
            // The leaf is the engine's own claim, re-derived here from every factor before it can become an
            // incumbent: an assignment the composed fixpoint refutes is an engine defect, never a solution. A check
            // the deadline cut short refutes nothing, so the leaf stands.
            if (pendingRealLeaf == null && pendingRealResult == null) {
                val verdict = composedFixpoint(
                    problem, Candidate(sample, Unit), params.cancellation, params.assumptions,
                )
                if (verdict is Verification.Rejected) {
                    throw UnsoundnessException(
                        "backtrack reached an optimization leaf its constraints refute: ${verdict.reason}",
                    )
                }
            }
            // What this leaf left open, apart from the run-wide record in sawIndeterminateLeaf.
            var unresolved = false
            var incomplete = false
            val incumbent = if (problem.numRealVars == 0) {
                recordIfImproving(sample, objective.evaluate(sample))
            } else {
                val real = if (pausable) {
                    advanceRealLeaf(sample) ?: return SearchModelDisposition.Pause
                } else completeLeaf(sample, runEndToken())
                when (real.verdict) {
                    LpVerdict.INFEASIBLE -> null

                    LpVerdict.INDETERMINATE, LpVerdict.CERTIFIED_BOUND -> {
                        sawIndeterminateLeaf = true
                        val witness = real.uncertifiedReals?.let { sample.copy(reals = it) }
                        val accepted = witness != null && !params.cancellation() &&
                            params.toleranceCheck?.invoke(witness) == true && !params.cancellation()
                        if (pausable && params.cancellation()) return SearchModelDisposition.Pause
                        val published = if (accepted) recordIfImproving(checkNotNull(witness),
                            objective.evaluate(witness)) else null
                        incomplete = published != null && !rebindable
                        unresolved = published == null
                        published
                    }

                    // A checked ray ends the run whatever the incumbent: no objective value bounds the model.
                    LpVerdict.UNBOUNDED -> {
                        unboundedLeaf = sample.copy(reals = real.reals, exactReals = real.exactReals) to
                            checkNotNull(real.direction)
                        null
                    }

                    LpVerdict.FEASIBLE,
                    LpVerdict.ATTAINED_OPTIMUM,
                    LpVerdict.TOLERANCE_OPTIMUM,
                    -> {
                        // A tolerance optimum completes the leaf on purpose: the source's own semantics accept it.
                        if (real.verdict !in setOf(LpVerdict.ATTAINED_OPTIMUM, LpVerdict.TOLERANCE_OPTIMUM)) {
                            sawIndeterminateLeaf = true
                            incomplete = true
                        }
                        val full = sample.copy(reals = real.reals, exactReals = real.exactReals)
                        recordIfImproving(full, objective.evaluate(full))
                    }
                }
            }
            pendingRealResult = null
            pendingIncumbent = incumbent
            return when {
                unboundedLeaf != null -> SearchModelDisposition.Surface

                // A leaf the LP could not decide is passed over rather than ending the arm: the search goes on to
                // other leaves, and the sticky flag keeps the terminal from claiming exhaustion or an optimum.
                unresolved -> SearchModelDisposition.Skip

                // Blocking a leaf whose real part is unproved would let its nogood escape as a proved shared conflict.
                incomplete -> SearchModelDisposition.Indeterminate

                incumbent == null -> SearchModelDisposition.Continue

                else -> SearchModelDisposition.Surface
            }
        }
    }

    /** CP optimization configuration and root-boundary work for the shared traversal. */
    private inner class OptimizationTraversalPolicy :
        SearchTraversalPolicy,
        SearchRunLifecycle {
        override val solveParams: SearchSolveParams
            get() = SearchSolveParams(
                maxDecisions = params.nodeBudget?.allowance?.decisionsLeft() ?: Long.MAX_VALUE, restart = restart,
            )
        override val booleanBranching = BooleanBranching.None

        // Two allowances, and a decision is charged against both. [decisionLimit] bounds one slice and is
        // refilled whenever a driver re-enters — a restart, an arm resuming, an LNS repair — so it never
        // bounds a solve. [BacktrackParams.nodeBudget] is the solve-spanning one that enforces a node limit.
        override val decisionBudget = SearchDecisionBudget {
            val withinSlice = --decisionLimit >= 0L
            val budget = params.nodeBudget?.allowance ?: return@SearchDecisionBudget withinSlice
            budget.spend()
            withinSlice && !budget.exhausted()
        }
        override val observer = brancher
        override val modelContinuation = if (rebindable) {
            SearchModelContinuation.Chronological
        } else {
            SearchModelContinuation.BlockAtRoot
        }
        override val modelPolicy: SearchModelPolicy = IncumbentPolicy()
        override val pauseBeforeDecision: () -> Boolean = {
            slice.charge()
            pausable && slice.workExpired()
        }
        override val nodePolicy: SearchNodePolicy = LpNodePolicy()
        override val lifecycle: SearchRunLifecycle get() = this

        override fun onResume(context: SearchContext): SearchRunDisposition {
            val simplified = simplify(context, false)
            if (simplified != SearchRunDisposition.Continue) return simplified
            if (pendingRealLeaf != null || pendingRealResult != null) return SearchRunDisposition.Continue
            boundExchange.applySharedFloor()
            val local = incumbents.current()
            if (params.valueSelector is SolutionGuided && (local != null || lpEngine.params.lpPlan.bounding)) {
                pooledIncumbents?.poll()?.takeIf { local == null || it.objective < local.objective.display }?.let {
                    brancher.importPooledSolution(it.assignment, guideValues = true, preferValues = local == null)
                }
            }
            return SearchRunDisposition.Continue
        }

        override fun onModelBlocked(context: SearchContext): SearchRunDisposition = applyObjectiveBound()

        override fun onRestart(context: SearchContext): SearchRunDisposition {
            if (drainLpNogoods()) return SearchRunDisposition.Exhausted
            params.clauseExchange?.onRestart(session)
            params.cutExchange?.let { lpEngine.exchangeCuts(it) }
            val bound = applyObjectiveBound()
            if (bound != SearchRunDisposition.Continue) return bound
            boundExchange.applySharedFloor()
            boundExchange.publishFloor()
            boundExchange.importGlobalVarBounds()
            forgetIfOverCap(session, params)
            val simplified = simplify(context, true)
            if (simplified != SearchRunDisposition.Continue) return simplified
            pooledIncumbents?.poll()?.let { brancher.importPooledSolution(it.assignment) }
            return SearchRunDisposition.Continue
        }

        override fun onCancellation(context: SearchContext): SearchRunDisposition {
            return if (pausable) SearchRunDisposition.Pause else SearchRunDisposition.Indeterminate
        }
    }

    private fun simplify(context: SearchContext, restart: Boolean): SearchRunDisposition {
        session.retainedInprocessingMemory = inprocessing?.let { it::retainedMemory }
        learnedMemory.run(session, params, inprocessing, sink.search)
        if (context.decisionLevel != 0) return SearchRunDisposition.Continue
        val refresh = if (restart) inprocessing?.onRestart(session, params) else inprocessing?.onRoot(session, params)
        if (session.isUnsatAtRoot) return SearchRunDisposition.Exhausted
        if (refresh != true) return SearchRunDisposition.Continue
        val result = checkNotNull(inprocessing).refreshRoot(cp, context, params)
        if (result == null) return if (pausable) SearchRunDisposition.Pause else SearchRunDisposition.Indeterminate
        if (result is ComponentResult.Conflict) return SearchRunDisposition.Exhausted
        if (result == ComponentResult.Indeterminate) {
            return if (pausable) SearchRunDisposition.Pause else SearchRunDisposition.Indeterminate
        }
        return SearchRunDisposition.Continue
    }

    private fun applyObjectiveBound(): SearchRunDisposition {
        session.installObjectiveBoolBound(objective.boolWeightValues)
        if (rebindable) return SearchRunDisposition.Continue
        if (assertObjectiveBoundAtRoot() || tightenOpenColumnsAtRoot()) return SearchRunDisposition.Exhausted
        if (params.componentFactory == null) return SearchRunDisposition.Continue
        // Objective cutoffs alter native CP bounds at the root; peers must see the tightened facts.
        val initialized = cp.initialize(searchSession)
        val result = if (initialized == ComponentResult.Consistent) searchSession.propagate() else initialized
        return when (result) {
            ComponentResult.Consistent -> SearchRunDisposition.Continue
            is ComponentResult.Conflict -> SearchRunDisposition.Exhausted
            ComponentResult.Indeterminate -> SearchRunDisposition.Indeterminate
        }
    }
}
