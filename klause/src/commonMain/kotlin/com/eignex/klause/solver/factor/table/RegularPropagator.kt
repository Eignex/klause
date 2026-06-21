package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.solver.factor.table.internals.RegularIncrementalState
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState

/** CP propagator for [Regular]. Constructed by [Regular.asPropagator]. */
internal class RegularPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val seq: IntArray,
    private val numStates: Int,
    private val alphabetSize: Int,
    private val transitions: IntArray,
    private val q0: Int,
    private val accepting: IntArray,
) : Propagator {

    /** Advisor subscription (#623): GAC over interior domains, so subscribe to every kind on every
     *  (distinct) sequence variable and consume the dirty-variable delta (#624) — the incremental
     *  propagator (`RegularIncrementalState`) recomputes only the layers a changed position reaches. */
    override val initialIntEventWatches: IntArray = run {
        val distinct = seq.toHashSet()
        val out = IntArray(distinct.size * IntEvent.COUNT)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
            out[w++] = IntEvent.pack(v, IntEvent.VALUE_REMOVED)
            out[w++] = IntEvent.pack(v, IntEvent.FIXED)
        }
        out
    }

    override val consumesIntEventDelta: Boolean = true

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
