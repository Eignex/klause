package com.eignex.klause.solver.search

import kotlin.random.Random

/**
 * What a branching heuristic may ask about the current partial assignment: variable counts and
 * liveness only, so one heuristic can drive both finite CP and theory traversals. Heuristics that need
 * domains take a richer state.
 */
interface BranchingState {
    /** Number of Boolean variables this branching may choose between. */
    val numBoolVars: Int

    /** Number of integer variables this branching may choose between. */
    val numIntVars: Int

    /** True while Boolean variable [variable] is free and this branching still has to decide it. */
    fun needsBoolDecision(variable: Int): Boolean

    /** True while integer variable [variable] admits more than one value and this branching still has to decide it. */
    fun needsIntDecision(variable: Int): Boolean
}

/** The variables one conflict implicated, as attributed by the substrate's analysis. */
class BranchingConflict(
    /** Boolean variables implicated in the conflict. */
    val bools: IntArray = IntArray(0),
    /** Integer variables implicated in the conflict. */
    val ints: IntArray = IntArray(0),
) {
    /** Attributions every substrate can produce. */
    companion object {
        /** No named variables beyond the failed decision itself. */
        val Empty = BranchingConflict()
    }
}

/**
 * Picks the next variable to branch on over a substrate exposing [S]. The notification hooks let
 * stateful heuristics accumulate activity across a search; pure heuristics keep the defaults.
 */
interface VariableBranching<S> {
    /** Pick the next variable to branch on, or null when all the ones it can see are determined. */
    fun pick(state: S, rng: Random): VarRef?

    /** A fresh, unshared instance for one solve, so no per-search state leaks across reuse. */
    fun fresh(): VariableBranching<S>

    /** Called once per conflict at `varRef`. */
    fun onConflict(varRef: VarRef) {}

    /**
     * Conflict notification with the implicated variables. `varRef` is the decision that triggered the
     * conflict, or null when a check refuted a complete assignment.
     */
    fun onConflict(varRef: VarRef?, conflict: BranchingConflict) {
        if (varRef != null) onConflict(varRef)
    }

    /** Called once per successful pin of `varRef`. */
    fun onCommit(varRef: VarRef) {}

    /** Called when the engine restarts. */
    fun onRestart() {}

    /**
     * True if this heuristic wants [onUnassign] callbacks on every backtrack. Off by default because the
     * engine installs the unassign listener only when some heuristic opts in.
     */
    val tracksUnassign: Boolean get() = false

    /** Called for each variable a backtrack frees, when [tracksUnassign] is true. */
    fun onUnassign(varRef: VarRef) {}
}
