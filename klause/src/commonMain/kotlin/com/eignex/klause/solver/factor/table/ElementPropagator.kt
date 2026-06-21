package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.solver.factor.table.internals.ElementCache
import com.eignex.klause.solver.factor.table.internals.ElementConstState
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap

/** CP propagator for [Element]. Constructed by [Element.asPropagator]. */
internal class ElementPropagator(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    private val idx: Int,
    private val result: Int,
    private val arr: IntArray,
    private val arrIsVars: Boolean,
    private val indexOffset: Int,
) : Propagator {

    override val initialIntEventWatches: IntArray? = if (!arrIsVars) {
        null
    } else {
        val distinct = intVars.toHashSet()
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

    override val consumesIntEventDelta: Boolean = arrIsVars

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val len = arr.size
        if (!state.tightenIntMin(idx, indexOffset)) return false
        if (!state.tightenIntMax(idx, indexOffset + len - 1)) return false
        if (!arrIsVars) {
            val st = (state.refPayload[factorId] as? ElementConstState)
                ?: ElementConstState(state, idx, result, arr, indexOffset).also { state.refPayload[factorId] = it }
            return st.propagate(state)
        }
        val cache = (state.refPayload[factorId] as? ElementCache) ?: run {
            val firstPos = HashMap<Int, Int>(intVars.size)
            for (k in intVars.indices) firstPos.getOrPut(intVars[k]) { k }
            val fresh = ElementCache(
                arrayOfNulls(intVars.size),
                IntIntMap.build(firstPos.keys.toIntArray(), firstPos.values.toIntArray(), absent = -1),
            )
            state.refPayload[factorId] = fresh
            fresh
        }
        val delta = state.drainIntEventDirtyVars(factorId)
        var changed = cache.cachedDoms[0] == null
        if (!changed) {
            for (vid in delta) {
                val k = cache.posOf[vid]
                if (k >= 0 && cache.cachedDoms[k] !== state.intDomains[vid]) {
                    changed = true
                    break
                }
            }
        }
        if (!changed) return true
        if (!elementPropagateVarArray(state)) return false
        for (k in intVars.indices) cache.cachedDoms[k] = state.intDomains[intVars[k]]
        return true
    }

    /** Full GAC for a variable array. */
    private fun elementPropagateVarArray(state: PropagationState): Boolean {
        val len = arr.size
        val resultDom = state.intDomains[result]
        var toExclude: IntArrayList? = null
        state.intDomains[idx].forEach { iv ->
            val pos = iv - indexOffset
            if (pos in 0 until len && !elementDomainsIntersect(state.intDomains[arr[pos]], resultDom)) {
                (toExclude ?: IntArrayList().also { toExclude = it }).add(iv)
            }
        }
        toExclude?.let { ex ->
            for (i in 0 until ex.size) {
                val ant = collectHoleAndBoundAntecedents(state, intArrayOf(result, arr[ex[i] - indexOffset]))
                if (!state.excludeIntValue(idx, ex[i], ant)) return false
            }
        }

        val positions = IntArrayList()
        state.intDomains[idx].forEach { iv ->
            val pos = iv - indexOffset
            if (pos in 0 until len) positions.add(pos)
        }
        if (positions.size == 0) return false

        var resExclude: IntArrayList? = null
        state.intDomains[result].forEach { rv ->
            var supported = false
            for (k in 0 until positions.size) {
                if (rv in state.intDomains[arr[positions[k]]]) {
                    supported = true
                    break
                }
            }
            if (!supported) (resExclude ?: IntArrayList().also { resExclude = it }).add(rv)
        }
        resExclude?.let { ex ->
            val arrVars = IntArray(positions.size) { arr[positions[it]] }
            val ant = collectHoleAndBoundAntecedents(state, intArrayOf(idx) + arrVars)
            for (i in 0 until ex.size) if (!state.excludeIntValue(result, ex[i], ant)) return false
        }

        val d = state.intDomains[idx]
        if (d.min == d.max) {
            val pos = d.min - indexOffset
            if (pos in 0 until len) {
                val sel = arr[pos]
                val resD = state.intDomains[result]
                var selExclude: IntArrayList? = null
                state.intDomains[sel].forEach { v ->
                    if (v !in resD) (selExclude ?: IntArrayList().also { selExclude = it }).add(v)
                }
                selExclude?.let { ex ->
                    val ant = collectHoleAndBoundAntecedents(state, intArrayOf(idx, result))
                    for (i in 0 until ex.size) if (!state.excludeIntValue(sel, ex[i], ant)) return false
                }
            }
        }
        return true
    }
}

/** Whether [a] and [b] share at least one live value (hole-aware). */
internal fun elementDomainsIntersect(a: IntDomain, b: IntDomain): Boolean {
    if (a.max < b.min || b.max < a.min) return false
    val small = if (a.size <= b.size) a else b
    val large = if (a.size <= b.size) b else a
    var found = false
    small.forEach { v -> if (v in large) found = true }
    return found
}
