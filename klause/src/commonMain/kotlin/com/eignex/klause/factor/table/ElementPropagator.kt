package com.eignex.klause.factor.table

import com.eignex.klause.config.DEFAULT_DOMAIN_WALK_CAP
import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.table.internals.ElementCache
import com.eignex.klause.factor.table.internals.ElementConstState
import com.eignex.klause.factor.table.internals.allEventWatches
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.boundLiteral
import com.eignex.klause.propagation.exclusionLiteral
import com.eignex.klause.propagation.inDomainAt
import com.eignex.klause.propagation.lazyReason
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntIntMap

/** CP propagator for [Element]. Constructed by the propagation projection. */
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

    override fun explain(state: PropagationState, factorId: Int, payload: IntArray, atTrail: Int, atLevel: Int) =
        if (arrIsVars) {
            Lits().apply { resultUnsupported(state, payload, atTrail) }.toArray()
        } else {
            (state.refPayload[factorId] as ElementConstState).explain(state, payload, atTrail)
        }

    // The reason of the failure the last [propagate] hit on a variable array, read by [conflictReason].
    private var failure: IntArray? = null

    private class Lits {
        private val seen = IntHashSet()
        private val out = IntArrayList()

        fun add(lit: Int) {
            if (lit != Lit.NONE && seen.add(lit)) out.add(lit)
        }

        fun addAll(lits: IntArray?) {
            lits?.forEach { add(it) }
        }

        fun toArray(): IntArray = out.toIntArray()
    }

    private fun excluded(state: PropagationState, v: Int, k: Long, atTrail: Int): Int = if (state.undoLogging) {
        state.exclusionLiteral(v, k, atTrail)
    } else if (k in state.rootDomains[v]) {
        Lit.make(state.atomVarEq(v, k), true)
    } else {
        Lit.NONE
    }

    /**
     * Cell [a] and the result share no value: below the overlap of their bounds the variable with the higher
     * least value excludes everything, above it the one with the lower greatest value, and inside it each value
     * is a hole of one of the two.
     */
    private fun Lits.disjoint(state: PropagationState, a: Int) {
        val now = state.undo.size
        val lvl = state.currentLevel
        val da = state.intDomains[a]
        val dr = state.intDomains[result]
        val lo = maxOf(da.min, dr.min)
        val hi = minOf(da.max, dr.max)
        if (lo > hi) {
            // Separated by bounds: everything at most hi' sits in one, everything above it in the other.
            if (da.max < dr.min) {
                add(state.boundLiteral(a, false, da.max, now, lvl))
                add(state.boundLiteral(result, true, da.max + 1, now, lvl))
            } else {
                add(state.boundLiteral(result, false, dr.max, now, lvl))
                add(state.boundLiteral(a, true, dr.max + 1, now, lvl))
            }
            return
        }
        add(state.boundLiteral(if (da.min >= dr.min) a else result, true, lo, now, lvl))
        add(state.boundLiteral(if (da.max <= dr.max) a else result, false, hi, now, lvl))
        da.forEachHoleInRange(lo, hi) { k -> add(excluded(state, a, k, now)) }
        dr.forEachHoleInRange(lo, hi) { k -> if (k in da) add(excluded(state, result, k, now)) }
    }

    // Each result value in [payload] lost every position: the index left it, or its cell lacks the value.
    private fun Lits.resultUnsupported(state: PropagationState, payload: IntArray, atTrail: Int) {
        val idxRoot = state.rootDomains[idx]
        for (j in 0 until payload.size / 2) {
            val rv = (payload[2 * j].toLong() shl 32) or (payload[2 * j + 1].toLong() and 0xFFFFFFFFL)
            for (pos in arr.indices) {
                val iv = indexOffset + pos.toLong()
                if (iv !in idxRoot) continue
                if (!state.inDomainAt(idx, iv, atTrail)) {
                    add(excluded(state, idx, iv, atTrail))
                } else {
                    add(excluded(state, arr[pos].toInt(), rv, atTrail))
                }
            }
        }
    }

    private fun ownDomain(state: PropagationState, v: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, intArrayOf(v))

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        (if (arrIsVars) failure else constantConflictReason(state)) ?: collectHoleAndBoundAntecedents(state, intVars)

    // Some position must be live in idx with its constant live in result, so the conflict cites, per position,
    // why it is not: idx's bound past it, its own hole, or its constant's absence from result. Null when a
    // position is still fully live, so the conflict lies elsewhere.
    private fun constantConflictReason(state: PropagationState): IntArray? {
        val idxDom = state.intDomains[idx]
        val resDom = state.intDomains[result]
        val idxRoot = state.rootDomains[idx]
        val resRoot = state.rootDomains[result]
        val seen = IntHashSet()
        val out = IntArrayList()
        fun cite(lit: Int) {
            if (lit != Lit.NONE && seen.add(lit)) out.add(lit)
        }

        // A hole the path never carved (a root or survivor restriction) is unconditional and needs no literal.
        fun hole(v: Int, k: Long): Int = if (state.undoLogging) {
            state.exclusionLiteral(v, k, state.undo.size)
        } else {
            Lit.make(state.atomVarEq(v, k), true)
        }
        for (pos in arr.indices) {
            val iv = indexOffset + pos.toLong()
            when {
                iv !in idxRoot -> Unit
                iv < idxDom.min -> cite(Lit.make(state.atomVarGe(idx, idxDom.min), false))
                iv > idxDom.max -> cite(Lit.make(state.atomVarLe(idx, idxDom.max), false))
                iv !in idxDom -> cite(hole(idx, iv))
                arr[pos] in resDom -> return null
                arr[pos] in resRoot -> cite(hole(result, arr[pos]))
            }
        }
        return out.toIntArray()
    }

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
        failure = null
        val len = arr.size
        val atRoot = state.currentLevel == 0
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
                val cell = arr[(ex[i] - indexOffset).toInt()].toInt()
                val ant = if (atRoot) null else Lits().apply { disjoint(state, cell) }.toArray()
                if (!state.excludeIntValue(idx, ex[i], ant)) {
                    failure = Lits().apply {
                        addAll(ant)
                        addAll(ownDomain(state, idx))
                    }.toArray()
                    return false
                }
            }
        }

        val positions = IntArrayList()
        for (pos in 0 until len) {
            if (indexOffset + pos.toLong() in idxDom) positions.add(pos)
        }
        if (positions.size == 0) {
            failure = ownDomain(state, idx) ?: IntArray(0)
            return false
        }

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
            val payload = IntArray(2 * ex.size) { k ->
                val v = ex[k / 2]
                if (k % 2 == 0) (v ushr 32).toInt() else v.toInt()
            }
            val ant = when {
                atRoot -> null
                state.undoLogging -> state.lazyReason(payload)
                else -> Lits().apply { resultUnsupported(state, payload, state.undo.size) }.toArray()
            }
            for (i in 0 until ex.size) {
                if (!state.excludeIntValue(result, ex[i], ant)) {
                    failure = Lits().apply {
                        resultUnsupported(state, payload, state.undo.size)
                        addAll(ownDomain(state, result))
                    }.toArray()
                    return false
                }
            }
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
                    // The index selects this cell, and the result lacks each value it loses.
                    val ant = if (atRoot) {
                        null
                    } else {
                        Lits().apply {
                            add(Lit.make(state.atomVarEq(idx, d.min), false))
                            for (k in 0 until ex.size) add(excluded(state, result, ex[k], state.undo.size))
                        }.toArray()
                    }
                    for (i in 0 until ex.size) {
                        if (!state.excludeIntValue(sel, ex[i], ant)) {
                            failure = Lits().apply {
                                addAll(ant)
                                addAll(ownDomain(state, sel))
                            }.toArray()
                            return false
                        }
                    }
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
