package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.solver.factor.table.internals.RegularIncrementalState
import com.eignex.klause.solver.propagation.PropagationState

/** CP propagation contract for the regular DFA constraint. */
internal interface RegularPropagator : Propagator {

    val seq: IntArray
    val numStates: Int
    val alphabetSize: Int
    val transitions: IntArray
    val q0: Int
    val accepting: IntArray

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val prefix = (state.refPayload[factorId] as? RegularIncrementalState)?.conflictPrefix ?: seq
        return collectHoleAndBoundAntecedents(state, prefix)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val inc = (state.refPayload[factorId] as? RegularIncrementalState) ?: run {
            val fresh = RegularIncrementalState(state, seq, numStates, alphabetSize, transitions, q0, accepting)
            state.refPayload[factorId] = fresh
            fresh
        }
        return inc.propagate(state, factorId)
    }
}
