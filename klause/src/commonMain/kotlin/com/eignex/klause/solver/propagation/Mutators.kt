package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList

/** Push every pin in `a` as a fresh decision; return `false` (so [PropagationState.seeded] becomes
 *  `false`) on the first contradiction. Direct primitive-array iteration so the early
 *  exit is a clean `return`. */
internal fun PropagationState.seedAssumptions(a: Assumptions): Boolean {
    val bk = a.boolKeys
    val bv = a.boolValues
    for (i in bk.indices) {
        if (!pinBoolAsDecision(bk[i], bv[i])) return false
    }
    val ik = a.intKeys
    val iv = a.intValues
    for (i in ik.indices) {
        if (!setIntAsDecision(ik[i], iv[i])) return false
    }
    // Non-singleton bound tightenings ride at the same decision level as int pins —
    // they're seed-time inputs, not propagated conclusions. Each takes its own level
    // so the conflict analyzer can attribute backjumps to the specific tightening.
    val minK = a.intMinKeys
    val minV = a.intMinValues
    for (i in minK.indices) {
        levelToDecisionVar.add(problem.numBoolVars + minK[i])
        currentLevel = levelToDecisionVar.size
        currentFactor = -1
        if (!tightenIntMinImpl(minK[i], minV[i], null)) return false
    }
    val maxK = a.intMaxKeys
    val maxV = a.intMaxValues
    for (i in maxK.indices) {
        levelToDecisionVar.add(problem.numBoolVars + maxK[i])
        currentLevel = levelToDecisionVar.size
        currentFactor = -1
        if (!tightenIntMaxImpl(maxK[i], maxV[i], null)) return false
    }
    val holeIds = a.intHoleVarIds
    val holeVals = a.intHoleValues
    for (i in holeIds.indices) {
        levelToDecisionVar.add(problem.numBoolVars + holeIds[i])
        currentLevel = levelToDecisionVar.size
        currentFactor = -1
        if (!excludeIntValueImpl(holeIds[i], holeVals[i], null)) return false
    }
    return true
}

/**
 * Push a bool var as a new decision: bumps the level and pins it. Used by the driver to
 * seed input assumptions and by [PropagationSession] to push branches.
 */
internal fun PropagationState.pinBoolAsDecision(v: Int, value: Boolean): Boolean {
    levelToDecisionVar.add(v)
    currentLevel = levelToDecisionVar.size
    currentFactor = -1
    return pinBoolImpl(v, value, antecedents = null)
}

/** Push an int var as a new decision. */
internal fun PropagationState.setIntAsDecision(v: Int, value: Int): Boolean {
    levelToDecisionVar.add(problem.numBoolVars + v)
    currentLevel = levelToDecisionVar.size
    currentFactor = -1
    return setIntImpl(v, value, null)
}

/**
 * Push an int upper-bound tightening (`v ≤ hi`) as a new decision. Unlike
 * [setIntAsDecision], this records a *single* bound atom at the new decision level, so
 * conflicts seeded by it have a single 1UIP literal there (an equality pin contributes
 * two same-level bound atoms that 1UIP cannot collapse). The caller must ensure `hi`
 * strictly narrows the domain (`hi in d.min until d.max`) so the level is non-empty.
 */
internal fun PropagationState.setIntMaxAsDecision(v: Int, hi: Int): Boolean {
    levelToDecisionVar.add(problem.numBoolVars + v)
    currentLevel = levelToDecisionVar.size
    currentFactor = -1
    return tightenIntMaxImpl(v, hi, null)
}

/** Push an int lower-bound tightening (`v ≥ lo`) as a new decision. See [setIntMaxAsDecision]. */
internal fun PropagationState.setIntMinAsDecision(v: Int, lo: Int): Boolean {
    levelToDecisionVar.add(problem.numBoolVars + v)
    currentLevel = levelToDecisionVar.size
    currentFactor = -1
    return tightenIntMinImpl(v, lo, null)
}

