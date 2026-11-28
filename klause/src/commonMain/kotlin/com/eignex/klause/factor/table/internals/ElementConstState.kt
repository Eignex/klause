package com.eignex.klause.factor.table.internals

import com.eignex.klause.config.DEFAULT_DOMAIN_WALK_CAP
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.RevInt
import com.eignex.klause.propagation.RevIntArray
import com.eignex.klause.propagation.RevRef
import com.eignex.klause.propagation.domainAt
import com.eignex.klause.propagation.excludeIntValues
import com.eignex.klause.propagation.exclusionLiteral
import com.eignex.klause.propagation.inDomainAt
import com.eignex.klause.propagation.lazyReason
import com.eignex.klause.propagation.restrictIntToSurvivors
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.MutableLongIntMap
import com.eignex.util.collections.toSortedLongArray

// Reversible GAC for `result = arr(idx)`. A support count per distinct constant records how many live index
// positions hold it. Counts and the last fixpoint's domain refs are trailed, so a fire processes only removals
// and backtracking restores the prior fixpoint.
internal class ElementConstState(
    state: PropagationState,
    private val idx: Int,
    private val result: Int,
    private val arr: LongArray,
    private val indexOffset: Int,
) {
    private val len = arr.size

    // Static value-id universe over the distinct constants (ids in array first-occurrence order).
    private val idOfValue = MutableLongIntMap()
    private val valueOfId: LongArray
    private val idOfPos = IntArray(len)
    private val positionsOfId: Array<IntArray>
    private val numValues: Int

    init {
        val vals = LongArrayList()
        val posLists = ArrayList<IntArrayList>()
        for (pos in 0 until len) {
            val v = arr[pos]
            var id = idOfValue.getOrDefault(v, -1)
            if (id < 0) {
                id = vals.size
                idOfValue.put(v, id)
                vals.add(v)
                posLists.add(IntArrayList())
            }
            idOfPos[pos] = id
            posLists[id].add(pos)
        }
        numValues = vals.size
        valueOfId = vals.toLongArray()
        positionsOfId = Array(numValues) { posLists[it].toIntArray() }
    }

    // Trailed: support count per value-id, the validity flag, and the last-fire domain refs.
    private val supportCount = RevIntArray(state, numValues, 0)
    private val valid = RevInt(state, 0)
    private val domRefIdx = RevRef<IntDomain?>(state, null)
    private val domRefResult = RevRef<IntDomain?>(state, null)

    fun propagate(state: PropagationState): Boolean {
        val idxDom = state.intDomains[idx]
        val resDom = state.intDomains[result]
        if (valid.value == 1 && domRefIdx.value === idxDom && domRefResult.value === resDom) {
            return true
        }
        val ok = if (valid.value == 0 || widened(idxDom, resDom)) rebuild(state) else delta(state)
        if (!ok) return false
        domRefIdx.set(state.intDomains[idx])
        domRefResult.set(state.intDomains[result])
        return true
    }

    private fun idFor(value: Long): Int = idOfValue.getOrDefault(value, -1)

    // A domain that gained a value since the last fire (a backtrack restored it) cannot be patched incrementally.
    private fun widened(idxDom: IntDomain, resDom: IntDomain): Boolean {
        val pi = domRefIdx.value
        if (pi != null && idxDom !== pi) {
            // Neither widening detection nor the delta walk can touch a domain too large to walk.
            if (idxDom.spanOrNull(
                    DEFAULT_DOMAIN_WALK_CAP,
                ) == null || pi.spanOrNull(DEFAULT_DOMAIN_WALK_CAP) == null
            ) {
                return true
            }
            var w = false
            idxDom.values.forEach { v -> if (v !in pi) w = true }
            if (w) return true
        }
        val pr = domRefResult.value
        if (pr != null && resDom !== pr) {
            if (resDom.spanOrNull(
                    DEFAULT_DOMAIN_WALK_CAP,
                ) == null || pr.spanOrNull(DEFAULT_DOMAIN_WALK_CAP) == null
            ) {
                return true
            }
            var w = false
            resDom.values.forEach { v -> if (v !in pr) w = true }
            if (w) return true
        }
        return false
    }

    private fun rebuild(state: PropagationState): Boolean {
        val idxDom = state.intDomains[idx]
        val resDom = state.intDomains[result]
        val counts = IntArray(numValues)
        // Walk array positions, not the index domain: a wide index is tested by membership, never walked.
        for (pos in 0 until len) {
            if (indexOffset + pos.toLong() in idxDom) counts[idOfPos[pos]]++
        }
        for (id in 0 until numValues) supportCount[id] = counts[id]
        valid.set(1)

        // No root reason is ever read, so the root restricts both domains to their survivor sets in one step
        // instead of excluding unsupported values across the result span.
        if (state.currentLevel == 0) return seedRestrictAtRoot(state, idxDom, resDom, counts)

        val idxSeed = LongArrayList()
        for (pos in 0 until len) {
            val iv = indexOffset + pos.toLong()
            if (iv in idxDom && arr[pos] !in resDom) idxSeed.add(iv)
        }
        val resSeed = LongArrayList()
        // A result domain too large to walk skips its per-value support scan (sound — it resumes once
        // result narrows below the cap, and every leaf has singleton domains).
        if (resDom.spanOrNull(DEFAULT_DOMAIN_WALK_CAP) != null) {
            resDom.values.forEach { rv ->
                val id = idFor(rv)
                if (id < 0 || counts[id] == 0) resSeed.add(rv)
            }
        }
        return applyThenCascade(state, idxSeed, resSeed)
    }

    // Removing the other positions cannot drop a surviving constant's support, so this two-sided restriction is
    // the fixpoint. [supportCount] is left matching the restricted domains for later level-0 delta fires.
    private fun seedRestrictAtRoot(
        state: PropagationState,
        idxDom: IntDomain,
        resDom: IntDomain,
        counts: IntArray,
    ): Boolean {
        val idSurvives = BooleanArray(numValues)
        val resSurvivors = LongArrayList()
        for (id in 0 until numValues) {
            if (counts[id] > 0 && valueOfId[id] in resDom) {
                idSurvives[id] = true
                resSurvivors.add(valueOfId[id])
            } else {
                supportCount[id] = 0
            }
        }
        if (!state.restrictIntToSurvivors(result, resSurvivors.toSortedLongArray())) return false
        // Positions are walked in ascending order, so the collected survivors are already sorted.
        val idxSurvivors = LongArrayList()
        for (pos in 0 until len) {
            val iv = indexOffset + pos.toLong()
            if (iv in idxDom && idSurvives[idOfPos[pos]]) idxSurvivors.add(iv)
        }
        return state.restrictIntToSurvivors(idx, idxSurvivors.toLongArray())
    }

    private fun delta(state: PropagationState): Boolean {
        val idxRem = removedSince(domRefIdx.value, state.intDomains[idx])
        val resRem = removedSince(domRefResult.value, state.intDomains[result])
        return cascade(state, idxRem ?: LongArrayList(), resRem ?: LongArrayList())
    }

    private fun removedSince(prev: IntDomain?, cur: IntDomain): LongArrayList? {
        if (prev == null || prev === cur) return null
        val out = LongArrayList()
        prev.values.forEach { v -> if (v !in cur) out.add(v) }
        return out
    }

    private fun applyThenCascade(state: PropagationState, idxSeed: LongArrayList, resSeed: LongArrayList): Boolean {
        if (idxSeed.size > 0) {
            val ex = sortedDistinct(idxSeed)
            if (!state.excludeIntValues(idx, ex, indexReason(state, ex))) return false
        }
        if (resSeed.size > 0 && !pruneResult(state, sortedDistinct(resSeed))) return false
        return cascade(state, idxSeed, resSeed)
    }

    private fun cascade(state: PropagationState, idxRem0: LongArrayList, resRem0: LongArrayList): Boolean {
        var idxRem = idxRem0
        var resRem = resRem0
        while (idxRem.size > 0 || resRem.size > 0) {
            val resultToExclude = unsupportedResults(state, idxRem)
            val idxToExclude = positionsOfLeftResults(state, resRem)

            var nextIdx = EMPTY
            var nextRes = EMPTY
            if (resultToExclude.size > 0) {
                if (!pruneResult(state, sortedDistinct(resultToExclude))) return false
                nextRes = resultToExclude
            }
            if (idxToExclude.size > 0) {
                val ex = sortedDistinct(idxToExclude)
                if (!state.excludeIntValues(idx, ex, indexReason(state, ex))) return false
                nextIdx = idxToExclude
            }
            idxRem = nextIdx
            resRem = nextRes
        }
        return true
    }

    // Each of a round's two scans is its own method: in one body, on-stack replacement of any loop recompiles all.
    private fun unsupportedResults(state: PropagationState, idxRem: LongArrayList): LongArrayList {
        val resultToExclude = LongArrayList()
        for (i in 0 until idxRem.size) {
            val pos = idxRem[i] - indexOffset
            if (pos !in 0 until len) continue
            val id = idOfPos[pos.toInt()]
            val c = supportCount[id] - 1
            supportCount[id] = c
            if (c == 0 && valueOfId[id] in state.intDomains[result]) {
                resultToExclude.add(valueOfId[id])
            }
        }
        return resultToExclude
    }

    private fun positionsOfLeftResults(state: PropagationState, resRem: LongArrayList): LongArrayList {
        val idxToExclude = LongArrayList()
        val idxDom = state.intDomains[idx]
        for (i in 0 until resRem.size) {
            val id = idFor(resRem[i])
            if (id < 0) continue
            for (pos in positionsOfId[id]) {
                val iv = pos.toLong() + indexOffset
                if (iv in idxDom) idxToExclude.add(iv)
            }
        }
        return idxToExclude
    }

    private fun sortedDistinct(list: LongArrayList): LongArray = list.toSortedLongArray()

    // Remove the ascending result values [ex]. Those taking an endpoint move it with a reason of its own: every
    // constant past the new bound is gone ([passed]). Snapping the bound over the batch instead would explain it
    // by the old bound and each hole crossed, and conflict analysis would carry those holes into the clause.
    private fun pruneResult(state: PropagationState, ex: LongArray): Boolean {
        val d = state.intDomains[result]
        var lo = d.min
        var i = 0
        while (i < ex.size && ex[i] <= lo && lo <= d.max) {
            if (ex[i] == lo) lo = if (lo == d.max) Long.MAX_VALUE else d.higher(lo)
            i++
        }
        var hi = d.max
        var j = ex.size - 1
        while (j >= 0 && ex[j] >= hi && hi >= d.min) {
            if (ex[j] == hi) hi = if (hi == d.min) Long.MIN_VALUE else d.lower(hi)
            j--
        }
        // A batch that empties the domain is a conflict, which the batch exclusion reports with its reason.
        if (lo > hi) return state.excludeIntValues(result, ex, resultReason(state, ex))
        if (lo != d.min && !state.tightenIntMin(result, lo, boundReason(state, BELOW, lo))) return false
        if (hi != d.max && !state.tightenIntMax(result, hi, boundReason(state, ABOVE, hi))) return false
        var interior = 0
        for (v in ex) if (v > lo && v < hi) interior++
        if (interior == 0) return true
        val rest = LongArray(interior)
        var k = 0
        for (v in ex) if (v > lo && v < hi) rest[k++] = v
        return state.excludeIntValues(result, rest, resultReason(state, rest))
    }

    // A result value leaves because every position holding it left idx; a position leaves because its constant left
    // result. One batch shares the union over its values, built only if conflict analysis reads it ([explain]).
    private fun resultReason(state: PropagationState, values: LongArray): IntArray? = reason(state, RESULT, values)

    private fun indexReason(state: PropagationState, positions: LongArray): IntArray? = reason(state, INDEX, positions)

    private fun boundReason(state: PropagationState, side: Int, bound: Long): IntArray? = when {
        state.currentLevel == 0 -> null
        state.undoLogging -> state.lazyReason(intArrayOf(side, (bound ushr 32).toInt(), bound.toInt()))
        else -> passed(state, side == BELOW, bound, state.undo.size)
    }

    private fun reason(state: PropagationState, side: Int, values: LongArray): IntArray? = when {
        state.currentLevel == 0 -> null

        state.undoLogging -> state.lazyReason(
            IntArray(1 + 2 * values.size).also { p ->
                p[0] = side
                for (k in values.indices) {
                    p[1 + 2 * k] = (values[k] ushr 32).toInt()
                    p[2 + 2 * k] = values[k].toInt()
                }
            },
        )

        // Without the undo log nothing reads reasons, and the live domains stand in for the past ones.
        else -> supports(state, side, values, state.undo.size)
    }

    // Decodes a payload recorded by [reason] or [boundReason], as of undo-log position [atTrail].
    fun explain(state: PropagationState, payload: IntArray, atTrail: Int): IntArray? {
        if (payload[0] == BELOW || payload[0] == ABOVE) {
            val bound = (payload[1].toLong() shl 32) or (payload[2].toLong() and 0xFFFFFFFFL)
            return passed(state, payload[0] == BELOW, bound, atTrail)
        }
        val values = LongArray((payload.size - 1) / 2) { k ->
            (payload[1 + 2 * k].toLong() shl 32) or (payload[2 + 2 * k].toLong() and 0xFFFFFFFFL)
        }
        return supports(state, payload[0], values, atTrail)
    }

    // Each lost support cites why it was gone at [atTrail]: a bound it lay past, or its own carve. The cited side is
    // one variable, so its past domain is read once and each bound's literal is looked up once.
    private fun supports(state: PropagationState, side: Int, values: LongArray, atTrail: Int): IntArray? {
        val out = LitSet()
        val v = if (side == RESULT) idx else result
        val d = state.domainAt(v, atTrail)
        val root = state.rootDomains[v]
        var belowCited = false
        var aboveCited = false
        fun cite(k: Long) {
            if (k !in root) return
            when {
                k < d.min -> if (belowCited) return else belowCited = true
                k > d.max -> if (aboveCited) return else aboveCited = true
            }
            val lit = state.exclusionLiteral(v, k, atTrail, d)
            if (lit != Lit.NONE) out.add(lit)
        }
        for (value in values) {
            if (side == RESULT) {
                val id = idFor(value)
                if (id >= 0) for (pos in positionsOfId[id]) cite(indexOffset + pos.toLong())
            } else {
                val pos = value - indexOffset
                if (pos in 0 until len) cite(arr[pos.toInt()])
            }
        }
        return out.toArrayOrNull()
    }

    // Why result held no constant below [bound] ([below]), or above it, at [atTrail]: each such constant had lost
    // every position holding it from idx, or else had left result itself. The positions are preferred, since idx is
    // what the search decides and what the result follows from.
    private fun passed(state: PropagationState, below: Boolean, bound: Long, atTrail: Int): IntArray? {
        val out = LitSet()
        val dIdx = state.domainAt(idx, atTrail)
        val dRes = state.domainAt(result, atTrail)
        val rootIdx = state.rootDomains[idx]
        val rootRes = state.rootDomains[result]
        val idxCite = BoundOnce(state, idx, atTrail, dIdx)
        val resCite = BoundOnce(state, result, atTrail, dRes)
        for (id in 0 until numValues) {
            val w = valueOfId[id]
            if (if (below) w >= bound else w <= bound) continue
            if (w !in rootRes) continue
            val positions = positionsOfId[id]
            if (positionsGone(state, positions, atTrail, dIdx)) {
                citePositions(positions, rootIdx, idxCite, out)
            } else {
                resCite.cite(w, out)
            }
        }
        return out.toArrayOrNull()
    }

    // The per-value scans are separate methods so the value loop compiles small.
    private fun positionsGone(state: PropagationState, positions: IntArray, atTrail: Int, dIdx: IntDomain): Boolean =
        positions.none { state.inDomainAt(idx, indexOffset + it.toLong(), atTrail, dIdx) }

    private fun citePositions(positions: IntArray, rootIdx: IntDomain, idxCite: BoundOnce, out: LitSet) {
        for (pos in positions) {
            val iv = indexOffset + pos.toLong()
            if (iv in rootIdx) idxCite.cite(iv, out)
        }
    }

    // Cites why a value of [v] was out of its past domain [d] at [atTrail], each bound's literal at most once.
    private class BoundOnce(
        private val state: PropagationState,
        private val v: Int,
        private val atTrail: Int,
        private val d: IntDomain,
    ) {
        private var belowCited = false
        private var aboveCited = false

        fun cite(k: Long, out: LitSet) {
            when {
                k < d.min -> if (belowCited) return else belowCited = true
                k > d.max -> if (aboveCited) return else aboveCited = true
            }
            val lit = state.exclusionLiteral(v, k, atTrail, d)
            if (lit != Lit.NONE) out.add(lit)
        }
    }

    private class LitSet {
        private val seen = IntHashSet()
        private val lits = IntArrayList()

        fun add(lit: Int) {
            if (seen.add(lit)) lits.add(lit)
        }

        fun toArrayOrNull(): IntArray? = if (lits.size == 0) null else lits.toIntArray()
    }

    private companion object {
        val EMPTY = LongArrayList()
        const val RESULT = 0
        const val INDEX = 1
        const val BELOW = 2
        const val ABOVE = 3
    }
}
