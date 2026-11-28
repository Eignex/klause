package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.bounding.hasDefaultNumericalSettings
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.CpBranching
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.LeafColumns
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.VarKind
import com.eignex.klause.propagation.baked
import com.eignex.klause.propagation.projectSeedCore
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentSubscription
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.UnsatCore
import com.eignex.klause.solver.result.projectSeedConflictToAssumptions
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.FiniteFactorEnforcer
import com.eignex.klause.solver.search.PreparingSearchComponent
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchDecisionBudget
import com.eignex.klause.solver.search.SearchExecutionAccounting
import com.eignex.klause.solver.search.SearchExecutionControlAdapter
import com.eignex.klause.solver.search.SearchInvocationResources
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchModelContinuation
import com.eignex.klause.solver.search.SearchModelPolicy
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchRun
import com.eignex.klause.solver.search.SearchRunDisposition
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchRunLifecycle
import com.eignex.klause.solver.search.SearchRunObserver
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.SearchStopCause
import com.eignex.klause.solver.search.SearchTraversalPolicy
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.cancelledWhen
import com.eignex.klause.util.pollStop
import com.eignex.klause.util.workScheduled
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntHashSet

// Null when there is no assumption layer or no level was touched.
internal fun BacktrackSolver.projectTouchedToAssumptions(input: Assumptions, levels: IntArray): Assumptions? {
    if (input.isEmpty || levels.isEmpty()) return null
    return projectSeedConflictToAssumptions(input, levels)
}

internal fun touchedToArray(touched: IntHashSet?): IntArray {
    if (touched == null || touched.isEmpty()) return emptyIntArray()
    val out = touched.toIntArray()
    out.sort()
    return out
}

// A seed-only contradiction has no factor core, reported as absent rather than empty.
internal fun coreOf(unsat: PropagationResult.Unsat): UnsatCore? = if (unsat.conflictFactors.isEmpty()) {
    null
} else {
    UnsatCore.of(unsat.conflictFactors)
}

internal sealed interface SearchOutcome {
    data class Found(val sample: Sample) : SearchOutcome

    // core is non-null only when root propagation forced the exhaustion. touchedAssumptionLevels holds a jointly
    // refuted seed prefix at propagation or the full seed range after tree exhaustion, for assumption-core
    // projection.
    data class Exhausted(
        val core: UnsatCore? = null,
        val touchedAssumptionLevels: IntArray = emptyIntArray(),
        // A leaf's continuous relaxation could not be certified either way, so the verdict is unknown, not UNSAT.
        val indeterminate: Boolean = false,
        val stopCause: SearchStopCause? = null,
    ) : SearchOutcome
    data class BudgetCapped(val stopCause: SearchStopCause? = null) : SearchOutcome
}

// How a pausable traversal meets its slice boundary: whether a fired cancellation ends the slice rather than the
// run, and the hook each node runs before it branches.
internal class TraversalSlice(
    val pauses: () -> Boolean,
    val beforeBranch: () -> Boolean,
    val atRoot: ((PropagationSession) -> Boolean)? = null,
    val remainingLpWork: () -> Long? = { null },
)

