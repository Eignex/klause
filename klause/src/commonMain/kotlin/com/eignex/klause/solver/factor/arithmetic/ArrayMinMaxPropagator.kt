package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.propagation.PropagationState

/** CP contract for [ArrayMinMax]: bounds propagation for `result = max/min(xs)`. */
interface ArrayMinMaxPropagator : Propagator {

    /** Result variable id. */
    val result: Int

    /** Operand variable ids. */
    val xs: IntArray

    /** `true` for max, `false` for min. */
    val max: Boolean

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val antResult = state.composeIntVarAtomAntecedents(intArrayOf(result))
        if (max) {
            var hiBound = Int.MIN_VALUE
            var loBound = Int.MIN_VALUE
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
            var loBound = Int.MAX_VALUE
            var hiBound = Int.MAX_VALUE
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
