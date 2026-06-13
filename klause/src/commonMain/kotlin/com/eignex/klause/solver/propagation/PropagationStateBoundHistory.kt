package com.eignex.klause.solver.propagation

import com.eignex.klause.util.IntArrayList

internal fun PropagationState.pushMinHist(
    v: Int,
    value: Int,
    level: Int,
    antNear: IntArray?,
    antFar: IntArray?,
    requested: Int,
) {
    val vals = minHistVal[v] ?: IntArrayList(initialCapacity = 4).also { minHistVal[v] = it }
    val lvls = minHistLvl[v] ?: IntArrayList(initialCapacity = 4).also { minHistLvl[v] = it }
    val near = minHistAntNear[v] ?: ArrayList<IntArray?>(4).also { minHistAntNear[v] = it }
    val far = minHistAntFar[v] ?: ArrayList<IntArray?>(4).also { minHistAntFar[v] = it }
    val req = minHistReq[v] ?: IntArrayList(initialCapacity = 4).also { minHistReq[v] = it }
    vals.add(value)
    lvls.add(level)
    near.add(antNear)
    far.add(antFar)
    req.add(requested)
}

internal fun PropagationState.pushMaxHist(
    v: Int,
    value: Int,
    level: Int,
    antNear: IntArray?,
    antFar: IntArray?,
    requested: Int,
) {
    val vals = maxHistVal[v] ?: IntArrayList(initialCapacity = 4).also { maxHistVal[v] = it }
    val lvls = maxHistLvl[v] ?: IntArrayList(initialCapacity = 4).also { maxHistLvl[v] = it }
    val near = maxHistAntNear[v] ?: ArrayList<IntArray?>(4).also { maxHistAntNear[v] = it }
    val far = maxHistAntFar[v] ?: ArrayList<IntArray?>(4).also { maxHistAntFar[v] = it }
    val req = maxHistReq[v] ?: IntArrayList(initialCapacity = 4).also { maxHistReq[v] = it }
    vals.add(value)
    lvls.add(level)
    near.add(antNear)
    far.add(antFar)
    req.add(requested)
}

/** Reason for `[v ≥ k]` being true: null (a root/bake fact) when no search-time move
 *  is on record, else the recorded reason of the move that first reached ≥ `k` — the
 *  near set when the move's requested bound already covers `k`, the far (hole-snap
 *  chained) set otherwise. */
internal fun PropagationState.minReasonFor(v: Int, k: Int): IntArray? {
    if (k <= problem.intDomains[v].min) return null
    val vals = minHistVal[v] ?: return null
    val i = vals.lowerBound(k)
    if (i >= vals.size) return null
    return if (k <= requireNotNull(minHistReq[v])[i]) {
        requireNotNull(minHistAntNear[v])[i]
    } else {
        requireNotNull(minHistAntFar[v])[i]
    }
}

/** Reason for `[v ≤ k]` being true; symmetric to [minReasonFor]. */
internal fun PropagationState.maxReasonFor(v: Int, k: Int): IntArray? {
    if (k >= problem.intDomains[v].max) return null
    val vals = maxHistVal[v] ?: return null
    val i = vals.lowerBoundDescending(k)
    if (i >= vals.size) return null
    return if (k >= requireNotNull(maxHistReq[v])[i]) {
        requireNotNull(maxHistAntNear[v])[i]
    } else {
        requireNotNull(maxHistAntFar[v])[i]
    }
}

/** Reason for the interior carve of `k` from `v`'s domain; null = bake-time fact. */
internal fun PropagationState.holeReasonFor(v: Int, k: Int): IntArray? {
    val vals = holeHistVal[v] ?: return null
    for (i in 0 until vals.size) if (vals[i] == k) return requireNotNull(holeHistAnt[v])[i]
    return null
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

/** Level at which `v`'s min *first* reached ≥ `k`. `0` when `k` is within the root domain
 *  (a global fact). Conservative fallback to [PropagationState.intLevel] if history is absent. */
internal fun PropagationState.minLevelForGe(v: Int, k: Int): Int {
    if (k <= problem.intDomains[v].min) return 0
    val vals = minHistVal[v] ?: return maxOf(intLevel[v], 0)
    val lvls = minHistLvl[v] ?: error("minHistLvl[$v] missing while minHistVal present")
    // [minHistVal] is ascending (min rises monotonically), so the first value ≥ k is the
    // lower bound of k — found in O(log n) instead of a linear scan (#97).
    val i = vals.lowerBound(k)
    return if (i < vals.size) lvls[i] else maxOf(intLevel[v], 0)
}

/** Level at which `v`'s max *first* reached ≤ `k`. Symmetric to [minLevelForGe]. */
internal fun PropagationState.maxLevelForLe(v: Int, k: Int): Int {
    if (k >= problem.intDomains[v].max) return 0
    val vals = maxHistVal[v] ?: return maxOf(intLevel[v], 0)
    val lvls = maxHistLvl[v] ?: error("maxHistLvl[$v] missing while maxHistVal present")
    // [maxHistVal] is descending (max falls monotonically); the first value ≤ k is the
    // descending lower bound — O(log n) (#97).
    val i = vals.lowerBoundDescending(k)
    return if (i < vals.size) lvls[i] else maxOf(intLevel[v], 0)
}

/** Loosest (smallest) min-value established at a level strictly below [level]; the root
 *  min when `v` was never tightened before [level]. */
internal fun PropagationState.minBelowLevel(v: Int, level: Int): Int {
    val rootMin = problem.intDomains[v].min
    val vals = minHistVal[v] ?: return rootMin
    val lvls = minHistLvl[v] ?: error("minHistLvl[$v] missing while minHistVal present")
    var best = rootMin
    for (i in 0 until vals.size) {
        if (lvls[i] < level) best = vals[i] else break // lvls non-decreasing → prefix
    }
    return best
}

/** Loosest (largest) max-value established at a level strictly below [level]; the root
 *  max when `v` was never tightened before [level]. */
internal fun PropagationState.maxAboveLevel(v: Int, level: Int): Int {
    val rootMax = problem.intDomains[v].max
    val vals = maxHistVal[v] ?: return rootMax
    val lvls = maxHistLvl[v] ?: error("maxHistLvl[$v] missing while maxHistVal present")
    var best = rootMax
    for (i in 0 until vals.size) {
        if (lvls[i] < level) best = vals[i] else break
    }
    return best
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
