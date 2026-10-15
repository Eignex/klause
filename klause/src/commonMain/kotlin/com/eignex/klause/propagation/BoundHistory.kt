package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

// The interior-hole carve record: the per-var (value, level, reason) of each interior hole carved
// out of a variable's domain during search. Bound atoms carry their establishment level/reason on
// their own trail slots ([AtomStore.lvl] / [AtomStore.ant]); this record is the
// equivalent for an eq atom ruled out by a hole — it answers the level and reason of an eq atom
// materialized after its value was carved, when no trail slot was stamped at carve time. Pushed by
// [pushHoleHist], truncated on backtrack alongside the carve, read by [holeReasonFor] /
// [holeLevelFor] / [holeHistHas].

/** Reason for the interior carve of `k` from `v`'s domain, as recorded (possibly lazy, see [reasonOf]); null =
 *  bake-time fact. */
internal fun PropagationState.holeReasonFor(v: Int, k: Long): IntArray? {
    if (rootHoleHist[v]?.contains(k) == true) return null
    val vals = holeHistVal[v] ?: return null
    for (i in 0 until vals.size) if (vals[i] == k) return requireNotNull(holeHistAnt[v])[i]
    return null
}

/** True iff `k` is on `v`'s interior-carve record. Since the record is truncated on every undo,
 *  a hit means `k` is excluded *and* was carved as a pure-interior hole on the current
 *  path — so [holeReasonFor] / [holeLevelFor] hold its real (other-variable) reason and carve
 *  level. Lets the conflict derivation prefer that over the same-var complementary-bound citation
 *  that otherwise cycles for a value far from the live bound. */
internal fun PropagationState.holeHistHas(v: Int, k: Long): Boolean {
    if (rootHoleHist[v]?.contains(k) == true) return true
    val vals = holeHistVal[v] ?: return false
    for (i in 0 until vals.size) if (vals[i] == k) return true
    return false
}

internal fun PropagationState.pushHoleHist(v: Int, value: Long, level: Int, ant: IntArray?) {
    if (!undoLogging && undo.size == 0 && level == 0 && ant == null && !holeHistHas(v, value)) {
        (rootHoleHist[v] ?: RootHoleHistory().also { rootHoleHist[v] = it }).add(value)
        return
    }
    val vals = holeHistVal[v] ?: LongArrayList(initialCapacity = 4).also { holeHistVal[v] = it }
    val lvls = holeHistLvl[v] ?: IntArrayList(initialCapacity = 4).also { holeHistLvl[v] = it }
    val ants = holeHistAnt[v] ?: ArrayList<IntArray?>(4).also { holeHistAnt[v] = it }
    val poss = holeHistPos[v] ?: IntArrayList(initialCapacity = 4).also { holeHistPos[v] = it }
    vals.add(value)
    lvls.add(level)
    ants.add(ant)
    poss.add(undo.size)
}

/**
 * The undo-log size when interior value `k` was carved out of `v`'s domain on the current path, or -1 when no
 * carve of it is on record: a root hole, a value past a bound, or one a survivor restriction dropped as an
 * unconditional fact.
 */
internal fun PropagationState.carvedAt(v: Int, k: Long): Int {
    if (rootHoleHist[v]?.contains(k) == true) return 0
    val vals = holeHistVal[v] ?: return -1
    val poss = requireNotNull(holeHistPos[v])
    for (i in 0 until vals.size) if (vals[i] == k) return poss[i]
    return -1
}

/** Level at which interior value `k` was carved out of `v`'s domain. `0` when no
 *  search-time carve is on record — the hole then predates the search (bake-time
 *  propagation), which is a root fact. */
internal fun PropagationState.holeLevelFor(v: Int, k: Long): Int {
    if (rootHoleHist[v]?.contains(k) == true) return 0
    val vals = holeHistVal[v] ?: return 0
    val lvls = holeHistLvl[v] ?: error("holeHistLvl[$v] missing while holeHistVal present")
    for (i in 0 until vals.size) if (vals[i] == k) return lvls[i]
    return 0
}

internal class RootHoleHistory {
    private class Run(var first: Long, var last: Long)
    private val runs = ArrayList<Run>()

    operator fun contains(value: Long): Boolean {
        val before = insertionPoint(value) - 1
        return before >= 0 && value <= runs[before].last
    }

    fun add(value: Long) {
        val index = insertionPoint(value)
        val before = runs.getOrNull(index - 1)
        if (before != null && value <= before.last) return
        val after = runs.getOrNull(index)
        val joinsBefore = before != null && before.last != Long.MAX_VALUE && before.last + 1L == value
        val joinsAfter = after != null && value != Long.MAX_VALUE && value + 1L == after.first
        when {
            joinsBefore && joinsAfter -> {
                checkNotNull(before).last = checkNotNull(after).last
                runs.removeAt(index)
            }
            joinsBefore -> checkNotNull(before).last = value
            joinsAfter -> checkNotNull(after).first = value
            else -> runs.add(index, Run(value, value))
        }
    }

    private fun insertionPoint(value: Long): Int {
        var low = 0
        var high = runs.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (runs[middle].first <= value) low = middle + 1 else high = middle
        }
        return low
    }
}
