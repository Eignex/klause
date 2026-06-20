package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntHashSet

/** CP propagation logic for `symmetric_all_different`. */
internal interface SymmetricAllDifferentPropagator : Propagator {
    val xs: IntArray
    val indexOffset: Int

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, xs, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val lo = indexOffset
        val hi = indexOffset + xs.size - 1
        for (v in xs) {
            if (!state.tightenIntMin(v, lo)) return false
            if (!state.tightenIntMax(v, hi)) return false
        }
        val taken = IntHashSet()
        for (v in xs) {
            val d = state.intDomains[v]
            if (d.min != d.max) continue
            if (!taken.add(d.min)) return false
        }
        for (i in xs.indices) {
            val d = state.intDomains[xs[i]]
            if (d.min != d.max) continue
            val target = d.min - indexOffset
            if (target !in xs.indices) return false
            val mirror = i + indexOffset
            val ant = state.composeIntVarAtomAntecedents(intArrayOf(xs[i]))
            if (!state.tightenIntMin(xs[target], mirror, ant)) return false
            if (!state.tightenIntMax(xs[target], mirror, ant)) return false
        }
        return true
    }
}
