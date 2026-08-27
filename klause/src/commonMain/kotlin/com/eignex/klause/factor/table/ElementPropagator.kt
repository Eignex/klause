package com.eignex.klause.factor.table

import com.eignex.klause.config.DEFAULT_DOMAIN_WALK_CAP
import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.table.internals.ElementCache
import com.eignex.klause.factor.table.internals.ElementConstState
import com.eignex.klause.factor.table.internals.allEventWatches
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.values
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntIntMap

/** CP propagator for [Element]. Constructed by [Element.asPropagator]. */
internal class ElementPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val idx: Int,
    private val result: Int,
    private val arr: LongArray,
    private val arrIsVars: Boolean,
    private val indexOffset: Int,
) : Propagator {

    override val initialIntEventWatches: IntArray? = if (!arrIsVars) null else allEventWatches(intVars)

    override val consumesIntEventDelta: Boolean = arrIsVars

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val len = arr.size
        if (!state.tightenIntMin(idx, indexOffset.toLong())) return false
        if (!state.tightenIntMax(idx, (indexOffset + len - 1).toLong())) return false
        if (!arrIsVars) {
            val st = (state.refPayload[factorId] as? ElementConstState)
                ?: ElementConstState(state, idx, result, arr, indexOffset).also { state.refPayload[factorId] = it }
            return st.propagate(state)
        }
        val cache = (state.refPayload[factorId] as? ElementCache) ?: run {
            val firstPos = MutableIntIntMap(intVars.size)
            for (k in intVars.indices) {
                val vid = intVars[k]
                if (!firstPos.containsKey(vid)) firstPos.put(vid, k)
            }
            val keys = IntArrayList(firstPos.size)
            val values = IntArrayList(firstPos.size)
            firstPos.forEach { key, pos ->
                keys.add(key)
                values.add(pos)
            }
            val fresh = ElementCache(
                arrayOfNulls(intVars.size),
                IntIntMap.build(keys.toIntArray(), values.toIntArray(), absent = -1),
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
        val idxDom = state.intDomains[idx]
        // Walk array positions, not the index domain: only in-range indices act here, and `len` is the
        // array length — so a wide index domain is tested by membership, never enumerated.
        var toExclude: LongArrayList? = null
        for (pos in 0 until len) {
            val iv = indexOffset + pos.toLong()
            if (iv in idxDom && !elementDomainsIntersect(state.intDomains[arr[pos].toInt()], resultDom)) {
                (toExclude ?: LongArrayList().also { toExclude = it }).add(iv)
            }
        }
        toExclude?.let { ex ->
            for (i in 0 until ex.size) {
                val ant = collectHoleAndBoundAntecedents(
                    state,
                    intArrayOf(result, arr[(ex[i] - indexOffset).toInt()].toInt()),
                )
                if (!state.excludeIntValue(idx, ex[i], ant)) return false
            }
        }

        val positions = IntArrayList()
        for (pos in 0 until len) {
            if (indexOffset + pos.toLong() in idxDom) positions.add(pos)
        }
        if (positions.size == 0) return false

        var resExclude: LongArrayList? = null
        // A result domain too large to walk skips its per-value support scan (sound — the scan resumes once
        // it narrows below the cap, and every leaf has singleton domains).
        if (state.intDomains[result].spanOrNull(DEFAULT_DOMAIN_WALK_CAP) != null) {
            state.intDomains[result].values.forEach { rv ->
                var supported = false
                for (k in 0 until positions.size) {
                    if (rv in state.intDomains[arr[positions[k]].toInt()]) {
                        supported = true
                        break
                    }
                }
                if (!supported) (resExclude ?: LongArrayList().also { resExclude = it }).add(rv)
            }
        }
        resExclude?.let { ex ->
            val arrVars = IntArray(positions.size) { arr[positions[it]].toInt() }
            val ant = collectHoleAndBoundAntecedents(state, intArrayOf(idx) + arrVars)
            for (i in 0 until ex.size) if (!state.excludeIntValue(result, ex[i], ant)) return false
        }

        val d = state.intDomains[idx]
        if (d.min == d.max) {
            val pos = d.min - indexOffset
            if (pos in 0 until len) {
                val sel = arr[pos.toInt()].toInt()
                val resD = state.intDomains[result]
                var selExclude: LongArrayList? = null
                // A selected-cell domain too large to walk skips its per-value scan (sound — it resumes
                // once the cell narrows below the cap, and leaves have singleton domains).
                if (state.intDomains[sel].spanOrNull(DEFAULT_DOMAIN_WALK_CAP) != null) {
                    state.intDomains[sel].values.forEach { v ->
                        if (v !in resD) (selExclude ?: LongArrayList().also { selExclude = it }).add(v)
                    }
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
    // Walking the smaller domain to find a shared value is O(span) for a large domain. When either is
    // larger than the walk cap, report an intersection whenever the bounds overlap — a sound over-
    // approximation (it can only leave an index unpruned, never prune a supported one).
    if (a.spanOrNull(DEFAULT_DOMAIN_WALK_CAP) == null || b.spanOrNull(DEFAULT_DOMAIN_WALK_CAP) == null) return true
    val small = if (a.values.size <= b.values.size) a else b
    val large = if (a.values.size <= b.values.size) b else a
    var found = false
    small.values.forEach { v -> if (v in large) found = true }
    return found
}
