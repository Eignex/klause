package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.boundsMidpoint
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.values
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.CpBranching
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentSubscription
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.UnsatCore
import com.eignex.klause.solver.result.projectSeedConflictToAssumptions
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchDecisionBudget
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
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.SearchTraversalPolicy
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntHashSet
import kotlin.random.Random

/** Map touched-seed-level [IntArray] to the subset of [input] assumptions at those
 *  levels. Returns `null` when the input was empty (no assumption layer to
 *  project) or no level was touched (no information). */
internal fun BacktrackSolver.projectTouchedToAssumptions(input: Assumptions, levels: IntArray): Assumptions? {
    if (input.isEmpty || levels.isEmpty()) return null
    // [levels] is already the touched seed-level array; the projection is idempotent over
    // duplicates, so pass it straight through (no dedup set needed).
    return projectSeedConflictToAssumptions(input, levels)
}

/** Convert a touched-seed-level set into a sorted-ascending [IntArray], or empty
 *  when there were no touches (or no seed in the first place). */
internal fun touchedToArray(touched: IntHashSet?): IntArray {
    if (touched == null || touched.isEmpty()) return EmptyIntArray
    val out = touched.toIntArray()
    out.sort()
    return out
}

/** Lift a [PropagationResult.Unsat]'s factor-level conflict info to a klause [UnsatCore].
 *  Empty `conflictFactors` (seed-only contradiction, no factor invocation involved)
 *  collapses to `null` — the API contract is "core absent" rather than "core empty",
 *  since an empty core wouldn't be actionable. */
internal fun coreOf(unsat: PropagationResult.Unsat): UnsatCore? = if (unsat.conflictFactors.isEmpty()) {
    null
} else {
    UnsatCore.of(unsat.conflictFactors)
}

internal sealed interface SearchOutcome {
    data class Found(val sample: Sample) : SearchOutcome

    /** DFS exhausted without finding a model. [core] is non-null when the exhaustion
     *  was forced by root-level propagation (bake or seed); after a full DFS-tree
     *  walk, no single-factor core explains the result and [core] stays null.
     *  [touchedAssumptionLevels] is the union of seed-level decision levels that
     *  appeared in any conflict's learned-clause decision-level set during the
     *  search — feeds the assumption-core projection in
     *  [com.eignex.klause.solver.result.satisfyUnderAssumptions]. Empty when no seed was
     *  in play or no conflict referenced a seed level. */
    data class Exhausted(
        val core: UnsatCore? = null,
        val touchedAssumptionLevels: IntArray = EmptyIntArray,
        /** True when a leaf was reached whose residual LP-only continuous relaxation could not be
         *  certified feasible or infeasible (see `leafRealFeasibility`), so the tree is not provably
         *  all-infeasible — the terminal verdict must be `unknown`, not UNSAT. */
        val indeterminate: Boolean = false,
    ) : SearchOutcome
    data object BudgetCapped : SearchOutcome
}

/** Lazy shared-session outcomes for satisfaction, enumeration, and sampling. */
@Suppress("TooGenericExceptionCaught") // lazy boundaries must preserve arbitrary primary and cleanup failures
internal fun BacktrackSolver.driveSearch(
    params: BacktrackParams,
    sink: SolveStatsSink? = null,
): Sequence<SearchOutcome> = sequence {
    val traversal = CpSatisfactionTraversal(problem, params, sink, lpSolveContext)
    while (true) {
        val outcome = try {
            checkNotNull(traversal.next()) { "an unsliced traversal never pauses" }
        } catch (failure: Throwable) {
            traversal.closeAfter(failure)
            throw failure
        }
        yield(outcome)
        if (outcome !is SearchOutcome.Found) return@sequence
    }
}

/**
 * How a pausable [CpSatisfactionTraversal] meets its slice boundary: whether a fired cancellation is the slice
 * ending rather than the run, and the hook each node runs before it branches.
 */
internal class TraversalSlice(val pauses: () -> Boolean, val beforeBranch: () -> Unit)

