package com.eignex.klause.solver.search

import kotlin.random.Random

/** Notified for each Boolean variable a retraction frees. */
fun interface SearchUnassignListener {
    /** [variable] is unassigned again. */
    fun onUnassign(variable: Int)
}

/**
 * Boolean branching driven by a substrate-neutral [VariableBranching].
 *
 * The heuristic sees the shared session only through [BranchingState], so the same instance that
 * drives a finite CP traversal drives this one. It also observes the traversal: activity heuristics
 * need conflicts, and the shared analyzer's asserting clause names every variable that participated,
 * which is a better attribution than a substrate reporting only the decision that failed.
 *
 * Polarity is false-before-true, as in [BooleanBranching.SourceOrder] — this changes which variable is
 * split, not which side is tried first.
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

    /** Attribute a conflict that produced no asserting clause to the decision that failed. */
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

/**
 * [BranchingState] over a shared session.
 *
 * No integer columns are offered: this seam supplies Boolean alternatives, and an integer split stays
 * with the component that owns the domain it splits.
 */
private class SessionBranchingState(override val numBoolVars: Int) : BranchingState {
    var context: SearchContext? = null

    override val numIntVars: Int get() = 0

    override fun boolValue(variable: Int): Boolean? = checkNotNull(context).boolValue(variable)

    override fun intFixed(variable: Int): Boolean = true
}