// Satisfaction search driven by the shared SearchRun, advanced one outcome at a time. With a slice, next() returns
// null where the slice fires and the following call resumes mid-tree. Root propagation polls
// propagationCancellation, since a fixpoint cut by a slice would leave the session under-propagated.
//
// A singleModel caller takes one model, so the leafColumns columns are completed at leaves rather than decided; an
// enumeration, a sampler or a counter tells models apart by those columns and keeps deciding them.
@Suppress("TooGenericExceptionCaught") // ownership boundaries must preserve arbitrary primary and cleanup failures
internal class CpSatisfactionTraversal(
    private val problem: BakedProblem,
    private val params: BacktrackParams,
    private val sink: SolveStatsSink?,
    solveContext: LpSolveContext,
    propagationCancellation: ExecutionControl = params.cancellation,
    private val slice: TraversalSlice? = null,
    projection: PropagationProblem = PropagationProblem(problem),
    private val deferredInitialization: Boolean = false,
    singleModel: Boolean = false,
    projectAssumptionCore: Boolean = false,
    preparedSession: PropagationSession? = null,
    preparedSeed: PropagationResult? = null,
    private val rebindable: Boolean = false,
    onRelease: ((PropagationSession) -> Unit)? = null,
    onPropagationWork: ((() -> Long) -> Unit)? = null,
) : AutoCloseable {
    private val resources = SearchInvocationResources()
    private val lpResources = ArrayList<LpSearchResource>()
    private val cp = CpSearchComponent(
        (preparedSession ?: PropagationSession(
            projection,
            propagationCancellation,
            params.propagationCancelFloor,
            nativeSat = params.nativeSat ?: true,
            pbLearning = params.pbLearning ?: (problem.numIntVars == 0),
            attribution = params.propagationAttribution,
            historicalTables = params.historicalTableReasons,
            costAware = params.costAwarePropagation,
            reasonPreflight = params.reasonPreflight,
            cumulativeUnarySubsets = problem.numRealVars == 0 && params.useCumulativeUnarySubsets,
            onPropagationWork = onPropagationWork,
        )).also {
            if (params.integerRowLearning) it.enableIntegerRowLearning()
            it.configureArithmeticLearning(params.arithmeticLearningWork, params.arithmeticLearningAllocationBytes)
            it.configureIntegerRowAdmission(params.integerRowAdmission, params.integerRowReuse)
            it.configureAttribution(params.propagationAttribution, params.historicalTableReasons,
                params.costAwarePropagation, params.reasonPreflight)
        },
        branching = CpBranching.None,
    ).also {
        resources.own(AutoCloseable { it.releaseRetainedState() })
        it.conflictStats = sink?.ca
        sink?.search?.let { search ->
            search.noteInprocessing(
                if (it.session.usesNativeSat) "propagationNativeSatSessions" else "propagationGeneralSessions", 1L,
            )
            search.propagationWork = { it.session.work }
            search.learning = { it.session.learnedLifecycle() + it.session.preparationStatistics() }
            search.valueOrdering = { it.session.valueOrdering.snapshot() }
            search.rootPropagationWork = { it.session.rootWork }
            search.propagationNanos = { it.session.propagationNanos }
            search.rootPropagationNanos = { it.session.rootPropagationNanos }
        }
    }
    private val traversal = CpSatisfactionTraversalPolicy(
        cp,
        params,
        sink,
        slice,
        if (singleModel) params.leafColumns(cp.session) else null,
        rebindable,
    )
    private val completion: BacktrackCompletion
    private var run: SearchRun? = null
    private var sharedSession: SearchSession? = null
    private var learningSession: SearchSession? = null
    private var rootLp: LpFeasibilityComponent? = null
    private var rootPrepared = false
    private var initialization: Iterator<ComponentResult?>? = null
    private var deferredRoot = false

    // The verdict the root reached before any search, handed out by the first next().
    private var currentAssumptions = params.assumptions
    private var rootOutcome: SearchOutcome? = null
    val hasRootOutcome: Boolean get() = rootOutcome != null
    val preparationPending: Boolean get() = sharedSession != null && run == null && rootOutcome == null
    private var closed = false

    init {
        try {
            if (rebindable) {
                require(problem.numIntVars == 0 && problem.numRealVars == 0 &&
                    problem.factors.all { it is Clause } && !problem.hasSymmetryBreaking &&
                    !params.integerRowLearning && params.componentFactory == null &&
                    params.lpConfig == null && params.lpPlan.hasDefaultNumericalSettings())
            }
            completion = BacktrackCompletion.of(problem, cp, params, sink, solveContext, propagationCancellation, slice)
            completion.lpResource?.let {
                resources.own(it)
                lpResources.add(it)
            }
            val lp = if (params.lpConfig != null || !params.lpPlan.hasDefaultNumericalSettings()) {
                LpFeasibilityComponent(problem, cp, params, sink, solveContext).also {
                    resources.own(it)
                    lpResources.add(it)
                    rootLp = it
                }
            } else {
                null
            }
            val components = ArrayList<SearchComponent>()
            components += cp
            completion.addTo(components)
            lp?.let { components += it.component }
            components += params.componentFactory?.invoke().orEmpty()
            components.forEach(resources::ownComponent)
            resources.own(AutoCloseable {
                try {
                    params.clauseExchange?.onSearchClose(cp.session)
                } finally {
                    cp.session.sharedLearningMemory = null
                    cp.session.sharedLearningPosition = null
                }
            })
            onRelease?.let { capture -> resources.own(AutoCloseable { capture(cp.session) }) }
            cp.useEnforcingPeers(components.filterIsInstance<FiniteFactorEnforcer>()
                .flatMap { it.enforcingFiniteFactors(problem) }.toSet())
            deferredRoot = deferredInitialization &&
                (preparedSession != null || components.any { it is PreparingSearchComponent })
            val sharedCancellation = if (deferredRoot) cancelledWhen({
                if (run == null) propagationCancellation.deadline() else params.cancellation.deadline()
            }, { params.cancellation.workScheduled() }, {
                if (run == null) propagationCancellation.stopPolling else params.cancellation.stopPolling
            }) {
                if (run == null) propagationCancellation() else params.cancellation()
            } else params.cancellation
            val session = SearchComponentSet(components, branchers = listOf(traversal.brancher)).session(
                cancellation = sharedCancellation,
                learnedDb = params.sharedLearnedDb(),
                atoms = if (params.integerRowLearning) SearchAtomRegistry(problem.numBoolVars) else null,
                executionControls = SearchExecutionControlAdapter(sharedCancellation, traversal.executionAccounting),
            )
            learningSession = session
            if (params.clauseExchange != null || params.learnedMemoryBytes != null) {
                cp.session.sharedLearningMemory = session::retainedLearningMemory
                cp.session.sharedLearningPosition = session::retainedLearningPosition
            }
            val seed = preparedSeed ?: cp.session.seed(params.assumptions)
            val seeded = if (projectAssumptionCore && seed is PropagationResult.Unsat) {
                cp.session.projectSeedCore(params.assumptions, seed, propagationCancellation)
            } else seed
            cp.rebase()
            rootOutcome = if (seeded is PropagationResult.Unsat || cp.session.isUnsatAtRoot) {
                val core = (problem.baked as? PropagationResult.Unsat)?.let(::coreOf)
                val touched = (seeded as? PropagationResult.Unsat)?.conflictLevels?.let { levels ->
                    touchedToArray(IntHashSet().also { touched -> levels.forEach(touched::add) })
                } ?: emptyIntArray()
                SearchOutcome.Exhausted(core, touched)
            } else {
                null
            }
            if (rootOutcome == null) {
                if (deferredRoot || lp?.preparesRoot == true) {
                    sharedSession = session
                } else {
                    rootOutcome = when (session.initialize()) {
                        ComponentResult.Consistent -> {
                            params.clauseExchange?.onSearchStart(cp.session)
                            null
                        }
                        is ComponentResult.Conflict -> SearchOutcome.Exhausted()
                        ComponentResult.Indeterminate -> SearchOutcome.Exhausted(
                            indeterminate = true, stopCause = session.executionControls.stopCause,
                        )
                    }
                    if (rootOutcome == null) run = session.openRun(problem.numBoolVars, traversal)
                }
            }
        } catch (failure: Throwable) {
            closeAfter(failure)
            throw failure
        }
    }

    // A fixed polling cadence makes a work-bounded slice pause on the same node every run.
    var fixedCancellationCadence: Boolean = false
        set(value) {
            field = value
            run?.fixedCancellationCadence = value
        }

    fun rootFixedVariableCount(): Int = cp.session.rootFixedVariableCount()

    fun lpWork(): Long = lpResources.sumOf { it.totalSolveWork() } + (sink?.lp?.standaloneWork ?: 0L)

    val initializationCancelled: Boolean get() = cp.session.fixpointCancelled

    fun propagationWork(): Long = cp.session.work

    fun affineParityStatistics(factorId: Int): Map<String, Long> = cp.session.affineParityStatistics(factorId)

    fun splitLiteral(): Int? {
        check(rebindable && !closed)
        val (kind, variable) = cp.session.decisionAt(currentAssumptions.numBools + 1) ?: return null
        if (kind != VarKind.Bool || variable in currentAssumptions.bools) return null
        return Lit.make(variable, cp.session.boolValue(variable) ?: return null)
    }

    fun retainPartition(literal: Int): Boolean {
        check(rebindable && !closed && run != null && sharedSession == null && rootOutcome == null)
        require(!traversal.retainedRoot && splitLiteral() == literal)
        if (!checkNotNull(run).retainBooleanRoot(literal)) return false
        traversal.retainedRoot = true
        return true
    }

    fun rebind(assumptions: Assumptions) {
        check(rebindable && !closed)
        require(assumptions.numInts == 0 && assumptions.deductions.isEmpty &&
            assumptions.boolKeys.all { it in 0 until problem.numBoolVars })
        check(sharedSession == null) { "conditioned search requires completed root preparation" }
        val session = checkNotNull(learningSession)
        session.popTo(0)
        val seeded = cp.session.reseedFrom(assumptions)
        cp.rebase()
        session.resetRootFacts()
        currentAssumptions = assumptions
        val exhausted = SearchOutcome.Exhausted(touchedAssumptionLevels =
            IntArray(assumptions.numBools) { it + 1 })
        rootOutcome = if (seeded is PropagationResult.Unsat || cp.session.isUnsatAtRoot) exhausted else {
            when (session.initialize()) {
                ComponentResult.Consistent -> {
                    params.clauseExchange?.onSearchStart(cp.session)
                    null
                }
                is ComponentResult.Conflict -> exhausted
                ComponentResult.Indeterminate -> SearchOutcome.Exhausted(
                    indeterminate = true, stopCause = session.executionControls.stopCause,
                )
            }
        }
        traversal.resetScope()
        run?.reset()
        if (run == null && rootOutcome == null) run = session.openRun(problem.numBoolVars, traversal)
    }

    // Null when a pausable traversal reached its slice boundary with search pending. Terminal outcomes close the LP
    // resources; a model releases their persistent solvers instead, since a lazy caller may abandon the traversal
    // after it. Rebindable Boolean queries retain the prepared state until their owner closes it.
    fun next(): SearchOutcome? {
        check(!closed) { "traversal is closed" }
        sharedSession?.let { session ->
            if (!rootPrepared) {
                rootPrepared = true
                if (rootLp?.prepareRoot() == true) rootOutcome = SearchOutcome.Exhausted()
                cp.rebase()
            }
            if (rootOutcome == null) {
                if (params.cancellation() && slice?.pauses() == true) return null
                val result = if (deferredRoot) {
                    val pending = initialization ?: session.prepareInitialization { component, context ->
                        when (component) {
                            is CpSearchComponent -> component.prepareInitialization(context)
                            is PreparingSearchComponent -> component.prepareInitialization(context)
                            else -> sequence { yield(component.initialize(context)) }.iterator()
                        }
                    }.also { initialization = it }
                    var initialized: ComponentResult? = null
                    while (initialized == null) {
                        if (params.cancellation() && slice?.pauses() == true) return null
                        initialized = pending.next()
                    }
                    initialization = null
                    initialized
                } else session.initialize()
                rootOutcome = when (result) {
                    ComponentResult.Consistent -> {
                        params.clauseExchange?.onSearchStart(cp.session)
                        null
                    }
                    is ComponentResult.Conflict -> SearchOutcome.Exhausted()
                    ComponentResult.Indeterminate -> SearchOutcome.Exhausted(
                        indeterminate = true, stopCause = session.executionControls.stopCause,
                    )
                }
                if (rootOutcome == null) {
                    run = session.openRun(problem.numBoolVars, traversal).also {
                        it.fixedCancellationCadence = fixedCancellationCadence
                    }
                }
            }
            sharedSession = null
            if (rootOutcome == null && params.cancellation() && slice?.pauses() == true) return null
        }
        rootOutcome?.let { outcome ->
            rootOutcome = null
            finishQuery()
            return outcome
        }
        traversal.clauseExchangeEnded = false
        return when (val event = checkNotNull(run).next()) {
            is SearchRunEvent.Satisfied -> {
                val sample = completion.sample(event.model, cp)
                traversal.brancher.onSolution(sample)
                releaseLpResources(lpResources)
                SearchOutcome.Found(sample)
            }

            SearchRunEvent.Exhausted -> {
                finishQuery()
                // Learned decision levels describe traversal, not every premise of the exhausted tree.
                SearchOutcome.Exhausted(touchedAssumptionLevels = IntArray(
                    currentAssumptions.numBools + currentAssumptions.numInts +
                        (if (traversal.retainedRoot) 1 else 0),
                ) { it + 1 })
            }

            SearchRunEvent.Indeterminate.Component -> {
                val cause = run?.componentStop?.stopCause ?: learningSession?.executionControls?.stopCause
                finishQuery()
                SearchOutcome.Exhausted(indeterminate = true, stopCause = cause)
            }

            SearchRunEvent.Paused -> if (slice != null) {
                if (!traversal.clauseExchangeEnded) params.clauseExchange?.onSearchPause(cp.session)
                null
            } else {
                val cause = learningSession?.executionControls?.stopCause
                finishQuery()
                SearchOutcome.BudgetCapped(cause)
            }

            SearchRunEvent.Indeterminate.Budget,
            SearchRunEvent.Indeterminate.Cancelled,
            -> {
                val cause = learningSession?.executionControls?.stopCause
                finishQuery()
                SearchOutcome.BudgetCapped(cause)
            }
        }
    }

    private fun finishQuery() {
        if (!rebindable) close()
    }

    override fun close() {
        if (closed) return
        closed = true
        initialization = null
        run = null
        sharedSession = null
        try {
            resources.close()
        } finally {
            learningSession?.releaseLearning()
            learningSession = null
            traversal.releaseRetainedState()
        }
    }

    fun closeAfter(failure: Throwable) {
        if (closed) return
        closed = true
        initialization = null
        run = null
        sharedSession = null
        try {
            resources.closeAfter(failure)
        } finally {
            learningSession?.releaseLearning()
            learningSession = null
            traversal.releaseRetainedState()
        }
    }
}

