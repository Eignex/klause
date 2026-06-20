package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.propagation.PropagationState

/** CP propagation logic for `lex_less` / `lex_lesseq`. */
internal interface LexLessPropagator : Propagator {
    val xs: IntArray
    val ys: IntArray
    val strict: Boolean

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
