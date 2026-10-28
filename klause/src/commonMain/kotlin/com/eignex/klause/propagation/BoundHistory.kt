package com.eignex.klause.propagation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableLongIntMap

// The interior-hole carve record: the per-var (value, level, reason) of each interior hole carved
// out of a variable's domain during search. Bound atoms carry their establishment level/reason on
// their own trail slots ([AtomStore.lvl] / [AtomStore.ant]); this record is the
// equivalent for an eq atom ruled out by a hole — it answers the level and reason of an eq atom
// materialized after its value was carved, when no trail slot was stamped at carve time. Pushed by
// [pushHoleHist], truncated on backtrack alongside the carve, read by [holeReasonFor] /
// [holeLevelFor] / [holeHistHas].

/** Reason for the interior carve of `k` from `v`'s domain, as recorded (possibly lazy, see [reasonOf]);
 *  null denotes a reasonless fact or decision. */
internal fun PropagationState.holeReasonFor(v: Int, k: Long): IntArray? {
    if (rootHoleHist[v]?.contains(k) == true) return null
    val slot = holeHistIndex[v]?.getOrDefault(k, -1) ?: -1
    return if (slot >= 0) requireNotNull(holeHistAnt[v])[slot] else null
}

/** True iff `k` is on `v`'s interior-carve record. Since the record is truncated on every undo,
 *  a hit means `k` is excluded *and* was carved as a pure-interior hole on the current
 *  path — so [holeReasonFor] / [holeLevelFor] hold its real (other-variable) reason and carve
 *  level. Lets the conflict derivation prefer that over the same-var complementary-bound citation
 *  that otherwise cycles for a value far from the live bound. */
internal fun PropagationState.holeHistHas(v: Int, k: Long): Boolean {
    if (rootHoleHist[v]?.contains(k) == true) return true
    return (holeHistIndex[v]?.getOrDefault(k, -1) ?: -1) >= 0 || survivorRestrictionFor(v, k) != null
}

internal class SurvivorRestrictionHistory(
    val before: IntDomain,
    val after: IntDomain,
    val level: Int,
    val position: Int,
    val previous: SurvivorRestrictionHistory?,
)

internal fun PropagationState.recordSurvivorRestriction(v: Int, before: IntDomain, after: IntDomain) {
    val histories = survivorRestrictionHistory ?: HashMap<Int, RevRef<SurvivorRestrictionHistory?>>()
        .also { survivorRestrictionHistory = it }
    val cell = histories.getOrPut(v) { RevRef(this, null) }
    cell.set(SurvivorRestrictionHistory(before, after, currentLevel, undo.size, cell.value))
}

private fun PropagationState.survivorRestrictionFor(v: Int, value: Long): SurvivorRestrictionHistory? {
    var history = survivorRestrictionHistory?.get(v)?.value
    while (history != null) {
        if (value in history.before && value !in history.after) return history
        history = history.previous
    }
    return null
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
    val index = holeHistIndex[v] ?: MutableLongIntMap(initialCapacity = 4).also { holeHistIndex[v] = it }
    if (index.getOrDefault(value, -1) < 0) index.put(value, vals.size)
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
    val slot = holeHistIndex[v]?.getOrDefault(k, -1) ?: -1
    return if (slot >= 0) requireNotNull(holeHistPos[v])[slot] else survivorRestrictionFor(v, k)?.position ?: -1
}

/** Level at which interior value `k` was carved out of `v`'s domain. `0` when no
 *  search-time carve is on record — the hole then predates the search (bake-time
 *  propagation), which is a root fact. */
internal fun PropagationState.holeLevelFor(v: Int, k: Long): Int {
    if (rootHoleHist[v]?.contains(k) == true) return 0
    val slot = holeHistIndex[v]?.getOrDefault(k, -1) ?: -1
    return if (slot >= 0) requireNotNull(holeHistLvl[v])[slot] else survivorRestrictionFor(v, k)?.level ?: 0
}

internal fun PropagationState.truncateHoleHistory(v: Int, size: Int) {
    val values = holeHistVal[v] ?: return
    val index = holeHistIndex[v]
    for (slot in values.size - 1 downTo size) {
        val value = values[slot]
        // The index names the first witness when a value has more than one record.
        if (index != null && index.getOrDefault(value, -1) == slot) index.remove(value)
    }
    values.truncateTo(size)
    holeHistLvl[v]?.truncateTo(size)
    holeHistAnt[v]?.let { reasons -> while (reasons.size > size) reasons.removeAt(reasons.size - 1) }
    holeHistPos[v]?.truncateTo(size)
}

internal class RootHoleHistory {
    private val runs = LongArrayList()

    operator fun contains(value: Long): Boolean {
        val before = insertionPoint(value) - 1
        return before >= 0 && value <= runs[before * 2 + 1]
    }

    fun add(value: Long) {
        val index = insertionPoint(value) * 2
        val before = index - 2
        if (before >= 0 && value <= runs[before + 1]) return
        val joinsBefore = before >= 0 && runs[before + 1] != Long.MAX_VALUE && runs[before + 1] + 1L == value
        val joinsAfter = index < runs.size && value != Long.MAX_VALUE && value + 1L == runs[index]
        when {
            joinsBefore && joinsAfter -> {
                runs[before + 1] = runs[index + 1]
                for (position in index until runs.size - 2) runs[position] = runs[position + 2]
                runs.truncateTo(runs.size - 2)
            }
            joinsBefore -> runs[before + 1] = value
            joinsAfter -> runs[index] = value
            else -> {
                runs.insertAt(index, value)
                runs.insertAt(index + 1, value)
            }
        }
    }

    private fun insertionPoint(value: Long): Int {
        var low = 0
        var high = runs.size / 2
        while (low < high) {
            val middle = (low + high) ushr 1
            if (runs[middle * 2] <= value) low = middle + 1 else high = middle
        }
        return low
    }
}
