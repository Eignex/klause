package com.eignex.klause.solver.propagation

import com.eignex.klause.util.IntArrayList

/** Per-var atom index, segregated by kind and sorted ascending by threshold. A bound
 *  move flips a contiguous threshold range per kind, so wakeups visit exactly the
 *  flipped atoms via binary search. */
internal class VarAtomIndex {
    val geKeys = IntArrayList()
    val geIds = IntArrayList()
    val leKeys = IntArrayList()
    val leIds = IntArrayList()
    val eqKeys = IntArrayList()
    val eqIds = IntArrayList()

    fun insert(kind: AtomKind, k: Int, id: Int) {
        val keys = keysOf(kind)
        val ids = idsOf(kind)
        val at = keys.lowerBound(k)
        keys.insertAt(at, k)
        ids.insertAt(at, id)
    }

    fun keysOf(kind: AtomKind): IntArrayList = when (kind) {
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