/**
 * Satisfaction path for a CP/theory search, advanced one outcome at a time.
 *
 * This has no private DFS trail: [CpSearchComponent] and every supplied theory component are driven by
 * [com.eignex.klause.solver.search.SearchRun]. With a [slice] the traversal pauses where the slice fires — a
 * cancellation that [TraversalSlice.pauses] reports as the slice's own makes [next] return null at decision
 * granularity, and the next [next] resumes mid-tree. Propagation polls [propagationCancellation] alone, since a
 * fixpoint a slice cut would leave the session under-propagated for the arm that resumes it.
 */
@Suppress("TooGenericExceptionCaught") // ownership boundaries must preserve arbitrary primary and cleanup failures
internal class CpSatisfactionTraversal(
    private val problem: BakedProblem,
    params: BacktrackParams,
    sink: SolveStatsSink?,
    solveContext: LpSolveContext,
    propagationCancellation: Cancellation = params.cancellation,
    private val slice: TraversalSlice? = null,
) {
    private val lpResources = ArrayList<LpSearchResource>()
    private val cp = CpSearchComponent(
        PropagationSession(
            problem,
            propagationCancellation,
            params.propagationCancelFloor,
            nativeSat = params.nativeSat ?: true,
            pbLearning = params.pbLearning ?: true,
        ),
        branching = CpBranching.None,
    ).also { it.conflictStats = sink?.ca }
    private val traversal = CpSatisfactionTraversalPolicy(
        cp.session,
        params,
        sink,
        seedDecisionLevels = params.assumptions.boolKeys.size + params.assumptions.intKeys.size,
        slice,
    )
    private lateinit var completion: BacktrackCompletion
    private var run: SearchRun? = null

    // The verdict the root reached before any search, handed out by the first [next].
    private var rootOutcome: SearchOutcome? = null
    private var closed = false

    init {
        try {
            completion = BacktrackCompletion.of(problem, cp, params, sink, solveContext)
            completion.lpResource?.let(lpResources::add)
            val lp = if (params.lpConfig != null) {
                LpFeasibilityComponent(problem, cp, params, sink, solveContext).also(lpResources::add)
            } else {
                null
            }
            val components = ArrayList<SearchComponent>()
            components += cp
            completion.addTo(components)
            lp?.let { components += it.component }
            components += params.componentFactory?.invoke().orEmpty()
            val session = SearchComponentSet(components, branchers = listOf(traversal.brancher)).session(
                cancellation = params.cancellation,
                learnedDb = params.sharedLearnedDb(),
            )
            val seeded = cp.session.seed(params.assumptions)
            cp.rebase()
            rootOutcome = if (seeded is PropagationResult.Unsat || cp.session.isUnsatAtRoot) {
                val core = (problem.baked as? PropagationResult.Unsat)?.let(::coreOf)
                val touched = (seeded as? PropagationResult.Unsat)?.conflictLevels?.let { levels ->
                    touchedToArray(IntHashSet().also { touched -> levels.forEach(touched::add) })
                } ?: EmptyIntArray
                SearchOutcome.Exhausted(core, touched)
            } else {
                when (session.initialize()) {
                    ComponentResult.Consistent -> null
                    is ComponentResult.Conflict -> SearchOutcome.Exhausted()
                    ComponentResult.Indeterminate -> SearchOutcome.BudgetCapped
                }
            }
            if (rootOutcome == null) run = session.openRun(problem.numBoolVars, traversal)
        } catch (failure: Throwable) {
            closeAfter(failure)
            throw failure
        }
    }

    /** Poll cancellation on a fixed cadence, so a slice bounded by work pauses on the same node every run. */
    var fixedCancellationCadence: Boolean
        get() = run?.fixedCancellationCadence ?: false
        set(value) {
            run?.fixedCancellationCadence = value
        }

    /** Variables the traversal's search has fixed at its root; see [PropagationSession.rootFixedVariableCount]. */
    fun rootFixedVariableCount(): Int = cp.session.rootFixedVariableCount()

    /** LP work the traversal's relaxations have done, for a slice that charges it against its budget. */
    fun lpWork(): Long = lpResources.sumOf { it.totalSolveWork() }

    /**
     * Advance to the next outcome, or null when a pausable traversal reached its slice boundary with search still
     * pending. Every outcome but [SearchOutcome.Found] is terminal and closes the traversal's LP resources; a model
     * releases their persistent solvers instead, since a lazy caller may abandon the traversal after it.
     */
    fun next(): SearchOutcome? {
        check(!closed) { "traversal is closed" }
        rootOutcome?.let { outcome ->
            rootOutcome = null
            close()
            return outcome
        }
        return when (val event = checkNotNull(run).next()) {
            is SearchRunEvent.Satisfied -> {
                val sample = completion.sample(event.model, cp)
                traversal.brancher.onSolution(sample)
                releaseLpResources(lpResources)
                SearchOutcome.Found(sample)
            }

            SearchRunEvent.Exhausted -> {
                close()
                SearchOutcome.Exhausted(touchedAssumptionLevels = traversal.brancher.touchedAssumptionLevels())
            }

            SearchRunEvent.Indeterminate.Component -> {
                close()
                SearchOutcome.Exhausted(indeterminate = true)
            }

            SearchRunEvent.Paused -> if (slice != null) {
                null
            } else {
                close()
                SearchOutcome.BudgetCapped
            }

            SearchRunEvent.Indeterminate.Budget,
            SearchRunEvent.Indeterminate.Cancelled,
            -> {
                close()
                SearchOutcome.BudgetCapped
            }
        }
    }

    /** Close the LP resources; idempotent. */
    fun close() {
        if (closed) return
        closed = true
        closeLpResources(lpResources)
    }

    /** Close after [failure], keeping it primary. */
    fun closeAfter(failure: Throwable) {
        try {
            close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
    }
}

private interface LpSearchResource : AutoCloseable {
    fun releasePersistentSolvers()

    fun totalSolveWork(): Long
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

@Suppress("TooGenericExceptionCaught") // all owned resources must be attempted even when close fails
private fun closeLpResources(resources: List<LpSearchResource>) {
    var failure: Throwable? = null
    for (resource in resources) {
        try {
            resource.close()
        } catch (closeFailure: Throwable) {
            failure?.addSuppressed(closeFailure) ?: run { failure = closeFailure }
        }
    }
    failure?.let { throw it }
}

/** CP-specific wiring around the shared traversal engine for satisfaction and model streaming. */
private class CpSatisfactionTraversalPolicy(
    private val session: PropagationSession,
    private val params: BacktrackParams,
    sink: SolveStatsSink?,
    seedDecisionLevels: Int,
    private val slice: TraversalSlice?,
) : SearchTraversalPolicy,
    SearchRunLifecycle {
    private val restart = RestartSchedule.from(params)

    val brancher = BacktrackBrancher(session, params, sink, restart, seedDecisionLevels = seedDecisionLevels)

    override val solveParams = SearchSolveParams(
        maxDecisions = minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE),
        restart = restart,
    )
    override val booleanBranching = BooleanBranching.None
    override val decisionBudget = SearchDecisionBudget {
        params.nodeBudget?.let { budget ->
            budget.spend()
            !budget.exhausted()
        } ?: true
    }
    override val observer: SearchRunObserver = brancher
    override val modelContinuation = SearchModelContinuation.BlockAtRoot
    override val modelPolicy: SearchModelPolicy = SearchModelPolicy.SurfaceAll
    override val nodePolicy: SearchNodePolicy = slice?.let { s ->
        object : SearchNodePolicy {
            override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
                s.beforeBranch()
                return SearchNodeDisposition.Expand
            }
        }
    } ?: SearchNodePolicy.ExpandAll
    override val lifecycle: SearchRunLifecycle get() = this

    private val inprocessing = Inprocessing.from(params, sink?.search)

    private val pooledIncumbents = params.pooledIncumbents?.let { IncumbentSubscription(it) }

    override fun onRestart(context: SearchContext): SearchRunDisposition {
        params.clauseExchange?.onRestart(session)
        forgetIfOverCap(session, params)
        inprocessing?.onRestart(session, params)
        pooledIncumbents?.poll()?.let { brancher.importPooledSolution(it.assignment) }
        return SearchRunDisposition.Continue
    }

    override fun onCancellation(context: SearchContext): SearchRunDisposition {
        params.clauseExchange?.onSearchEnd(session)
        return if (slice?.pauses() == true) SearchRunDisposition.Pause else SearchRunDisposition.Indeterminate
    }
}

