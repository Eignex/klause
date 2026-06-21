package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntHashSet

/** CP propagation logic for `symmetric_all_different`. */
internal class SymmetricAllDifferentPropagator(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    private val xs: IntArray,
    private val indexOffset: Int,
) : Propagator {

    /**
     * Advisor subscription (#623): `propagate` reads only each variable's `min`/`max` — it tightens
     * into the index range, detects clashes among already-fixed variables, and forces the involution
     * mirror of a fixed variable. An interior hole moves no bound and fixes nothing, so the factor
     * subscribes to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED] per variable and skips interior
     * `VALUE_REMOVED` wakes (fixing collapses both bounds, so it is covered).
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
