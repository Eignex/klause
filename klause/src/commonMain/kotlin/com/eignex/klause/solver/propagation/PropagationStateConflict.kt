package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/** Max decision level of any variable in `boolVars` / `intVars`. Used by the driver to
 *  set `currentLevel` before each factor invocation.
 *
 *  No variable's level can exceed the number of decisions pushed so far (`cap`); once
 *  the running max reaches that ceiling, the remaining vars can't raise it, so we stop
 *  early. This is an exact short-circuit (same result, fewer reads) — it mainly trims
 *  the scan for large-arity global constraints that fire often during search. */
internal fun PropagationState.maxLevelForVars(boolVars: IntArray, intVars: IntArray): Int {
    // No live level can exceed the number of decisions pushed so far; a stored level
    // above that is a stale advisory left by a pop and must be clamped, or it poisons
    // currentLevel and every pin stamped from it.
    val cap = levelToDecisionVar.size
    var max = 0
    for (v in boolVars) {
        // boolVars may include atom-var ids when a Clause has atom-lits; dispatch.
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
        val l = intLevel[v]
        if (l > max) {
            max = l
            if (max >= cap) return cap
        }
    }
    return max
}

/** Variant that also folds in atom-lit levels for a Clause's literals — used for
 *  learned clauses that reference atom-vars, where the relevant decision level isn't
 *  captured by `boolVars` / `intVars` alone. */
internal fun PropagationState.maxLevelForClause(literals: IntArray): Int {
    // Clamped to the live decision count for the same reason as [maxLevelForVars].
    val cap = levelToDecisionVar.size
    var max = 0
    for (lit in literals) {
        val v = Lit.variable(lit)
        val l = if (v < problem.numBoolVars) boolLevel[v] else atomLevelForConflict(v - problem.numBoolVars)
        if (l > max) {
            max = l
            if (max >= cap) return cap
        }
    }
    return max
}

/** Collect every decision level touched by `boolVars` / `intVars` — the factor's view of
 *  who's responsible. Used when a factor returns `false` without explicitly setting
 *  [PropagationState.conflictLevels].
 *
 *  Atom-lit dispatch: `boolVars` may legitimately contain virtual atom-var ids when the
 *  failing factor is a learned Clause whose literals reference atom-lits (encoded as
 *  `Lit.make(v, ...)` with `v >= problem.numBoolVars`). Those map into `atomLevel`,
 *  not [PropagationState.boolLevel] — mirrors the [maxLevelForVars] dispatch a few lines above. */
internal fun PropagationState.collectLevelsForVars(boolVars: IntArray, intVars: IntArray): IntArray {
    // Dedup levels in a reused primitive set (no per-conflict HashSet / Int boxing), then
    // materialize a plain IntArray — this is on the per-conflict path.
    levelScratch.clear()
    val numBool = problem.numBoolVars
    for (v in boolVars) {
        val l = if (v < numBool) boolLevel[v] else atomLevelForConflict(v - numBool)
        if (l > 0) levelScratch.add(l)
    }
    for (v in intVars) {
        val l = intLevel[v]
        if (l > 0) levelScratch.add(l)
    }
    return levelScratch.toIntArray()
}

/** Decode [levels] (a subset of pushed decision levels) into the bool decision vars at
 *  those levels. */
internal fun PropagationState.extractConflictBools(levels: IntArray): IntArray {
    if (levels.isEmpty()) return EmptyIntArray
    val out = IntHashSet(levels.size)
    for (lvl in levels) {
        if (lvl <= 0 || lvl > levelToDecisionVar.size) continue
        val encoded = levelToDecisionVar[lvl - 1]
        if (encoded < problem.numBoolVars) out.add(encoded)
    }
    return out.toIntArray()
}

/** Decode [levels] into the int decision vars at those levels. */
internal fun PropagationState.extractConflictInts(levels: IntArray): IntArray {
    if (levels.isEmpty()) return EmptyIntArray
    val out = IntHashSet(levels.size)
    for (lvl in levels) {
        if (lvl <= 0 || lvl > levelToDecisionVar.size) continue
        val encoded = levelToDecisionVar[lvl - 1]
        if (encoded >= problem.numBoolVars) out.add(encoded - problem.numBoolVars)
    }
    return out.toIntArray()
}

/**
 * BFS the propagation graph backwards from [PropagationState.conflictSeedFactors] (factors directly
 * implicated in a contradiction) through the per-var reason arrays, collecting every
 * factor whose firing transitively contributed. Each visited factor F is expanded by
 * walking its `boolVars` / `intVars`: for each variable, the factor (if any) that
 * forced the current value / domain bound is added to the frontier. Returns the full
 * factor-level core, or the empty set when no seed was recorded (e.g. seed-assumption
 * contradictions that never reached a factor).
 *
 * Two-sided narrowing is handled because [PropagationState.intMinReason] and
 * [PropagationState.intMaxReason] are tracked separately and both endpoints are walked for every
 * int var.
 */
internal fun PropagationState.extractConflictFactors(): IntArray {
    if (conflictSeedFactors.isEmpty()) return EmptyIntArray
    // Primitive BFS over the propagation graph: [out] dedups reached factor ids, [frontier]
    // is a grow-only worklist walked by a head index (no boxing, no per-step dequeue alloc).
    val out = IntHashSet(conflictSeedFactors.size * 2)
    val frontier = IntArrayList(conflictSeedFactors.size)
    conflictSeedFactors.forEach { fid ->
        out.add(fid)
        frontier.add(fid)
    }
    var head = 0
    while (head < frontier.size) {
        // factorAt routes learned-clause ids (≥ problem.numFactors) to the session's clause
        // registry — conflicts can name a learned clause as their failing factor.
        val f = factorAt(frontier.get(head++))
        for (v in f.boolVars) {
            // Skip atom-encoded literal ids (≥ numBoolVars) — their causation is captured
            // through intMinReason / intMaxReason on the underlying int var, expanded below.
            if (v >= problem.numBoolVars) continue
            val r = boolReason[v]
            if (r >= 0 && out.add(r)) frontier.add(r)
        }
        for (v in f.intVars) {
            val rMin = intMinReason[v]
            if (rMin >= 0 && out.add(rMin)) frontier.add(rMin)
            val rMax = intMaxReason[v]
            if (rMax >= 0 && out.add(rMax)) frontier.add(rMax)
        }
    }
    return out.toIntArray()
}
