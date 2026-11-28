package com.eignex.klause.propagation

import com.eignex.klause.ir.IntDomain
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList

// Column-wise undo journal: each record captures a cell's prior value, so a pop costs O(changes since the mark)
// rather than a snapshot's O(numVars). Tags: 0 bool pin (prior is always unassigned; int columns are padding),
// 1 int change (the full prior int-var state), 2 interior carve (just the carved value). Atom slots have their own
// trail in [AtomStore].
internal class UndoLog {
    val tag = IntArrayList()
    val varId = IntArrayList()
    val level = IntArrayList() // int: prior intLevel
    val minLvl = IntArrayList() // int: prior intMinLevel
    val maxLvl = IntArrayList() // int: prior intMaxLevel
    val minReason = IntArrayList() // int: prior intMinReason
    val maxReason = IntArrayList() // int: prior intMaxReason
    val carved = LongArrayList() // long: carved value (tag 2); padding otherwise
    val domain = ArrayList<IntDomain?>() // int: prior intDomains[v]
    val minAnt = ArrayList<IntArray?>() // int: prior intMinAntecedents
    val maxAnt = ArrayList<IntArray?>() // int: prior intMaxAntecedents
    val holeHistLen = IntArrayList() // int: prior holeHist length for the var

    // Kept apart from the domain columns so the hot mutation path pays nothing; each cell holds its own typed
    // prior-value stack, so restore is box-free.
    val revTrail = ArrayList<Trailed>()

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
