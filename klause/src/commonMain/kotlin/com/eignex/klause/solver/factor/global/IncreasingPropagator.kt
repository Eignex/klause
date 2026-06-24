package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState

/**
 * CP propagator for [Increasing]: bounds consistency on the chain `xs(0) (+gap) ≤ xs(1) …` via a
 * forward lower-bound sweep then a backward upper-bound sweep. The chain is Berge-acyclic, so these
 * two O(n) passes reach the same fixpoint a pairwise [com.eignex.klause.solver.factor.arithmetic.Linear]
 * decomposition would — full bounds-consistency, no global algorithm gains anything — with one factor
 * and one wake instead of n−1. Lower bounds flow forward and upper bounds flow backward independently
 * (in a `≤` chain a lowered max never raises a min, nor vice versa), so a single pass each suffices.
 */
internal class IncreasingPropagator(private val xs: IntArray, private val gap: Int) : Propagator {

    /** Reads only `min`/`max`, so subscribe to bound events and skip interior `VALUE_REMOVED` wakes. */
    override val initialIntEventWatches: IntArray = run {
        val distinct = xs.toHashSet()
        val out = IntArray(distinct.size * 2)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        }
        out
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val d = state.intDomains
        // Forward: xs(i).min ≥ xs(i−1).min + gap. Each tighten feeds the next iteration, so the
        // prefix maximum propagates in one pass; a failed tighten (min crosses max) is the conflict.
        for (i in 1 until xs.size) {
            val need = d[xs[i - 1]].min.toLong() + gap
            if (need > d[xs[i]].min &&
                !state.tightenIntMin(xs[i], need.toInt(), state.composeIntVarAtomAntecedents(intArrayOf(xs[i - 1])))
            ) {
                return false
            }
        }
        // Backward: xs(i).max ≤ xs(i+1).max − gap.
        for (i in xs.size - 2 downTo 0) {
            val cap = d[xs[i + 1]].max.toLong() - gap
            if (cap < d[xs[i]].max &&
                !state.tightenIntMax(xs[i], cap.toInt(), state.composeIntVarAtomAntecedents(intArrayOf(xs[i + 1])))
            ) {
                return false
            }
        }
        return true
    }
}
