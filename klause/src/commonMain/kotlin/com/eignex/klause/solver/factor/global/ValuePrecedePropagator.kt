package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.propagation.RevInt

/** CP propagation logic for `value_precede`. */
internal interface ValuePrecedePropagator : Propagator {
    val s: Int
    val t: Int
    val xs: IntArray

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, xs)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val n = xs.size
        if (n == 0) return true
        val st = (state.refPayload[factorId] as? VpState) ?: run {
            val fresh = VpState(state)
            state.refPayload[factorId] = fresh
            fresh
        }
        val dirty = state.drainIntEventDirtyVars(factorId)
        if (st.started && dirty.isEmpty()) return true
        var reason: IntArray? = null
        var reasonReady = false
        fun reason(): IntArray? {
            if (!reasonReady) {
                reason = collectHoleAndBoundAntecedents(state, xs)
                reasonReady = true
            }
            return reason
        }
        var alpha = st.alpha.value
        while (alpha < n && s !in state.intDomains[xs[alpha]]) alpha++
        if (alpha != st.alpha.value) st.alpha.set(alpha)
        val upTo = if (alpha == n) n - 1 else alpha
        for (j in st.prunedUpTo.value..upTo) {
            val v = xs[j]
            if (t in state.intDomains[v] && !state.excludeIntValue(v, t, reason())) return false
        }
        if (upTo + 1 > st.prunedUpTo.value) st.prunedUpTo.set(upTo + 1)
        var firstForcedT = -1
        for (j in 0 until n) {
            val d = state.intDomains[xs[j]]
            if (d.min == d.max && d.min == t) {
                firstForcedT = j
                break
            }
        }
        if (firstForcedT >= 0) {
            var candidate = -1
            var count = 0
            for (k in 0 until firstForcedT) {
                if (s in state.intDomains[xs[k]]) {
                    candidate = k
                    count++
                    if (count > 1) break
                }
            }
            if (count == 0) return false
            if (count == 1) {
                val v = xs[candidate]
                if (!state.tightenIntMin(v, s, reason())) return false
                if (!state.tightenIntMax(v, s, reason())) return false
            }
        }
        st.started = true
        return true
    }
}

internal class VpState(state: PropagationState) {
    var started: Boolean = false
    val alpha = RevInt(state, 0)
    val prunedUpTo = RevInt(state, 0)
}
