package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.RevInt
import com.eignex.klause.propagation.exclusionLiteral
import com.eignex.klause.propagation.lazyReason
import com.eignex.klause.util.IntArrayList

/** CP propagation logic for `value_precede`. */
internal class ValuePrecedePropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val s: Long,
    private val t: Long,
    private val xs: IntArray,
) : Propagator {

    /** Advisor subscription: membership-sensitive (the prefix scan tests `s ∈ dom` and
     *  forced-`t`), so subscribe to every kind on every sequence variable and consume the dirty-
     *  variable delta. The reversible `α`/`prunedUpTo` state ([VpState]) advances only over the
     *  changed prefix instead of rescanning the whole sequence each fire. */
    override val initialIntEventWatches: IntArray = run {
        val distinct = xs.toHashSet()
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

    // The reason of the failure the last [propagate] hit, read by [conflictReason] before the engine backtracks.
    private var failure: IntArray? = null

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        failure ?: collectHoleAndBoundAntecedents(state, xs)

    // A t at position payload(0) would precede every s: s had left each earlier position.
    override fun explain(state: PropagationState, factorId: Int, payload: IntArray, atTrail: Int, atLevel: Int) =
        sBefore(state, payload[0], -1, atTrail).toIntArray()

    // The literals saying s had left every position before [end] but [skip], as of [atTrail].
    private fun sBefore(state: PropagationState, end: Int, skip: Int, atTrail: Int): IntArrayList {
        val out = IntArrayList()
        for (k in 0 until end) {
            if (k == skip) continue
            val lit = if (state.undoLogging) {
                state.exclusionLiteral(xs[k], s, atTrail)
            } else if (s in state.rootDomains[xs[k]]) {
                Lit.make(state.atomVarEq(xs[k], s), true)
            } else {
                Lit.NONE
            }
            if (lit != Lit.NONE && !out.contains(lit)) out.add(lit)
        }
        return out
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val n = xs.size
        if (n == 0) return true
        val st = (state.refPayload[factorId] as? VpState) ?: run {
            val fresh = VpState(state)
            state.refPayload[factorId] = fresh
            fresh
        }
        failure = null
        val dirty = state.drainIntEventDirtyVars(factorId)
        if (st.started && dirty.isEmpty()) return true
        fun noT(j: Int): IntArray? = when {
            state.currentLevel == 0 -> null
            state.undoLogging -> state.lazyReason(intArrayOf(j))
            else -> sBefore(state, j, -1, state.undo.size).toIntArray()
        }
        var alpha = st.alpha.value
        while (alpha < n && s !in state.intDomains[xs[alpha]]) alpha++
        if (alpha != st.alpha.value) st.alpha.set(alpha)
        val upTo = if (alpha == n) n - 1 else alpha
        for (j in st.prunedUpTo.value..upTo) {
            val v = xs[j]
            if (t in state.intDomains[v] && !state.excludeIntValue(v, t, noT(j))) {
                failure = sBefore(state, j, -1, state.undo.size).toIntArray() +
                    (collectHoleAndBoundAntecedents(state, intArrayOf(v)) ?: IntArray(0))
                return false
            }
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
            // A t at position firstForcedT needs an s before it.
            val pinnedT = Lit.make(state.atomVarEq(xs[firstForcedT], t), false)
            if (count == 0) {
                failure = sBefore(state, firstForcedT, -1, state.undo.size).also { it.add(pinnedT) }.toIntArray()
                return false
            }
            if (count == 1) {
                val v = xs[candidate]
                val ant = if (state.currentLevel == 0) {
                    null
                } else {
                    sBefore(state, firstForcedT, candidate, state.undo.size).also { it.add(pinnedT) }.toIntArray()
                }
                if (!state.tightenIntMin(v, s, ant) || !state.tightenIntMax(v, s, ant)) {
                    failure = (ant ?: IntArray(0)) + (collectHoleAndBoundAntecedents(state, intArrayOf(v)) ?: IntArray(0))
                    return false
                }
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
