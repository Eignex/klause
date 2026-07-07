package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/** Per-var atom index, segregated by kind and sorted ascending by threshold. A bound
 *  move flips a contiguous threshold range per kind, so wakeups visit exactly the
 *  flipped atoms via binary search. */
internal class VarAtomIndex {
    val geKeys = LongArrayList()
    val geIds = IntArrayList()
    val leKeys = LongArrayList()
    val leIds = IntArrayList()
    val eqKeys = LongArrayList()
    val eqIds = IntArrayList()

    fun insert(kind: AtomKind, k: Long, id: Int) {
        val keys = keysOf(kind)
        val ids = idsOf(kind)
        val at = keys.lowerBound(k)
        keys.insertAt(at, k)
        ids.insertAt(at, id)
    }

    /** Atom id for threshold [k] of [kind], or -1 if not materialized. */
    fun find(kind: AtomKind, k: Long): Int {
        val keys = keysOf(kind)
        val at = keys.lowerBound(k)
        return if (at < keys.size && keys[at] == k) idsOf(kind)[at] else -1
    }

    fun keysOf(kind: AtomKind): LongArrayList = when (kind) {
        AtomKind.GE -> geKeys
        AtomKind.LE -> leKeys
        AtomKind.EQ -> eqKeys
    }

    fun idsOf(kind: AtomKind): IntArrayList = when (kind) {
        AtomKind.GE -> geIds
        AtomKind.LE -> leIds
        AtomKind.EQ -> eqIds
    }
}