internal class BacktrackBrancher(
    private val session: PropagationSession,
    params: BacktrackParams,
    private val sink: SolveStatsSink?,
    private val restart: RestartSchedule,
    private val branching: BacktrackBranching = BacktrackBranching.Selectors,
    private val seedDecisionLevels: Int = 0,
) : SearchBrancher,
    SearchRunObserver {
    // Node count from which the next restart may count root fixings. The count visits every variable, so it runs
    // at most once per that many nodes: one visit per node, amortized.
    private var nextRootCount = 0L
    private val variables = params.variableSelector.fresh()
    private val values = params.valueSelector.fresh()
    private val phase = PhaseSaving(session.problem.numBoolVars, session.problem.numIntVars, params)
    private val rng = Random(params.randomSeed ?: Random.Default.nextLong())
    private val onEvent = params.onEvent
    private var restartCount = 0L
    private val touchedSeedLevels = IntHashSet()

    init {
        if (variables.tracksUnassign) {
            val numBool = session.problem.numBoolVars
            session.unassignListener = { encoded ->
                variables.onUnassign(if (encoded < numBool) VarRef.Bool(encoded) else VarRef.IntVar(encoded - numBool))
            }
        }
    }

    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        val variable = branching.pick(session)
            ?.takeIf(::isOpen)
            ?: variables.pick(session, rng)
            ?: return null
        if (restart.phaseMode() != PhaseMode.UNMANAGED) phase.setManagedMode(restart.phaseMode())
        val ordered = branching.orderValues(
            variable,
            phase.applyPhase(variable, values.values(session, variable, rng), rng),
        )
        val alternatives = when (variable) {
            is VarRef.Bool -> ordered.map { value ->
                require(value == 0L || value == 1L) { "Boolean selector produced $value" }
                SearchDecision.Bool(Lit.make(variable.varId, value != 0L))
            }.toList()

            // A probing selector offers nothing once it has refuted every value, so the node is dead. Null
            // would claim every column is fixed and surface the open domains' minima as a model.
            is VarRef.IntVar -> ordered.firstOrNull()?.let { splitIntAlternatives(session, variable, it) }
                ?: emptyList()
        }
        if (session.probeCancelled) {
            session.probeCancelled = false
            context.requestCancellationPoll()
        }
        return alternatives
    }

    private fun isOpen(variable: VarRef): Boolean = when (variable) {
        is VarRef.Bool -> session.boolValue(variable.varId) == null
        is VarRef.IntVar -> !session.intDomain(variable.varId).isFixed
    }

    override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
        val (variable, value) = decision.asSelectorDecision() ?: return
        variables.onCommit(variable)
        values.onCommit(variable, value)
        phase.capture(variable, session)
        phase.captureTargetIfDeeper(session, decisionLevel)
        sink?.search?.observeNode(decisionLevel)
    }

    override fun onConflict(decision: SearchDecision?) {
        sink?.search?.observeFail()
        val (variable, value) = decision?.asSelectorDecision() ?: return
        variables.onConflict(variable)
        values.onConflict(variable, value)
        phase.onConflictTick()
    }

    override fun onLearnedConflict(conflict: com.eignex.klause.solver.search.SearchLearnedConflict) {
        sink?.search?.observeLearned(conflict.guardLiterals, conflict.lbd)
        for (level in conflict.decisionLevels) {
            if (level in 1..seedDecisionLevels) touchedSeedLevels.add(level)
        }
    }

    override fun onRestart(decisions: Long) {
        variables.onRestart()
        values.onRestart()
        sink?.search?.observeRestart()
        sink?.search?.let { search ->
            if (search.nodeCount >= nextRootCount) {
                search.observeRootFixed(session.fixedVariableCount())
                nextRootCount = search.nodeCount + session.problem.numBoolVars + session.problem.numIntVars
            }
        }
        onEvent?.invoke(SearchEvent.Restart(++restartCount, decisions))
    }

    fun onSolution(sample: Sample) {
        variables.onSolution(sample)
        values.onSolution(sample)
        phase.onSolution(sample)
    }

    fun importPooledSolution(sample: Sample) {
        phase.onSolution(sample)
    }

    fun touchedAssumptionLevels(): IntArray {
        if (touchedSeedLevels.isEmpty()) return EmptyIntArray
        return touchedSeedLevels.toIntArray().also(IntArray::sort)
    }
}