internal fun PropagationState.pinBoolImpl(v: Int, value: Boolean, antecedents: IntArray?): Boolean {
    // Read the packed bits directly rather than through the boxing `boolValues[v]`
    // accessor — this runs once per pin (≈ once per propagation) and the `Boolean?`
    // box dominated the BCP CPU profile.
    if (boolAssigned.get(v)) {
        if (boolValueBits.get(v) == value) return true
        // Conflict — record levels of both contributors, and seed the factor core with
        // the prior pin's reason (whichever factor forced `cur`, if any) plus the
        // currently-running factor (if any). Also record [v] so the analyzer can
        // synthesise a clause-form seed from the prior pin's antecedents when this
        // is a decision-level vs prior-pin contradiction (currentFactor == -1).
        recordConflictLevels(boolLevel[v], currentLevel)
        seedConflictFactor(boolReason[v])
        seedConflictFactor(currentFactor)
        if (currentFactor < 0) lastDecisionConflictVar = v
        return false
    }
    if (undoLogging) logBoolPin(v)
    if (currentFactor >= 0) propagations++
    if (value) boolValueBits.set(v) else boolValueBits.clear(v)
    boolAssigned.set(v)
    boolLevel[v] = currentLevel
    boolReason[v] = currentFactor
    noteLearnedUse(currentFactor) // a learned clause that forces a unit counts as reused (#201)
    boolAntecedents[v] = antecedents
    boolPinOrder.add(v)
    dirtyBools.addLast(v)
    return true
}

/** Append the prior bound atom's clause-form literal to [base] when the prior bound was
 *  itself search-derived ([cite]); a root-level bound is a global fact and needs none. */
internal fun PropagationState.appendPriorBound(priorLit: Int, cite: Boolean, base: IntArray?): IntArray? {
    if (!cite) return base
    if (base != null && base.contains(priorLit)) return base
    val out = IntArray((base?.size ?: 0) + 1)
    base?.copyInto(out)
    out[out.size - 1] = priorLit
    return out
}

/**
 * Antecedents for a bound move that snapped past interior holes. The supplied [base] reason
 * justifies the *requested* bound only; when the hole-aware domain update lands the endpoint
 * further (because the values in between are excluded), the deduction additionally rests on
 * those exclusions. Each value in [crossed] that exists in the root domain was carved out
 * during search, so its positive eq-atom literal joins the reason — omitting it makes the
 * recorded implication stronger than what was actually derived, and clauses learned through
 * it can prune feasible assignments. Values absent from the root domain are global facts and
 * need no citation.
 */
internal fun PropagationState.antecedentsAcrossHoles(v: Int, crossed: IntRange, base: IntArray?): IntArray? {
    var out: IntArrayList? = null
    val orig = problem.intDomains[v]
    for (value in crossed) {
        if (value in orig) {
            val o = out ?: IntArrayList().also { fresh ->
                out = fresh
                base?.forEach { fresh.add(it) }
            }
            o.add(Lit.make(atomVarEq(v, value), true))
        }
    }
    return out?.toIntArray() ?: base
}

