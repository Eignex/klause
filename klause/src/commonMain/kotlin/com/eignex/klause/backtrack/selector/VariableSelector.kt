package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.orderingSize
import com.eignex.klause.util.IndexedMaxHeap
import com.eignex.klause.util.IntArrayList
import kotlin.random.Random

/**
 * Picks the next variable to branch on. Returns `null` when every variable is determined.
 *
 * The optional notification hooks ([onConflict], [onCommit], [onRestart]) let activity-,
 * conflict-, or weight-driven heuristics (VSIDS, dom/wdeg, last-conflict, impact-based)
 * accumulate state across the search without smuggling listeners through the engine.
 * Pure heuristics (random, smallest-domain, input-order) ignore them via the defaults.
 */
interface VariableSelector {
    /** Pick the next variable to branch on, or null when all are determined. */
    fun pick(session: PropagationSession, rng: Random): VarRef?

    /** A fresh, unshared instance for one solve: stateless selectors return this, stateful ones
     *  rebuild from their config so no per-search state leaks across reuse. */
    fun fresh(): VariableSelector

    /** Called once per propagation conflict at `varRef`; bump activity / failure weight. */
    fun onConflict(varRef: VarRef) {}

    /** Called once per SAT leaf reached by the search. Solution-guided heuristics snapshot
     *  the assignment here so they can bias future picks toward it. Default no-op. */
    fun onSolution(snapshot: Sample) {}

    /**
     * Richer conflict notification: `varRef` is the decision that triggered the conflict,
     * [unsat] carries the full reason set (decision variables, decision levels, contributing
     * factor ids) the propagation engine assembled. VSIDS reads `conflictBools` /
     * `conflictInts`; dom/wdeg reads `conflictFactors`; impact-style heuristics could read
     * `conflictLevels` to score depth. Default forwards to [onConflict] (varRef only) so
     * pure / activity-agnostic heuristics ignore the extra info transparently.
     */
    fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        onConflict(varRef)
    }

    /** Called once per successful pin of `varRef`; useful for phase-saving-like state. */
    fun onCommit(varRef: VarRef) {}

    /**
     * Called after every successful propagation step (pin + fixpoint). [implied] carries
     * the variables newly forced into singletons during this step. Activity-Based Search
     * (Michel-Van Hentenryck 2012) bumps per-variable activity here; pure / activity-
     * agnostic heuristics ignore. Default no-op.
     */
    fun onPropagation(implied: PropagationResult.Implied) {}

    /** Called when the engine restarts (Luby / geometric); decay activity or reset
     *  per-run counters here. */
    fun onRestart() {}

    /** True if this heuristic wants per-variable [onUnassign] callbacks on every backtrack.
     *  Off by default so heuristics that don't need them pay nothing — the engine only
     *  installs the (per-revert) unassign listener when some heuristic opts in. */
    val tracksUnassign: Boolean get() = false

    /** Called for each variable made free again by a backtrack, when [tracksUnassign] is true.
     *  VSIDS removes assigned variables from its order heap on pick and re-inserts them here,
     *  keeping pick O(log n) instead of O(trail-depth · log n). Default no-op. */
    fun onUnassign(varRef: VarRef) {}
}

/**
 * Shared `argmax key(v) / dom(v)` walk used by [DomWdeg] and [ActivityBasedSearch]. The
 * [heap] is keyed on the un-divided score (wdeg or activity); we extract in descending key
 * order and stop once `key / 2.0` (the best score any remaining var could achieve with the
 * tightest possible domain = 2) falls below the current best. Pops are restored at the end
 * so the heap stays complete across calls.
 *
 * Bool ids live in `0..numBool-1`; int ids are stored at `numBool + v` in the heap.
 */
internal fun pickByActivityWithDomDivider(
    heap: IndexedMaxHeap,
    session: PropagationSession,
    numBool: Int,
    skip: IntArrayList,
): VarRef? {
    skip.clear()
    var best: VarRef? = null
    var bestScore = Double.NEGATIVE_INFINITY
    while (heap.size > 0) {
        val topId = heap.peekMax()
        val activity = heap.keyOf(topId)
        // Upper bound on the score of any remaining var: activity / 2 (dom is ≥ 2 when free).
        if (activity / 2.0 <= bestScore) break
        heap.extractMax()
        skip.add(topId)
        if (topId < numBool) {
            if (session.boolValue(topId) == null) {
                val score = activity / 2.0
                if (score > bestScore) {
                    bestScore = score
                    best = VarRef.Bool(topId)
                }
            }
        } else {
            val intId = topId - numBool
            val dom = session.intDomain(intId).orderingSize()
            if (dom > 1) {
                val score = activity / dom.toDouble()
                if (score > bestScore) {
                    bestScore = score
                    best = VarRef.IntVar(intId)
                }
            }
        }
    }
    for (i in 0 until skip.size) heap.restore(skip[i])
    return best
}
