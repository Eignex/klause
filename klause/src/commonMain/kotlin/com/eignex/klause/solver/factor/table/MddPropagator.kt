package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.table.internals.MddIncrementalState
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState

/** CP propagator for [Mdd]. Constructed by [Mdd.asPropagator]. */
internal class MddPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val seq: IntArray,
    private val numStatesPerLayer: IntArray,
    private val layerStarts: IntArray,
    private val transitions: IntArray,
    private val initial: Int,
    private val accepting: IntArray,
    private val recordStride: Int,
    private val cost: Int,
) : Propagator {

    /** Advisor subscription (#623): the layered reachability sweep reads each sequence variable's
     *  bounds (`sym in min..max`), not interior holes, so it wakes on bound moves only — interior
     *  [IntEvent.VALUE_REMOVED] carves cannot change the reachability bitsets. Consumes the dirty-
     *  variable delta (#624); the incremental propagator (`MddIncrementalState`) recomputes only the
     *  layers a changed position reaches. */
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    override val consumesIntEventDelta: Boolean = true

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
