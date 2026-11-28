package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.boundsMidpoint
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.LeafColumns
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.BranchingConflict
import com.eignex.klause.solver.search.SearchBranchAttemptObserver
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

// Columns no factor declares and the objective gives no term, completed at leaves. Only CP factors declare what
// they read, while a componentFactory component may read any column through the shared trail, so a search with
// such components decides every column (null).
internal fun BacktrackParams.leafColumns(
    session: PropagationSession,
    objective: LinearObjective? = null,
): LeafColumns? {
    val unread = (if (componentFactory == null) session.projection.unreadColumns else null) ?: return null
    return if (objective == null) unread
    else unread.withoutTerms(objective.boolWeightValues, objective.intCoefficientValues)
}

internal class BacktrackBrancher(
    private val session: PropagationSession,
    params: BacktrackParams,
    private val sink: SolveStatsSink?,
    private val restart: RestartSchedule,
    private val branching: BacktrackBranching = BacktrackBranching.Selectors,
    private val nativeConflict: () -> PropagationResult.Unsat? = { null },
    leafColumns: LeafColumns? = null,
) : SearchBrancher,
    SearchBranchAttemptObserver {
    // Node count from which the next restart may count root fixings. The count visits every variable, so it runs
    // at most once per that many nodes: one visit per node, amortized.
    private var nextRootCount = 0L
    private val variables = params.variableSelector.fresh()
    private val values = params.valueSelector.fresh()
    private val phase = PhaseSaving(session.problem.numBoolVars, session.problem.numIntVars, params)
    private val rng = Random(params.randomSeed ?: Random.Default.nextLong())
    private val onEvent = params.onEvent
    private val branchObserver = params.branchObserver
    private var restartCount = 0L

    // A replayed point leads every value order, restarts included, until a decision it led conflicts.
    private var hint: Sample? = null
    private val hinted = HashSet<SearchDecision>()

    init {
        session.completeAtLeaves(leafColumns)
        variables.onSessionCreated(session)
        if (params.phaseSaving || params.targetPhasing) {
            session.retractingListener = { phase.capture(it, session) }
        }
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
        branching.onBranch(variable)
        val preferred = branching.orderValues(
            variable,
            phase.applyPhase(variable, values.values(session, variable, rng), rng),
        )
        val replayed = hint?.let { hintedValue(variable, it) }
        val ordered = if (replayed == null) preferred else sequenceOf(replayed) + preferred.filter { it != replayed }
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
        if (replayed != null) alternatives.firstOrNull()?.let(hinted::add)
        if (session.probeCancelled) {
            session.probeCancelled = false
            context.requestCancellationPoll()
        }
        return if (replayed == null) branching.orderAlternatives(variable, alternatives) else alternatives
    }

    private fun isOpen(variable: VarRef): Boolean = when (variable) {
        is VarRef.Bool -> session.needsBoolDecision(variable.varId)
        is VarRef.IntVar -> session.needsIntDecision(variable.varId)
    }

    private fun hintedValue(variable: VarRef, point: Sample): Long? = when (variable) {
        is VarRef.Bool -> if (point.boolValue(variable.varId)) 1L else 0L
        is VarRef.IntVar -> point.intValue(variable.varId).takeIf { it in session.intDomain(variable.varId) }
    }

    private fun clearHint() {
        hint = null
        hinted.clear()
    }

    override fun onAttempt(decision: SearchDecision, context: SearchContext) {
        branchObserver?.onAttempt(decision, context)
        branchObserver.observeGuidance(PooledGuidanceKind.ATTEMPT) {
            PooledGuidanceObservation(PooledGuidanceKind.ATTEMPT, hint != null,
                decision = decision.guidanceText(), level = context.decisionLevel)
        }
    }

    override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
        branching.onCommit(decision, decisionLevel)
        branchObserver?.onCommit(decision, decisionLevel)
        branchObserver.observeGuidance(PooledGuidanceKind.COMMIT) {
            PooledGuidanceObservation(PooledGuidanceKind.COMMIT, hint != null,
                decision = decision.guidanceText(), level = decisionLevel)
        }
        val (variable, value) = decision.asSelectorDecision() ?: return
        variables.onCommit(variable)
        values.onCommit(variable, value)
        phase.capture(variable, session)
        phase.captureTargetIfDeeper(session, decisionLevel)
        sink?.search?.observeNode(decisionLevel)
    }

    override fun onConflict(decision: SearchDecision?) {
        sink?.search?.observeFail()
        // A prune without a decision, or a refuted hinted decision, shows the replayed point cannot lead further.
        if (decision == null || decision in hinted) clearHint()
        val (variable, value) = decision?.asSelectorDecision() ?: return
        val conflict = nativeConflict()
        if (conflict == null) variables.onConflict(variable) else variables.onConflict(variable, conflict)
        values.onConflict(variable, value)
        phase.onConflictTick()
    }

    fun onCascadeConflict(conflict: PropagationResult.Unsat) {
        sink?.search?.observeFail()
        clearHint()
        variables.onConflict(null, BranchingConflict(conflict.conflictBools, conflict.conflictInts))
        phase.onConflictTick()
    }

    fun onPropagation(implied: PropagationResult.Implied) {
        variables.onPropagation(implied)
        val count = implied.numBools + implied.numInts + implied.intMinKeys.size + implied.intMaxKeys.size +
            implied.intHoleVarIds.size + implied.intSetKeys.size
        sink?.search?.observePropagation(count.toLong())
    }

    override fun onLearnedConflict(conflict: com.eignex.klause.solver.search.SearchLearnedConflict) {
        sink?.search?.observeLearned(conflict.guardLiterals, conflict.lbd)
    }

    override fun onRestart(decisions: Long) {
        branching.onRestart()
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
        branchObserver.observeGuidance(PooledGuidanceKind.LOCAL) {
            PooledGuidanceObservation(PooledGuidanceKind.LOCAL, hint != null)
        }
        clearHint()
        variables.onSolution(sample)
        values.onSolution(sample)
        phase.onSolution(sample)
    }

    fun importPooledSolution(sample: Sample, guideValues: Boolean = false, replay: Boolean = false) {
        if (guideValues && values is SolutionGuided) values.onSolution(sample)
        if (replay && sample.numBoolVars == session.problem.numBoolVars &&
            sample.numIntVars == session.problem.numIntVars
        ) {
            hint = sample
            hinted.clear()
        }
        phase.onSolution(sample)
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

// Search-only branch guidance layered over the configured selectors.
internal interface BacktrackBranching {
    fun onBranch(variable: VarRef) {}

    fun onCommit(decision: SearchDecision, depth: Int) {}

    fun onRestart() {}

    fun pick(session: PropagationSession): VarRef? = null

    fun orderValues(variable: VarRef, values: Sequence<Long>): Sequence<Long> = values

    fun orderAlternatives(variable: VarRef, alternatives: List<SearchDecision>): List<SearchDecision> = alternatives

    data object Selectors : BacktrackBranching
}

private fun SearchDecision.asSelectorDecision(): Pair<VarRef, Long>? = when (this) {
    is SearchDecision.Bool -> VarRef.Bool(literal ushr 1) to if (literal and 1 == 0) 1L else 0L
    is SearchDecision.IntAtMost -> VarRef.IntVar(variable) to upper
    is SearchDecision.IntAtLeast -> VarRef.IntVar(variable) to lower
    is SearchDecision.IntEqual -> VarRef.IntVar(variable) to value
    is SearchDecision.Theory -> null
}
