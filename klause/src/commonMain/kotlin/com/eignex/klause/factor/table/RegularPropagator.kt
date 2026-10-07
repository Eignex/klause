package com.eignex.klause.factor.table

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.table.internals.RegularExplainer
import com.eignex.klause.factor.table.internals.RegularIncrementalState
import com.eignex.klause.factor.table.internals.allEventWatches
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator

/** CP propagator for [Regular]. Constructed by the propagation projection. */
internal class RegularPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val seq: IntArray,
    private val numStates: Int,
    private val alphabetSize: Int,
    private val transitions: LongArray,
    private val q0: Int,
    private val accepting: IntArray,
) : Propagator {

    override val expensiveBake: Boolean get() = true

    /** Advisor subscription: GAC over interior domains, so subscribe to every kind on every
     *  (distinct) sequence variable and consume the dirty-variable delta — the incremental
     *  propagator (`RegularIncrementalState`) recomputes only the layers a changed position reaches. */
    override val initialIntEventWatches: IntArray = allEventWatches(seq)

    override val consumesIntEventDelta: Boolean = true

    private val explainer = RegularExplainer(seq, numStates, alphabetSize, transitions, q0, accepting)

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        if (state.undoLogging) return explainer.conflict(state, state.undo.size)
        val prefix = (state.refPayload[factorId] as? RegularIncrementalState)?.conflictPrefix ?: seq
        return collectHoleAndBoundAntecedents(state, prefix)
    }

    override fun explain(state: PropagationState, factorId: Int, payload: IntArray, atTrail: Int, atLevel: Int) =
        explainer.prune(state, payload[0], atTrail)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val inc = (state.refPayload[factorId] as? RegularIncrementalState) ?: run {
            val fresh = RegularIncrementalState(state, seq, numStates, alphabetSize, transitions, q0, accepting)
            state.refPayload[factorId] = fresh
            fresh
        }
        return inc.propagate(state, factorId)
    }
}