internal fun PropagationState.tightenIntMinImpl(v: Int, lo: Int, antecedents: IntArray?): Boolean {
    val d = intDomains[v]
    if (lo <= d.min) return true
    if (lo > d.max) {
        // Two-sided narrowing emptied the domain: the existing upper bound came from
        // `intMaxReason[v]`, and `currentFactor` is the one trying to push the lower
        // past it. Both go into the core seed.
        recordConflictLevels(intLevel[v], currentLevel)
        seedConflictFactor(intMaxReason[v])
        seedConflictFactor(currentFactor)
        return false
    }
    if (undoLogging) logIntChange(v)
    if (currentFactor >= 0) propagations++
    // Preserve interior holes via the sparse-aware constructor path. For contiguous
    // domains this is functionally identical to `IntDomain(lo, d.max)`.
    val newDomain = d.withMinAtLeast(lo)
    // A landing value inside a hole snaps the min further. The snapped bound rests on
    // the requested bound plus the crossed holes; without the requested-bound atom in
    // the chain a decision's contribution vanishes from conflict analysis.
    val ant = if (newDomain.min > lo) {
        appendPriorBound(
            Lit.make(atomVarGe(v, lo), false),
            lo > problem.intDomains[v].min,
            antecedentsAcrossHoles(v, lo until newDomain.min, antecedents),
        )
    } else {
        antecedents
    }
    intDomains[v] = newDomain
    intLevel[v] = maxOf(intLevel[v], currentLevel)
    intMinReason[v] = currentFactor
    intMinAntecedents[v] = ant
    // Record the post-snap bound, not the requested one: when the landing value sits in
    // a hole the actual min jumps further, and minLevelForGe must attribute every value
    // in the jumped-over range to this level.
    pushMinHist(v, newDomain.min, currentLevel, antecedents, ant, lo)
    dirtyInts.addLast(v)
    propagateAtomsForVar(v, antNear = antecedents, antFar = ant, reqMin = lo, oldMin = d.min, oldMax = d.max)
    return true
}

internal fun PropagationState.tightenIntMaxImpl(v: Int, hi: Int, antecedents: IntArray?): Boolean {
    val d = intDomains[v]
    if (hi >= d.max) return true
    if (hi < d.min) {
        recordConflictLevels(intLevel[v], currentLevel)
        seedConflictFactor(intMinReason[v])
        seedConflictFactor(currentFactor)
        return false
    }
    if (undoLogging) logIntChange(v)
    if (currentFactor >= 0) propagations++
    val newDomain = d.withMaxAtMost(hi)
    // Snap chaining mirrors [tightenIntMinImpl]: requested-bound atom + crossed holes.
    val ant = if (newDomain.max < hi) {
        appendPriorBound(
            Lit.make(atomVarLe(v, hi), false),
            hi < problem.intDomains[v].max,
            antecedentsAcrossHoles(v, (newDomain.max + 1)..hi, antecedents),
        )
    } else {
        antecedents
    }
    intDomains[v] = newDomain
    intLevel[v] = maxOf(intLevel[v], currentLevel)
    intMaxReason[v] = currentFactor
    intMaxAntecedents[v] = ant
    // Post-snap bound for the same reason as the min side.
    pushMaxHist(v, newDomain.max, currentLevel, antecedents, ant, hi)
    dirtyInts.addLast(v)
    propagateAtomsForVar(v, antNear = antecedents, antFar = ant, reqMax = hi, oldMin = d.min, oldMax = d.max)
    return true
}

/**
 * Punch a hole at [value] in `intDomains[v]`. Three cases:
 *  - `value` not in the current domain → no-op, returns `true`.
 *  - `value` is at the current min or max → equivalent to a one-step bound tighten.
 *  - `value` is interior → the domain transitions to sparse representation. Other
 *    propagators still see the same `min`/`max` until further tightening; the hole
 *    affects `contains(value)` lookups and `forEach` iteration.
 *
 * Returns `false` only when removing [value] would empty the domain (singleton
 * domain whose sole value is [value]). On conflict, seeds the factor core with the
 * level / reason fields already tracked for the min and max sides.
 */
