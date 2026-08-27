package com.eignex.klause.propagation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/**
 * Column-wise undo journal for [PropagationState] (replaces per-level full-array snapshots).
 *
 * Each mutation that a pop must rewind appends one record capturing the cell's *prior* value. A pop
 * replays records from the top down to a [PropagationState.LevelMark]'s undo size —
 * O(changes-since-mark) instead of a snapshot/restore's O(numVars) per level. Records are stored
 * column-wise across parallel lists (no per-record object alloc). Record kinds by [tag]:
 *  - 0 — bool pin: a bool was assigned (prior state is always unassigned, since `pinBoolImpl` only
 *    proceeds when the var was free), so only [varId] is meaningful; the int/obj columns are padding.
 *  - 1 — int change: a tighten / exclude replaced the var's domain. The full prior int-var state is
 *    recorded so replay restores it exactly even when the same var is narrowed several times within
 *    a level.
 *  - 2 — interior carve, journalled as just the carved value (see `logIntCarve`).
 *
 * Atom-table slots are logged on their own reversible trail (`AtomStore.undoAtomId` and parallels),
 * captured by a [PropagationState.LevelMark]'s atom-undo size and replayed by `undoTo` alongside
 * these records — so an atom-lit forced directly by a clause backtracks even when no bound moved.
 */
internal class UndoLog {
    val tag = IntArrayList()
    val varId = IntArrayList()
    val level = IntArrayList() // int: prior intLevel
    val minLvl = IntArrayList() // int: prior intMinLevel
    val maxLvl = IntArrayList() // int: prior intMaxLevel
    val minReason = IntArrayList() // int: prior intMinReason
    val maxReason = IntArrayList() // int: prior intMaxReason
    val carved = LongArrayList() // long: carved value (tags 2/3); padding otherwise
    val domain = ArrayList<IntDomain?>() // int: prior intDomains[v]
    val minAnt = ArrayList<IntArray?>() // int: prior intMinAntecedents
    val maxAnt = ArrayList<IntArray?>() // int: prior intMaxAntecedents
    val holeHistLen = IntArrayList() // int: prior holeHist length for the var

    /** Separate trail for [Trailed] reversible cells (incremental factor state). Kept apart from the
     *  bool/int columns above so the hot domain-mutation path pays nothing; a
     *  [PropagationState.LevelMark] captures its size, `undoTo` replays it top-down. Each entry is a
     *  cell that changed (the cell holds its own typed prior-value stack), so restore is box-free.
     *  See [Trailed] / `Reversible.kt`. */
    val revTrail = ArrayList<Trailed>()

    /** Number of records; a [PropagationState.LevelMark] captures this. */
    val size: Int get() = tag.size

    fun truncateTo(n: Int) {
        tag.truncateTo(n)
        varId.truncateTo(n)
        level.truncateTo(n)
        minLvl.truncateTo(n)
        maxLvl.truncateTo(n)
        minReason.truncateTo(n)
        maxReason.truncateTo(n)
        carved.truncateTo(n)
        while (domain.size > n) domain.removeAt(domain.size - 1)
        while (minAnt.size > n) minAnt.removeAt(minAnt.size - 1)
        while (maxAnt.size > n) maxAnt.removeAt(maxAnt.size - 1)
        holeHistLen.truncateTo(n)
    }
}
