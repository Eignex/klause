package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState

/** CP propagation logic for `lex_less` / `lex_lesseq`. */
internal class LexLessPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val xs: IntArray,
    private val ys: IntArray,
    private val strict: Boolean,
) : Propagator {

    /**
     * Advisor subscription (#623): lexicographic propagation is bound-only (see [propagate], which
     * compares `min`/`max` at the deciding position and tightens bounds — its own comment notes it
     * "can't propagate further with bound-only reasoning"). An interior hole moves no bound, so the
     * factor subscribes to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED] per variable and skips
     * interior `VALUE_REMOVED` wakes.
     */
    override val initialIntEventWatches: IntArray = run {
        val distinct = intVars.toHashSet()
        val out = IntArray(distinct.size * 2)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        }
        out
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val combined = IntArray(xs.size + ys.size).also {
            xs.copyInto(it, 0)
            ys.copyInto(it, xs.size)
        }
        return collectLinearTightenAntecedents(state, combined, excludeIdx = -1, extraLit = 0)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val len = minOf(xs.size, ys.size)
        var i = 0
        while (i < len) {
            val dx = state.intDomains[xs[i]]
            val dy = state.intDomains[ys[i]]
            if (dx.min == dx.max && dy.min == dy.max) {
                when {
                    dx.min < dy.min -> return true

                    dx.min > dy.min -> return false

                    else -> {
                        i++
                        continue
                    }
                }
            }
            if (dx.max < dy.min) return true
            if (dx.min > dy.max) return false
            val prefixVars = IntArray(2 * i)
            for (j in 0 until i) {
                prefixVars[j] = xs[j]
                prefixVars[i + j] = ys[j]
            }
            val antFromY = state.composeIntVarAtomAntecedents(prefixVars + ys[i])
            val antFromX = state.composeIntVarAtomAntecedents(prefixVars + xs[i])
            if (!state.tightenIntMax(xs[i], dy.max, antFromY)) return false
            if (!state.tightenIntMin(ys[i], dx.min, antFromX)) return false
            val dx2 = state.intDomains[xs[i]]
            val dy2 = state.intDomains[ys[i]]
            if (!(dx2.min == dx2.max && dy2.min == dy2.max)) return true
            if (dx2.min != dy2.min) return dx2.min < dy2.min
            i++
        }
        return when {
            xs.size == ys.size -> !strict
            xs.size < ys.size -> true
            else -> false
        }
    }
}
