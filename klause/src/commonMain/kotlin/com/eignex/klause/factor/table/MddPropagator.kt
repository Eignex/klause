package com.eignex.klause.factor.table

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.table.internals.MddExplainer
import com.eignex.klause.factor.table.internals.MddIncrementalState
import com.eignex.klause.factor.table.internals.MddTransitionIndex
import com.eignex.klause.factor.table.internals.allEventWatches
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.InterruptiblePropagator

internal class MddPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val seq: IntArray,
    private val numStatesPerLayer: IntArray,
    private val layerStarts: IntArray,
    private val transitions: LongArray,
    private val initial: Int,
    private val accepting: IntArray,
    private val recordStride: Int,
    private val cost: Int,
    private val transitionIndex: MddTransitionIndex? = null,
) : InterruptiblePropagator {

    override fun interrupted(state: PropagationState, factorId: Int) {
        (state.refPayload[factorId] as? MddIncrementalState)?.invalidate()
    }

    override val expensiveBake: Boolean get() = true

    override val initialIntEventWatches: IntArray = allEventWatches(intVars)

    override val consumesIntEventDelta: Boolean = true

    // Built once and shared with the incremental state, so a large diagram is not indexed twice.
    private val index by lazy(LazyThreadSafetyMode.NONE) {
        transitionIndex ?: MddTransitionIndex.build(transitions, layerStarts, numStatesPerLayer, recordStride)
    }

    private val explainer = MddExplainer(seq, numStatesPerLayer, transitions, initial, accepting, cost) { index }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = if (state.undoLogging) {
        explainer.conflict(state, state.undo.size)
    } else {
        collectHoleAndBoundAntecedents(state, intVars)
    }

    override fun explain(state: PropagationState, factorId: Int, payload: IntArray, atTrail: Int, atLevel: Int) =
        if (payload[0] == MddIncrementalState.PRUNE) {
            explainer.prune(state, payload[1], atTrail)
        } else {
            explainer.costBound(state, lower = payload[1] == 1, atTrail)
        }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        state.finiteWork.begin()
        val inc = (state.refPayload[factorId] as? MddIncrementalState) ?: run {
            val fresh = MddIncrementalState(
                state, seq, numStatesPerLayer, layerStarts, transitions, initial, accepting, recordStride, cost,
                index,
            )
            state.refPayload[factorId] = fresh
            fresh
        }
        return inc.propagate(state, factorId)
    }
}