internal fun splitIntAlternatives(
    session: PropagationSession,
    variable: VarRef.IntVar,
    preferred: Long,
): List<SearchDecision> {
    val domain = session.intDomain(variable.varId)
    check(domain.min < domain.max) { "selector chose fixed integer ${variable.varId}" }
    val split = when {
        (domain.spanOrNull() == null) && preferred > domain.min && preferred < domain.max -> preferred
        (domain.spanOrNull() == null) -> boundsMidpoint(domain)
        preferred >= domain.max -> domain.max - 1
        else -> maxOf(preferred, domain.min)
    }
    val lower = SearchDecision.IntAtMost(variable.varId, split)
    val upper = SearchDecision.IntAtLeast(variable.varId, split + 1)
    return if (preferred <= split) listOf(lower, upper) else listOf(upper, lower)
}

/** Optional search-only branch guidance layered over the configured selectors. */
internal interface BacktrackBranching {
    fun pick(session: PropagationSession): VarRef? = null

    fun orderValues(variable: VarRef, values: Sequence<Long>): Sequence<Long> = values

    data object Selectors : BacktrackBranching
}

private fun SearchDecision.asSelectorDecision(): Pair<VarRef, Long>? = when (this) {
    is SearchDecision.Bool -> VarRef.Bool(literal ushr 1) to if (literal and 1 == 0) 1L else 0L
    is SearchDecision.IntAtMost -> VarRef.IntVar(variable) to upper
    is SearchDecision.IntAtLeast -> VarRef.IntVar(variable) to lower
    is SearchDecision.IntEqual -> VarRef.IntVar(variable) to value
    is SearchDecision.Theory -> null
}

