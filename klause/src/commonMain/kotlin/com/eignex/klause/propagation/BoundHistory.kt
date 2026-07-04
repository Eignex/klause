package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayList

// The interior-hole carve record: the per-var (value, level, reason) of each interior hole carved
// out of a variable's domain during search. Bound atoms carry their establishment level/reason on
// their own trail slots ([AtomStore.lvl] / [AtomStore.ant]); this record is the
// equivalent for an eq atom ruled out by a hole — it answers the level and reason of an eq atom
// materialized after its value was carved, when no trail slot was stamped at carve time. Pushed by
// [pushHoleHist], truncated on backtrack alongside the carve, read by [holeReasonFor] /
// [holeLevelFor] / [holeHistHas].

/** Reason for the interior carve of `k` from `v`'s domain; null = bake-time fact. */
internal fun PropagationState.holeReasonFor(v: Int, k: Int): IntArray? {
    val vals = holeHistVal[v] ?: return null
    for (i in 0 until vals.size) if (vals[i] == k) return requireNotNull(holeHistAnt[v])[i]
    return null
}

/** True iff `k` is on `v`'s interior-carve record. Since the record is truncated on every undo,
 *  a hit means `k` is excluded *and* was carved as a pure-interior hole on the current
 *  path — so [holeReasonFor] / [holeLevelFor] hold its real (other-variable) reason and carve
 *  level. Lets the conflict derivation prefer that over the same-var complementary-bound citation
 *  that otherwise cycles for a value far from the live bound (#671). */
internal fun PropagationState.holeHistHas(v: Int, k: Int): Boolean {
    val vals = holeHistVal[v] ?: return false
    for (i in 0 until vals.size) if (vals[i] == k) return true
    return false
}

internal fun PropagationState.pushHoleHist(v: Int, value: Int, level: Int, ant: IntArray?) {
    val vals = holeHistVal[v] ?: IntArrayList(initialCapacity = 4).also { holeHistVal[v] = it }
    val lvls = holeHistLvl[v] ?: IntArrayList(initialCapacity = 4).also { holeHistLvl[v] = it }
    val ants = holeHistAnt[v] ?: ArrayList<IntArray?>(4).also { holeHistAnt[v] = it }
    vals.add(value)
    lvls.add(level)
    ants.add(ant)
}

/** Level at which interior value `k` was carved out of `v`'s domain. `0` when no
 *  search-time carve is on record — the hole then predates the search (bake-time
 *  propagation), which is a root fact. */
internal fun PropagationState.holeLevelFor(v: Int, k: Int): Int {
    val vals = holeHistVal[v] ?: return 0
    val lvls = holeHistLvl[v] ?: error("holeHistLvl[$v] missing while holeHistVal present")
    for (i in 0 until vals.size) if (vals[i] == k) return lvls[i]
    return 0
}
