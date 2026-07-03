package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import kotlin.random.Random

/**
 * Last-conflict prioritisation (Lecoutre-Saïs-Tabary-Vidal 2009). Wraps any base
 * [VariableSelector]: on every pick, returns the variable that triggered the most
 * recent conflict (if it's still unpinned), otherwise delegates to the base. Cleared
 * when the prioritised variable successfully commits (the next pick falls through to
 * the base) or on restart.
 *
 * Tends to fix unstable subtrees fast — when the search backtracks past a conflict
 * and the responsible variable is back in scope, branching on it again before
 * exploring other vars lets the engine confirm or rule out the cause of the prior
 * failure without wandering. Composes cleanly with [Vsids] / [DomWdeg]: use
 * `LastConflict(Vsids())` to get last-conflict priority on top of activity-driven
 * picking.
 */
class LastConflict(private val base: VariableSelector) : VariableSelector {

    private var pending: VarRef? = null

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val candidate = pending
        if (candidate != null) {
            val stillFree = when (candidate) {
                is VarRef.Bool -> session.boolValue(candidate.varId) == null
                is VarRef.IntVar -> session.intDomain(candidate.varId).size > 1
            }
            if (stillFree) return candidate
            pending = null // assigned away (likely via propagation); drop the prioritisation
        }
        return base.pick(session, rng)
    }

    override fun onConflict(varRef: VarRef) {
        pending = varRef
        base.onConflict(varRef)
    }

    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        pending = varRef
        base.onConflict(varRef, unsat)
    }

    override fun onCommit(varRef: VarRef) {
        if (pending == varRef) pending = null
        base.onCommit(varRef)
    }

    override fun onPropagation(implied: PropagationResult.Implied) {
        base.onPropagation(implied)
    }

    override fun onRestart() {
        pending = null
        base.onRestart()
    }

    override fun onSolution(snapshot: Sample) {
        base.onSolution(snapshot)
    }

    override val tracksUnassign: Boolean get() = base.tracksUnassign
    override fun onUnassign(varRef: VarRef) = base.onUnassign(varRef)
}
