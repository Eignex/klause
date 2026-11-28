package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.BranchingConflict
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

/**
 * Last-conflict prioritisation (Lecoutre, Saïs, Tabary and Vidal, "Reasoning from last conflict(s) in
 * constraint programming", Artificial Intelligence 173, 2009, https://doi.org/10.1016/j.artint.2009.09.002).
 * Picks the variable of the most recent conflict while it is unpinned, otherwise delegates to [base]. The
 * priority clears when that variable commits or on restart.
 */
class LastConflict(private val base: VariableSelector) : VariableSelector {

    override fun fresh() = LastConflict(base.fresh())

    private var pending: VarRef? = null

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val candidate = pending
        if (candidate != null) {
            val stillFree = when (candidate) {
                is VarRef.Bool -> session.needsBoolDecision(candidate.varId)
                is VarRef.IntVar -> session.needsIntDecision(candidate.varId)
            }
            if (stillFree) return candidate
            pending = null
        }
        return base.pick(session, rng)
    }

    override fun onConflict(varRef: VarRef) {
        pending = varRef
        base.onConflict(varRef)
    }

    override fun onConflict(varRef: VarRef?, conflict: BranchingConflict) {
        if (varRef != null) pending = varRef
        base.onConflict(varRef, conflict)
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
