package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.table.internals.MddIncrementalState
import com.eignex.klause.solver.propagation.PropagationState

/** CP propagation contract for the layered MDD constraint. */
internal interface MddPropagator : Propagator {

    val seq: IntArray
    val numStatesPerLayer: IntArray
    val layerStarts: IntArray
    val transitions: IntArray
    val initial: Int
    val accepting: IntArray
    val recordStride: Int
    val cost: Int

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        state.composeIntVarAtomAntecedents(intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val inc = (state.refPayload[factorId] as? MddIncrementalState) ?: run {
            val fresh = MddIncrementalState(
                state, seq, numStatesPerLayer, layerStarts, transitions, initial, accepting, recordStride, cost,
            )
            state.refPayload[factorId] = fresh
            fresh
        }
        return inc.propagate(state, factorId)
    }
}
