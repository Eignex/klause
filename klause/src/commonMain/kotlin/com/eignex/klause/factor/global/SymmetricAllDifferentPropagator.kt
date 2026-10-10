package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator

/** CP propagation logic for `symmetric_all_different`. */
internal class SymmetricAllDifferentPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val xs: IntArray,
    private val indexOffset: Int,
) : Propagator {

    /**
     * Advisor subscription: `propagate` reads only each variable's `min`/`max` — it tightens
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

    // The variables the last failed [propagate] rests on: two fixed to one value, or a fixed one and the mirror
    // it could not pin.

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        state.propagatorFailures[this]?.let { state.composeIntVarAtomAntecedents(it) }
            ?: collectLinearTightenAntecedents(state, xs, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        state.propagatorFailures.remove(this)
        val lo = indexOffset
        val hi = indexOffset + xs.size - 1
        for (v in xs) {
            if (!state.tightenIntMin(v, lo.toLong())) return false
            if (!state.tightenIntMax(v, hi.toLong())) return false
        }
        // Every domain now lies in [lo, hi], so a fixed value indexes its claimant directly.
        val claimedBy = IntArray(xs.size) { -1 }
        for (v in xs) {
            val d = state.intDomains[v]
            if (d.min != d.max) continue
            val slot = (d.min - indexOffset).toInt()
            if (claimedBy[slot] != -1) {
                state.propagatorFailures[this] = intArrayOf(claimedBy[slot], v)
                return false
            }
            claimedBy[slot] = v
        }
        for (i in xs.indices) {
            val d = state.intDomains[xs[i]]
            if (d.min != d.max) continue
            val target = d.min - indexOffset
            if (target < 0 || target >= xs.size) return false
            val mirror = i + indexOffset
            val ant = state.composeIntVarAtomAntecedents(intArrayOf(xs[i]))
            state.propagatorFailures[this] = intArrayOf(xs[i], xs[target.toInt()])
            if (!state.tightenIntMin(xs[target.toInt()], mirror.toLong(), ant)) return false
            if (!state.tightenIntMax(xs[target.toInt()], mirror.toLong(), ant)) return false
            state.propagatorFailures.remove(this)
        }
        return true
    }
}
