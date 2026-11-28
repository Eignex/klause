package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.solver.search.VariableBranching
import com.eignex.util.collections.IndexedMaxHeap
import com.eignex.util.collections.IntArrayList
import kotlin.random.Random

/**
 * [VariableBranching] over finite domains, plus the hooks only that substrate delivers: a witness at a
 * solution, the reason set at a conflict, and the singletons a fixpoint forced. Heuristics that need none
 * of them are written against [com.eignex.klause.solver.search.BranchingState] and bound via [asSelector].
 */
interface VariableSelector : VariableBranching<PropagationSession> {
    /** Pick the next variable to branch on, or null when none still needs a decision. */
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun pick(session: PropagationSession, rng: Random): VarRef?

    /** A fresh, unshared instance for one solve; stateless selectors return this. */
    override fun fresh(): VariableSelector

    /**
     * Observation hook called once when this fresh selector is attached to its native session, before
     * search and LP branch proposals. The session may still await root initialization; this hook must
     * not mutate it.
     */
    fun onSessionCreated(session: PropagationSession) {}

    /** Called once per solution the search reaches. */
    fun onSolution(snapshot: Sample) {}

    /**
     * Conflict notification with the reason set the propagation engine assembled; [varRef] is the decision
     * that triggered it. Defaults to the variable-only [onConflict].
     */
    fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        onConflict(varRef)
    }

    /** Called after every successful propagation step with the variables it forced into singletons. */
    fun onPropagation(implied: PropagationResult.Implied) {}
}

// `argmax key(v) / dom(v)` over a heap keyed on the undivided score, with ints stored at `numBool + v`.
// Pops are restored so the heap stays complete across calls.
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
        // dom ≥ 2 when free, so activity / 2 bounds every remaining score.
        if (activity / 2.0 <= bestScore) break
        heap.extractMax()
        skip.add(topId)
        if (topId < numBool) {
            if (session.needsBoolDecision(topId)) {
                val score = activity / 2.0
                if (score > bestScore) {
                    bestScore = score
                    best = VarRef.Bool(topId)
                }
            }
        } else {
            val intId = topId - numBool
            val dom = session.intDomain(intId).valueCount
            if (session.needsIntDecision(intId)) {
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
