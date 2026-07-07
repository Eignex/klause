package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator

/** CP propagator for [ArrayMinMax]: bounds propagation for `result = max/min(xs)`. */
internal class ArrayMinMaxPropagator(
    private val result: Int,
    private val xs: IntArray,
    private val max: Boolean,
    val boolVars: IntArray,
    val intVars: IntArray,
) : Propagator {

    /**
     * Advisor subscription (#623): `propagate` tightens `result` against the operands' bounds and
     * pushes `result`'s bound back onto every operand — reading only `min`/`max`. An interior hole
     * never moves a `min`/`max`, so the factor subscribes to [IntEvent.LB_RAISED] /
     * [IntEvent.UB_LOWERED] on each variable and skips interior `VALUE_REMOVED` wakes. A repeated
     * operand is subscribed once.
     */
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val antResult = state.composeIntVarAtomAntecedents(intArrayOf(result))
        if (max) {
            var hiBound = Long.MIN_VALUE
            var loBound = Long.MIN_VALUE
            var loVar = xs[0]
            for (i in xs) {
                val d = state.intDomains[i]
                if (d.max > hiBound) hiBound = d.max
                if (d.min > loBound) {
                    loBound = d.min
                    loVar = i
                }
            }
            if (!state.tightenIntMax(result, hiBound, state.composeIntVarAtomAntecedents(xs))) return false
            if (!state.tightenIntMin(
                    result,
                    loBound,
                    state.composeIntVarAtomAntecedents(intArrayOf(loVar)),
                )
            ) {
                return false
            }
            val rMax = state.intDomains[result].max
            for (i in xs) if (!state.tightenIntMax(i, rMax, antResult)) return false
        } else {
            var loBound = Long.MAX_VALUE
            var hiBound = Long.MAX_VALUE
            var hiVar = xs[0]
            for (i in xs) {
                val d = state.intDomains[i]
                if (d.min < loBound) loBound = d.min
                if (d.max < hiBound) {
                    hiBound = d.max
                    hiVar = i
                }
            }
            if (!state.tightenIntMin(result, loBound, state.composeIntVarAtomAntecedents(xs))) return false
            if (!state.tightenIntMax(
                    result,
                    hiBound,
                    state.composeIntVarAtomAntecedents(intArrayOf(hiVar)),
                )
            ) {
                return false
            }
            val rMin = state.intDomains[result].min
            for (i in xs) if (!state.tightenIntMin(i, rMin, antResult)) return false
        }
        return true
    }
}
