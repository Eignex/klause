package com.eignex.klause.solver.search

import kotlin.random.Random

/**
 * What a branching heuristic may ask about the current partial assignment.
 *
 * The view is deliberately poorer than any one substrate's own state: liveness of a variable and how
 * many there are, nothing else. That is what an activity- or conflict-driven heuristic needs, and it
 * is available from finite domains and from open integer bounds alike, so one heuristic can drive a
 * finite CP traversal and a theory traversal without either substrate leaking into the other.
 *
 * Heuristics that genuinely need domains — smallest-domain, max-regret, impact — take a richer state
 * and stay with the substrate that owns it.
 */
interface BranchingState {
    /** Number of Boolean variables this branching may choose between. */
    val numBoolVars: Int

    /** Number of integer variables this branching may choose between. */
    val numIntVars: Int

    /** Current value of Boolean variable [variable], or null when it is still free. */
    fun boolValue(variable: Int): Boolean?

    /** True when integer variable [variable] admits exactly one value and cannot be branched on. */
    fun intFixed(variable: Int): Boolean
}

/**
 * The variables one conflict implicated, as an activity heuristic wants to read them.
 *
 * Attribution quality is the substrate's business, not the heuristic's: a shared Boolean analyzer can
 * name every variable of the asserting clause, while a CSP-style sampler names the decision variables
 * at the conflicting levels. Both arrive here in the same shape.
 */
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
 * Picks the next variable to branch on over a substrate exposing [S]. Returns `null` when every
 * variable it can see is determined.
 *
 * The notification hooks let activity-, conflict-, or weight-driven heuristics accumulate state across
 * a search without smuggling listeners through the engine. Pure heuristics ignore them via the
 * defaults.
 *
 * [S] is invariant: a substrate that offers more than [BranchingState] — finite domains, say — also
 * accepts heuristics that need that extra state, and those are not interchangeable with the ones that
 * do not. A heuristic written against [BranchingState] reaches such a substrate through an adapter that
 * supplies the view.
 */
interface VariableBranching<S> {
    /** Pick the next variable to branch on, or null when all the ones it can see are determined. */
    fun pick(state: S, rng: Random): VarRef?

    /** A fresh, unshared instance for one solve: stateless heuristics return this, stateful ones
     *  rebuild from their config so no per-search state leaks across reuse. */
    fun fresh(): VariableBranching<S>

    /** Called once per conflict at `varRef`; bump activity / failure weight. */
    fun onConflict(varRef: VarRef) {}

    /**
     * Richer conflict notification: `varRef` is the decision that triggered the conflict, or null where
     * the conflict has no branch to blame — a complete assignment a check refuted. [conflict] carries
     * the variables the substrate's analysis implicated. Default forwards to [onConflict] so
     * activity-agnostic heuristics ignore the extra information transparently.
     */
    fun onConflict(varRef: VarRef?, conflict: BranchingConflict) {
        if (varRef != null) onConflict(varRef)
    }

    /** Called once per successful pin of `varRef`; useful for phase-saving-like state. */
    fun onCommit(varRef: VarRef) {}

    /** Called when the engine restarts; decay activity or reset per-run counters here. */
    fun onRestart() {}

    /** True if this heuristic wants per-variable [onUnassign] callbacks on every backtrack.
     *  Off by default so heuristics that don't need them pay nothing — the engine only
     *  installs the (per-revert) unassign listener when some heuristic opts in. */
    val tracksUnassign: Boolean get() = false

    /** Called for each variable made free again by a backtrack, when [tracksUnassign] is true.
     *  An order-heap heuristic removes assigned variables on pick and re-inserts them here,
     *  keeping pick O(log n) instead of O(trail-depth · log n). Default no-op. */
    fun onUnassign(varRef: VarRef) {}
}