private sealed interface BacktrackCompletion {
    val lpResource: LpSearchResource? get() = null

    fun addTo(components: MutableList<SearchComponent>)

    fun sample(model: com.eignex.klause.solver.search.AssembledSearchModel, cp: CpSearchComponent): Sample

    data object Discrete : BacktrackCompletion {
        override fun addTo(components: MutableList<SearchComponent>) = Unit

        override fun sample(
            model: com.eignex.klause.solver.search.AssembledSearchModel,
            cp: CpSearchComponent,
        ): Sample = checkNotNull(model.valueOf(cp))
    }

    class ResidualReal(private val component: ResidualRealComponent) : BacktrackCompletion {
        override val lpResource: LpSearchResource get() = component

        override fun addTo(components: MutableList<SearchComponent>) {
            components += component
        }

        override fun sample(
            model: com.eignex.klause.solver.search.AssembledSearchModel,
            cp: CpSearchComponent,
        ): Sample = component.sample()
    }

    companion object {
        fun of(
            problem: Problem,
            cp: CpSearchComponent,
            params: BacktrackParams,
            sink: SolveStatsSink?,
            solveContext: LpSolveContext,
        ): BacktrackCompletion = if (problem.numRealVars == 0) {
            Discrete
        } else {
            ResidualReal(ResidualRealComponent(problem, cp, params, sink, solveContext))
        }
    }
}