internal fun PropagationState.excludeIntValueImpl(v: Int, value: Int, antecedents: IntArray?): Boolean {
    val d = intDomains[v]
    if (value !in d) return true
    if (d.min == d.max && d.min == value) {
        recordConflictLevels(intLevel[v], currentLevel)
        seedConflictFactor(intMinReason[v])
        seedConflictFactor(intMaxReason[v])
        seedConflictFactor(currentFactor)
        return false
    }
    val interior = value > d.min && value < d.max
    if (undoLogging) {
        if (interior) logIntCarve(v, value) else logIntChange(v)
    }
    if (currentFactor >= 0) propagations++
    val newDomain = d.excludeValue(value)
    // An edge exclusion advances the endpoint: the new bound rests on the *prior* bound, the
    // exclusion itself, and any further holes crossed on the way. The supplied reason only
    // justifies the exclusion, so the prior bound atom and the crossed values' exclusions
    // must join the recorded reason (see [antecedentsAcrossHoles]) — without them the
    // implication is stronger than what was derived and learned clauses can prune feasible
    // assignments. Kept as separate near/far reason sets (the one-step bound move vs. the
    // hole-snapped landing) so each flipped atom can take the weakest sufficient one.
    val antNear = when {
        newDomain.min != d.min -> appendPriorBound(
            Lit.make(atomVarGe(v, d.min), false),
            d.min > problem.intDomains[v].min,
            antecedents,
        )

        newDomain.max != d.max -> appendPriorBound(
            Lit.make(atomVarLe(v, d.max), false),
            d.max < problem.intDomains[v].max,
            antecedents,
        )

        else -> antecedents
    }
    val ant = when {
        newDomain.min != d.min -> antecedentsAcrossHoles(v, (value + 1) until newDomain.min, antNear)
        newDomain.max != d.max -> antecedentsAcrossHoles(v, (newDomain.max + 1) until value, antNear)
        else -> antecedents
    }
    intDomains[v] = newDomain
    intLevel[v] = maxOf(intLevel[v], currentLevel)
    // History upkeep mirrors the tighten paths: an edge carve joins the bound history
    // (this path bypasses tightenInt*Impl), an interior carve the hole history —
    // without the record the level lookups mis-attribute the change.
    when {
        newDomain.min != d.min -> pushMinHist(v, newDomain.min, currentLevel, antNear, ant, value + 1)
        newDomain.max != d.max -> pushMaxHist(v, newDomain.max, currentLevel, antNear, ant, value - 1)
        else -> pushHoleHist(v, value, currentLevel, antecedents)
    }
    // Reason attribution: which side (min/max) "moved" depends on where the hole
    // landed. Pure interior holes don't shift either endpoint; in that case the
    // current factor still becomes the relevant reason for any future propagator
    // walking back through this variable.
    if (newDomain.min != d.min) {
        intMinReason[v] = currentFactor
        intMinAntecedents[v] = ant
    }
    if (newDomain.max != d.max) {
        intMaxReason[v] = currentFactor
        intMaxAntecedents[v] = ant
    }
    dirtyInts.addLast(v)
    when {
        newDomain.min != d.min ->
            propagateAtomsForVar(
                v,
                antNear = antNear,
                antFar = ant,
                reqMin = value + 1,
                oldMin = d.min,
                oldMax = d.max,
            )

        newDomain.max != d.max ->
            propagateAtomsForVar(
                v,
                antNear = antNear,
                antFar = ant,
                reqMax = value - 1,
                oldMin = d.min,
                oldMax = d.max,
            )

        else ->
            propagateAtomsForVar(v, antNear = antecedents, oldMin = d.min, oldMax = d.max, carved = value)
    }
    return true
}

internal fun PropagationState.seedConflictFactor(fid: Int) {
    if (fid < 0) return
    noteLearnedUse(fid) // a learned clause that detects a conflict counts as reused (#201)
    conflictSeedFactors.add(fid)
}

internal fun PropagationState.setIntImpl(v: Int, value: Int, antecedents: IntArray?): Boolean =
    tightenIntMinImpl(v, value, antecedents) && tightenIntMaxImpl(v, value, antecedents)

internal fun PropagationState.recordConflictLevels(a: Int, b: Int) {
    conflictLevels = when {
        a > 0 && b > 0 && a != b -> intArrayOf(a, b)
        a > 0 -> intArrayOf(a)
        b > 0 -> intArrayOf(b)
        else -> EmptyIntArray
    }
}
