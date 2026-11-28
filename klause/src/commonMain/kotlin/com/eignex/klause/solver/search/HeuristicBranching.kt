package com.eignex.klause.solver.search

import kotlin.random.Random

/** Notified for each Boolean variable a retraction frees. */
fun interface SearchUnassignListener {
    /** [variable] is unassigned again. */
    fun onUnassign(variable: Int)
}

/**
 * Boolean branching driven by a substrate-neutral [VariableBranching], seeing the session only through
 * [BranchingState]. Conflicts are attributed from the asserting clause, which names every participating
 * variable, rather than only the failed decision. Polarity is false-before-true, as in
 * [BooleanBranching.SourceOrder].
 */
class HeuristicBooleanBranching(
    private val heuristic: VariableBranching<BranchingState>,
    numBoolVars: Int,
    private val rng: Random = Random(0),
) : BooleanBranching,
    SearchRunObserver,
    SearchUnassignListener {
    private val state = SessionBranchingState(numBoolVars)

    // The decision whose conflict has not been attributed yet. A conflict that goes on to produce an
    // asserting clause is bumped from that clause instead, so each conflict decays the activity
    // increment exactly once however it was resolved.
    private var pendingConflict: SearchDecision? = null
    private var conflictPending = false

    override fun alternatives(context: SearchContext): List<SearchDecision>? {
        state.context = context
        val variable = when (val picked = heuristic.pick(state, rng)) {
            null -> return null
            is VarRef.Bool -> picked.varId
            is VarRef.IntVar -> error("Boolean branching was offered integer variable ${picked.varId}")
        }
        return listOf(SearchDecision.Bool((variable shl 1) or 1), SearchDecision.Bool(variable shl 1))
    }

    override fun onConflict(decision: SearchDecision?) {
        flushPendingConflict()
        pendingConflict = decision
        conflictPending = true
    }

    override fun onLearnedConflict(conflict: SearchLearnedConflict) {
        val literals = conflict.guardLiterals
        val bools = IntArray(literals.size) { literals[it] ushr 1 }
        heuristic.onConflict(failedVariable(), BranchingConflict(bools))
        pendingConflict = null
        conflictPending = false
    }

    override fun onRestart(decisions: Long) {
        flushPendingConflict()
        heuristic.onRestart()
    }

    override fun onUnassign(variable: Int) {
        if (heuristic.tracksUnassign) heuristic.onUnassign(VarRef.Bool(variable))
    }

    // A conflict that produced no asserting clause is attributed to the decision that failed.
    private fun flushPendingConflict() {
        if (!conflictPending) return
        conflictPending = false
        val failed = failedVariable()
        pendingConflict = null
        if (failed != null) heuristic.onConflict(failed, BranchingConflict.Empty)
    }

    private fun failedVariable(): VarRef.Bool? =
        (pendingConflict as? SearchDecision.Bool)?.let { VarRef.Bool(it.literal ushr 1) }
}

internal class ActivityRestartBooleanBranching(
    private val numBoolVars: Int,
    private val initialDecisions: Long,
) : BooleanBranching, SearchRestartPolicy, SearchRunObserver, SearchUnassignListener {
    private val sourceOrder = BooleanBranching.SourceOrder(numBoolVars)
    private var activity: HeuristicBooleanBranching? = null

    override fun alternatives(context: SearchContext): List<SearchDecision>? =
        (activity ?: sourceOrder).alternatives(context)

    override fun shouldRestart(decisionsThisRun: Long): Boolean =
        activity == null && decisionsThisRun >= initialDecisions

    override fun onRestart() {
        activity = HeuristicBooleanBranching(Vsids(), numBoolVars)
    }

    override fun onConflict(decision: SearchDecision?) {
        activity?.onConflict(decision)
    }

    override fun onLearnedConflict(conflict: SearchLearnedConflict) {
        activity?.onLearnedConflict(conflict)
    }

    override fun onUnassign(variable: Int) {
        activity?.onUnassign(variable)
    }
}

// No integer columns are offered: an integer split stays with the component that owns its domain.
private class SessionBranchingState(override val numBoolVars: Int) : BranchingState {
    var context: SearchContext? = null

    override val numIntVars: Int get() = 0

    override fun needsBoolDecision(variable: Int): Boolean = checkNotNull(context).boolValue(variable) == null

    override fun needsIntDecision(variable: Int): Boolean = false
}
