package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.BranchingConflict
import com.eignex.klause.solver.search.BranchingState
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.solver.search.Vsids
import kotlin.random.Random

internal class PrivateRebuildFreeSelector(private val columns: IntArray) : VariableSelector {
    private val activity = Vsids()
    private var view: FreeState? = null

    override fun fresh(): VariableSelector = PrivateRebuildFreeSelector(columns)

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        val state = view?.takeIf { it.session === session } ?: FreeState(session, columns).also { view = it }
        return activity.pick(state, rng) ?: InputOrder.pick(session, rng)
    }

    override fun onConflict(varRef: VarRef) {
        activity.onConflict(varRef, BranchingConflict.Empty)
    }

    override fun onConflict(varRef: VarRef?, conflict: BranchingConflict) {
        activity.onConflict(varRef, conflict)
    }

    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        activity.onConflict(varRef, BranchingConflict(unsat.conflictBools, unsat.conflictInts))
    }

    override fun onRestart() {
        activity.onRestart()
    }

    override val tracksUnassign: Boolean get() = true

    override fun onUnassign(varRef: VarRef) {
        if (varRef is VarRef.Bool && view?.preferred(varRef.varId) == true) activity.onUnassign(varRef)
    }

    private class FreeState(val session: PropagationSession, columns: IntArray) : BranchingState {
        private val preferred = BooleanArray(session.problem.numBoolVars).also { flags ->
            for (column in columns) flags[column] = true
        }

        override val numBoolVars: Int get() = session.problem.numBoolVars

        override val numIntVars: Int get() = 0

        fun preferred(variable: Int): Boolean = preferred[variable]

        override fun needsBoolDecision(variable: Int): Boolean =
            preferred[variable] && session.needsBoolDecision(variable)

        override fun needsIntDecision(variable: Int): Boolean = false
    }

    companion object {
        fun modeledBytes(columns: Int): Long = columns * 17L + 64L

        fun initializationWork(columns: Int, free: Int): Long = columns * 16L + free * 4L + 32L
    }
}
