package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/** Per-var atom index, segregated by kind and sorted ascending by threshold. A bound
 *  move flips a contiguous threshold range per kind, so wakeups visit exactly the
 *  flipped atoms via binary search. */
internal class VarAtomIndex {
    internal val ge = AtomRun()
    internal val le = AtomRun()
    internal val eq = AtomRun()

    fun insert(kind: AtomKind, k: Long, id: Int) = runOf(kind).insert(k, id)

    /** Atom id for threshold [k] of [kind], or -1 if not materialized. */
    fun find(kind: AtomKind, k: Long): Int = runOf(kind).find(k)

    /** The kind's thresholds as one ordered array, staging folded in. Prefer [AtomRun.visitRange] on a
     *  hot path: this has to merge, and merging on every read is what made an earlier attempt slower. */
    fun keysOf(kind: AtomKind): LongArrayList = runOf(kind).let {
        it.flush()
        it.mainKeys
    }

    /** Atom ids parallel to [keysOf]. */
    fun idsOf(kind: AtomKind): IntArrayList = runOf(kind).let {
        it.flush()
        it.mainIds
    }

    fun runOf(kind: AtomKind): AtomRun = when (kind) {
        AtomKind.GE -> ge
        AtomKind.LE -> le
        AtomKind.EQ -> eq
    }
}

/**
 * One kind's `(threshold → atom id)` run: a sorted array plus a small sorted staging buffer.
 *
 * A single sorted array costs an O(n) element shift per insertion, which is quadratic over a run and was
 * measured dominating search on wide domains — bound tightening allocates an order atom at every distinct
 * threshold it passes, so the index grows by insertions into the middle. Staging bounds that shift: an
 * insert only reorders the buffer, which merges into the main array once it reaches `√n`.
 *
 * The staged entries are never materialized into one array for reading. An earlier attempt merged on
 * every read and was *worse* — the workload interleaves insert and scan (a bound move both allocates
 * atoms and wakes a range), so the buffer was non-empty at almost every scan and the O(n) merge simply
 * moved the quadratic term to the scan side. [visitRange] therefore walks the two sorted runs together.
 */
internal class AtomRun {
    internal val mainKeys = LongArrayList()
    internal val mainIds = IntArrayList()
    internal val pendKeys = LongArrayList()
    internal val pendIds = IntArrayList()

    fun insert(k: Long, id: Int) {
        val at = pendKeys.lowerBound(k)
        pendKeys.insertAt(at, k)
        pendIds.insertAt(at, id)
        if (pendKeys.size >= mergeThreshold()) merge()
    }

    fun find(k: Long): Int {
        val at = mainKeys.lowerBound(k)
        if (at < mainKeys.size && mainKeys[at] == k) return mainIds[at]
        val p = pendKeys.lowerBound(k)
        return if (p < pendKeys.size && pendKeys[p] == k) pendIds[p] else -1
    }

    /**
     * Atom id of the greatest threshold strictly below [k], or -1. Searches both runs and takes the
     * nearer, so a neighbour lookup never forces a merge — the caller runs on every atom allocation.
     */
    fun below(k: Long): Int {
        val i = mainKeys.lowerBound(k)
        val j = pendKeys.lowerBound(k)
        if (i == 0 && j == 0) return -1
        if (j == 0) return mainIds[i - 1]
        if (i == 0) return pendIds[j - 1]
        return if (mainKeys[i - 1] >= pendKeys[j - 1]) mainIds[i - 1] else pendIds[j - 1]
    }

    /** Atom id of the least threshold strictly above [k], or -1. Merge-free, like [below]. */
    fun above(k: Long): Int {
        var i = mainKeys.lowerBound(k)
        while (i < mainKeys.size && mainKeys[i] <= k) i++
        var j = pendKeys.lowerBound(k)
        while (j < pendKeys.size && pendKeys[j] <= k) j++
        val hasMain = i < mainKeys.size
        val hasPend = j < pendKeys.size
        if (!hasMain && !hasPend) return -1
        if (!hasPend) return mainIds[i]
        if (!hasMain) return pendIds[j]
        return if (mainKeys[i] <= pendKeys[j]) mainIds[i] else pendIds[j]
    }

    /** `√n`, floored to a small minimum so a short run does not merge on every insert. */
    private fun mergeThreshold(): Int {
        var s = MIN_PENDING
        while (s * s < mainKeys.size) s++
        return s
    }

    /** Fold the staged entries into the main run, back to front so each lands in one pass. */
    fun flush() = merge()

    private fun merge() {
        val p = pendKeys.size
        if (p == 0) return
        val n = mainKeys.size
        var i = n - 1
        var j = p - 1
        var w = n + p - 1
        repeat(p) {
            mainKeys.add(0L)
            mainIds.add(0)
        }
        while (j >= 0) {
            if (i >= 0 && mainKeys[i] > pendKeys[j]) {
                mainKeys[w] = mainKeys[i]
                mainIds[w] = mainIds[i]
                i--
            } else {
                mainKeys[w] = pendKeys[j]
                mainIds[w] = pendIds[j]
                j--
            }
            w--
        }
        pendKeys.clear()
        pendIds.clear()
    }

    internal companion object {
        /** Below this the array shift is cheaper than the bookkeeping. */
        const val MIN_PENDING = 8
    }
}

/**
 * Visit every atom id whose threshold lies in `[from, to]`, ascending. Walks the main run and the staging
 * buffer in lockstep so a scan never forces a merge — both are sorted, so the merge is free at read time.
 */
internal inline fun AtomRun.visitRange(from: Long, to: Long, action: (atomId: Int) -> Unit) {
    if (to < from) return
    var i = mainKeys.lowerBound(from)
    var j = pendKeys.lowerBound(from)
    while (true) {
        val hasMain = i < mainKeys.size && mainKeys[i] <= to
        val hasPend = j < pendKeys.size && pendKeys[j] <= to
        if (!hasMain && !hasPend) return
        if (!hasPend || (hasMain && mainKeys[i] <= pendKeys[j])) {
            action(mainIds[i])
            i++
        } else {
            action(pendIds[j])
            j++
        }
    }
}
