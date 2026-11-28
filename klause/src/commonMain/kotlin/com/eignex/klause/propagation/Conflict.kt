package com.eignex.klause.propagation

import com.eignex.klause.factor.litVars
import com.eignex.klause.ir.Lit
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.DynamicBits
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet

private fun PropagationState.factorBoolVars(fid: Int): IntArray = when {
    fid < baseFactorCount -> problem.factors[fid].boolVars
    incremental -> midlife.factors[fid - baseFactorCount].boolVars
    nativeEngine != null -> nativeEngine.literalsOf(fid - baseFactorCount).litVars()
    else -> learned.store[fid - baseFactorCount].boolVars
}

internal fun PropagationState.factorIntVars(fid: Int): IntArray = when {
    fid < baseFactorCount -> problem.factors[fid].intVars
    incremental -> midlife.factors[fid - baseFactorCount].intVars
    nativeEngine != null -> emptyIntArray()
    else -> learned.store[fid - baseFactorCount].intVars
}

internal fun PropagationState.factorVarsConflictLevels(fid: Int): IntArray =
    collectLevelsForVars(factorBoolVars(fid), factorIntVars(fid))

internal fun PropagationState.maxLevelForVars(boolVars: IntArray, intVars: IntArray): Int {
    // No live level can exceed the number of decisions pushed so far; a stored level
    // above that is a stale advisory left by a pop and must be clamped, or it poisons
    // currentLevel and every pin stamped from it.
    val cap = levelToDecisionVar.size
    var max = 0
    // With no decisions pushed (root bake, presolve) the result is pinned to `cap`: skip the O(arity) scan.
    if (max >= cap) return cap
    for (v in boolVars) {
        work++
        // boolVars may include atom-var ids when a Clause has atom-lits.
        val l = if (v < problem.numBoolVars) {
            boolLevel[v]
        } else {
            atomLevelForConflict(v - problem.numBoolVars)
        }
        if (l > max) {
            max = l
            if (max >= cap) return cap
        }
    }
    for (v in intVars) {
        work++
        val l = intLevel[v]
        if (l > max) {
            max = l
            if (max >= cap) return cap
        }
    }
    return max
}

internal fun PropagationState.maxLevelForClause(literals: IntArray): Int {
    // Clamped to the live decision count for the same reason as [maxLevelForVars].
    val cap = levelToDecisionVar.size
    var max = 0
    if (max >= cap) return cap
    for (lit in literals) {
        work++
        val v = Lit.variable(lit)
        val l = if (v < problem.numBoolVars) boolLevel[v] else atomLevelForConflict(v - problem.numBoolVars)
        if (l > max) {
            max = l
            if (max >= cap) return cap
        }
    }
    return max
}

// Used when a factor fails without setting conflictLevels. A learned clause's boolVars can hold atom-var ids.
internal fun PropagationState.collectLevelsForVars(boolVars: IntArray, intVars: IntArray): IntArray {
    levelScratch.clear()
    val numBool = problem.numBoolVars
    for (v in boolVars) {
        work++
        val l = if (v < numBool) boolLevel[v] else atomLevelForConflict(v - numBool)
        if (l > 0) levelScratch.add(l)
    }
    for (v in intVars) {
        work++
        val l = intLevel[v]
        if (l > 0) levelScratch.add(l)
    }
    return levelScratch.toIntArray()
}

internal fun PropagationState.extractConflictBools(levels: IntArray): IntArray {
    if (levels.isEmpty()) return emptyIntArray()
    val out = IntHashSet(levels.size)
    for (lvl in levels) {
        if (lvl <= 0 || lvl > levelToDecisionVar.size) continue
        val encoded = levelToDecisionVar[lvl - 1]
        if (encoded < problem.numBoolVars) out.add(encoded)
    }
    return out.toIntArray()
}

internal fun PropagationState.extractConflictInts(levels: IntArray): IntArray {
    if (levels.isEmpty()) return emptyIntArray()
    val out = IntHashSet(levels.size)
    for (lvl in levels) {
        if (lvl <= 0 || lvl > levelToDecisionVar.size) continue
        val encoded = levelToDecisionVar[lvl - 1]
        if (encoded >= problem.numBoolVars) out.add(encoded - problem.numBoolVars)
    }
    return out.toIntArray()
}

// Every factor whose firing transitively contributed, walking both int endpoint reasons. Empty when no seed was
// recorded, such as a seed-assumption contradiction that never reached a factor.
internal fun PropagationState.extractConflictFactors(): IntArray {
    if (conflictSeedFactors.isEmpty()) return emptyIntArray()
    val scratch = conflictFactorScratch
    scratch.clear()
    val frontier = scratch.frontier
    conflictSeedFactors.forEach { fid ->
        scratch.add(fid)
    }
    var head = 0
    while (head < frontier.size) {
        // Conflicts can name a learned clause as their failing factor; tail ids route like [factorBoolVars].
        val fid = frontier.get(head++)
        val native = nativeEngine
        if (fid >= baseFactorCount && !incremental && native != null) {
            val index = fid - baseFactorCount
            for (i in 0 until native.literalCountOf(index)) {
                val r = boolReason[native.variableOf(index, i)]
                if (r >= 0) scratch.add(r)
            }
        } else {
            for (v in factorBoolVars(fid)) {
                // Atom causation is captured by the underlying integer endpoint reasons.
                if (v >= problem.numBoolVars) continue
                val r = boolReason[v]
                if (r >= 0) scratch.add(r)
            }
        }
        for (v in factorIntVars(fid)) {
            val rMin = intMinReason[v]
            if (rMin >= 0) scratch.add(rMin)
            val rMax = intMaxReason[v]
            if (rMax >= 0) scratch.add(rMax)
        }
    }
    return frontier.toIntArray()
}

internal class ConflictFactorScratch {
    private val seen = DynamicBits()
    val frontier = IntArrayList()

    fun clear() {
        for (i in 0 until frontier.size) seen[frontier[i]] = false
        frontier.clear()
    }

    fun add(fid: Int) {
        if (fid >= seen.size || !seen[fid]) {
            seen.set(fid)
            frontier.add(fid)
        }
    }
}
