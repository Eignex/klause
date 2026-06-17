package com.eignex.klause.solver.propagation

import com.eignex.klause.util.IntArrayList

// The per-var bound histories (minHist/maxHist) that derived atom levels/reasons at conflict
// time are GONE: order literals are now trail-resident and carry their establishment
// level/reason on their own slots (see [PropagationState.atomLvl] / [PropagationState.atomAnt],
// maintained by [wakeAtom] / [resetAtomTrailFor] / [reconstructCurrentBoundLevel]). The only
// surviving per-var history is the interior-hole carve record, which still answers the level /
// reason of an eq atom ruled out by a hole materialized after the carve.

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

/** True iff every decision on the trail so far is a bool decision (no int pin
 *  decisions). Lets conflict-reason fallbacks emit a sound "negate the current
 *  bool partial assignment" nogood without needing int-bound literals — the clause
 *  is sound exactly when no int decision is partly responsible for the conflict. */
internal fun PropagationState.allDecisionsAreBool(): Boolean {
    val numBool = problem.numBoolVars
    for (lvl in 0 until levelToDecisionVar.size) {
        if (levelToDecisionVar[lvl] >= numBool) return false
    }
    return true
}