/**
 * The LP relaxation as a satisfaction-path participant: refute a node whose relaxation is infeasible.
 *
 * The optimize path reaches the relaxation through [ResumableMinimize], which owns an [LpEngine] to bound
 * against an incumbent. A satisfaction search has no incumbent and no objective to bound, but that is not
 * the only thing a relaxation decides — an infeasible one refutes its node whatever the objective is, and
 * on real models that is where most of the pruning comes from. Without this component the relaxation is
 * unreachable on a model with no objective, however the arm is configured.
 *
 * The engine is handed a zero objective, so the bound arms it also carries can never prune: the
 * lower bound of a zero objective is zero and the bound to beat is infinite. Only the infeasibility
 * prune fires, which is what a satisfaction node can act on.
 */
private class LpFeasibilityComponent(
    problem: Problem,
    private val cp: CpSearchComponent,
    params: BacktrackParams,
    sink: SolveStatsSink?,
    solveContext: LpSolveContext,
) : LpSearchResource {
    val engine = LpEngine(
        problem,
        LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
        LpParams(
            lpPlan = params.lpPlan,
            lpConfig = params.lpConfig,
            cancellation = params.cancellation,
            solveBudgetMillis = params.solveBudgetMillis,
            randomSeed = params.randomSeed,
            zeroObjectivePricing = params.zeroObjectivePricing,
        ),
        sink ?: SolveStatsSink(backend = "backtrack"),
        solveContext,
    )

    val component: SearchComponent = engine.propagator.also { engine.cpAdapter.attach(cp.session, feasibility = true) }

    override fun releasePersistentSolvers() = engine.releasePersistentSolvers()

    override fun totalSolveWork(): Long = engine.totalSolveWork()

    override fun close() = engine.close()
}

private class ResidualRealComponent(
    private val problem: Problem,
    private val cp: CpSearchComponent,
    params: BacktrackParams,
    sink: SolveStatsSink?,
    solveContext: LpSolveContext,
) : SearchComponent,
    LpSearchResource {
    private val toleranceCheck = params.toleranceCheck
    private val engine = LpEngine(
        problem,
        LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
        LpParams(
            lpPlan = params.lpPlan.copy(bounding = true, learn = true, realResidual = true),
            lpConfig = params.lpConfig,
            cancellation = params.cancellation,
            solveBudgetMillis = params.solveBudgetMillis,
            randomSeed = params.randomSeed,
            zeroObjectivePricing = params.zeroObjectivePricing,
        ),
        sink ?: SolveStatsSink(backend = "backtrack"),
        solveContext,
    )
    private var completed: Sample? = null

    override fun check(context: SearchContext): ComponentCheck {
        completed = null
        if (context.cancelled()) return ComponentCheck.Indeterminate
        val result = engine.leafCertify(cp.session, toleranceCheck)
        return when (result.verdict) {
            LpVerdict.FEASIBLE,
            LpVerdict.ATTAINED_OPTIMUM,
            LpVerdict.TOLERANCE_OPTIMUM,
            LpVerdict.UNBOUNDED,
            -> ComponentCheck.Feasible.also {
                completed = Sample(
                    BooleanArray(problem.numBoolVars) { cp.session.boolValue(it) ?: false },
                    LongArray(problem.numIntVars) { cp.session.intDomain(it).min },
                    result.reals,
                    result.exactReals,
                )
            }

            LpVerdict.INFEASIBLE -> ComponentCheck.Infeasible()

            LpVerdict.INDETERMINATE, LpVerdict.CERTIFIED_BOUND -> ComponentCheck.Indeterminate
        }
    }

    override fun retract(decisionLevel: Int) {
        completed = null
    }

    fun sample(): Sample = requireNotNull(completed)

    override fun releasePersistentSolvers() = engine.releasePersistentSolvers()

    override fun totalSolveWork(): Long = engine.totalSolveWork()

    override fun close() = engine.close()
}

/** The shared learned-clause bound mirrors the CP database's own cap and glue threshold. */
internal fun BacktrackParams.sharedLearnedDb(): SearchLearnedDbParams =
    SearchLearnedDbParams(maxClauses = maxLearnedClauses, glueLbd = lbdGlueThreshold)