@Suppress("TooGenericExceptionCaught") // all owned resources must be attempted even when release fails
private fun releaseLpResources(resources: List<LpSearchResource>) {
    var failure: Throwable? = null
    for (resource in resources) {
        try {
            resource.releasePersistentSolvers()
        } catch (releaseFailure: Throwable) {
            failure?.addSuppressed(releaseFailure) ?: run { failure = releaseFailure }
        }
    }
    failure?.let { throw it }
}

private class CpSatisfactionTraversalPolicy(
    private val cp: CpSearchComponent,
    private val params: BacktrackParams,
    private val sink: SolveStatsSink?,
    private val slice: TraversalSlice?,
    leafColumns: LeafColumns?,
    private val rebindable: Boolean,
) : SearchTraversalPolicy,
    SearchRunLifecycle {
    private val restart = RestartSchedule.from(params)
    var retainedRoot = false
    var clauseExchangeEnded = false

    private val session: PropagationSession get() = cp.session
    val brancher = BacktrackBrancher(
        session, params, sink, restart,
        nativeConflict = { cp.lastConflict },
        leafColumns = leafColumns,
    ).also {
        cp.propagationObserver = it::onPropagation
        cp.cascadeConflictObserver = it::onCascadeConflict
    }

    override val solveParams = SearchSolveParams(
        maxDecisions = minOf(
            params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE,
            params.nodeBudget?.allowance?.decisionsLeft() ?: Long.MAX_VALUE,
        ),
        restart = restart,
    )
    override val booleanBranching = BooleanBranching.None
    val executionAccounting: SearchExecutionAccounting = params.nodeBudget?.takeIf { rebindable }?.let { budget ->
        object : SearchExecutionAccounting {
            override fun canCommitDecision(): Boolean = !budget.exhausted()
            override fun recordDecision(decision: SearchDecision): Boolean {
                budget.spend()
                return true
            }
            override val stopCause: SearchStopCause? get() =
                if (budget.exhausted()) SearchStopCause.DecisionAllowance else null
        }
    } ?: SearchExecutionAccounting.Unlimited
    override val decisionBudget = SearchDecisionBudget {
        params.nodeBudget?.allowance?.let { budget ->
            if (!rebindable) budget.spend()
            !budget.exhausted()
        } ?: true
    }
    override val observer: SearchRunObserver = brancher
    override val modelContinuation = if (rebindable) SearchModelContinuation.Chronological
    else SearchModelContinuation.BlockAtRoot
    override val modelPolicy: SearchModelPolicy = SearchModelPolicy.SurfaceAll
    override val pauseBeforeDecision: () -> Boolean = slice?.beforeBranch ?: { false }
    override val nodePolicy: SearchNodePolicy = object : SearchNodePolicy {
        override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
            if (slice?.beforeBranch?.invoke() == true) return SearchNodeDisposition.Pause
            return when (simplify(context, false)) {
                SearchRunDisposition.Continue -> SearchNodeDisposition.Expand
                SearchRunDisposition.Exhausted -> SearchNodeDisposition.Prune
                SearchRunDisposition.Pause -> SearchNodeDisposition.Pause
                SearchRunDisposition.Indeterminate -> SearchNodeDisposition.Indeterminate
            }
        }
    }
    override val lifecycle: SearchRunLifecycle get() = this

    private val inprocessing = Inprocessing.from(params, sink?.search)
    private val learnedMemory = LearnedMemoryMaintenance()

    init {
        session.retainedInprocessingMemory = inprocessing?.let { it::retainedMemory }
    }

    fun releaseRetainedState() {
        inprocessing?.reset()
    }

    fun resetScope() {
        retainedRoot = false
        inprocessing?.reset()
        learnedMemory.reset()
    }

    private val pooledIncumbents = params.pooledIncumbents?.let { IncumbentSubscription(it) }

    override fun onResume(context: SearchContext): SearchRunDisposition {
        val cursor = session.nativeCapContinuationCursor
        val heap = session.nativeCapContinuationHeap
        val operation = session.nativeCapContinuationOperation
        if (context.decisionLevel == 0 && slice != null && session.nativeCapWindowPending) {
            forgetIfOverCap(session, params, inprocessing?.retainsPublication == true)
        }
        val simplified = simplify(context, false)
        return if (simplified == SearchRunDisposition.Continue) capContinuation(cursor, heap, operation) else simplified
    }

    // A pending window that advanced takes its next bounded attempt inside the same slice. Yielding the slice at
    // every restart would split the search into segments too short to earn their share of the schedule.
    private fun capContinuation(cursor: Long, heap: Long, operation: Long): SearchRunDisposition {
        var seenCursor = cursor
        var seenHeap = heap
        var seenOperation = operation
        while (session.nativeCapWindowPending && slice != null &&
            (session.nativeCapContinuationCursor != seenCursor || session.nativeCapContinuationHeap != seenHeap ||
                session.nativeCapContinuationOperation != seenOperation)
        ) {
            if (params.cancellation.pollStop()) {
                return if (slice.pauses()) SearchRunDisposition.Pause else SearchRunDisposition.Continue
            }
            seenCursor = session.nativeCapContinuationCursor
            seenHeap = session.nativeCapContinuationHeap
            seenOperation = session.nativeCapContinuationOperation
            forgetIfOverCap(session, params, inprocessing?.retainsPublication == true)
        }
        return SearchRunDisposition.Continue
    }

    private fun simplify(context: SearchContext, restart: Boolean): SearchRunDisposition {
        learnedMemory.run(session, params, inprocessing, sink?.search)
        if (context.decisionLevel != 0) return SearchRunDisposition.Continue
        val refresh = if (restart) inprocessing?.onRestart(session, params) else inprocessing?.onRoot(session, params)
        if (session.isUnsatAtRoot) return SearchRunDisposition.Exhausted
        if (refresh != true) return rootDisposition()
        val result = checkNotNull(inprocessing).refreshRoot(cp, context, params)
        if (result == null) {
            return if (slice != null) SearchRunDisposition.Pause else SearchRunDisposition.Indeterminate
        }
        if (result is ComponentResult.Conflict) return SearchRunDisposition.Exhausted
        if (result == ComponentResult.Indeterminate) {
            return if (slice?.pauses?.invoke() == true) {
                SearchRunDisposition.Pause
            } else {
                SearchRunDisposition.Indeterminate
            }
        }
        return rootDisposition()
    }

    private fun rootDisposition(): SearchRunDisposition {
        val observer = slice?.atRoot ?: return SearchRunDisposition.Continue
        return if (session.canSimplifyRoot && observer(session)) SearchRunDisposition.Pause
        else SearchRunDisposition.Continue
    }

    override fun onRestart(context: SearchContext): SearchRunDisposition {
        params.clauseExchange?.onRestart(session)
        val cursor = session.nativeCapContinuationCursor
        val heap = session.nativeCapContinuationHeap
        val operation = session.nativeCapContinuationOperation
        forgetIfOverCap(session, params, inprocessing?.retainsPublication == true)
        val simplified = simplify(context, true)
        if (simplified != SearchRunDisposition.Continue) return simplified
        pooledIncumbents?.poll()?.let { brancher.importPooledSolution(it.assignment, replay = true) }
        return capContinuation(cursor, heap, operation)
    }

    override fun onCancellation(context: SearchContext): SearchRunDisposition {
        clauseExchangeEnded = true
        params.clauseExchange?.onSearchEnd(session)
        return if (slice?.pauses() == true) SearchRunDisposition.Pause else SearchRunDisposition.Indeterminate
    }
}

internal fun BacktrackParams.sharedLearnedDb(): SearchLearnedDbParams =
    SearchLearnedDbParams(maxClauses = maxLearnedClauses, glueLbd = lbdGlueThreshold,
        softLimitBytes = learnedMemoryBytes, reclamationWork = learnedMemoryWork,
        reclamationScratchBytes = learnedMemoryScratchBytes)
