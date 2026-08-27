package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.intdomain.intDomainFromSurvivors
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

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
    val minK = a.deductions.intMinKeys
    val minV = a.deductions.intMinValues
    for (i in minK.indices) {
        levelToDecisionVar.add(problem.numBoolVars + minK[i])
        currentLevel = levelToDecisionVar.size
        currentFactor = -1
        if (!tightenIntMinImpl(minK[i], minV[i], null)) return false
    }
    val maxK = a.deductions.intMaxKeys
    val maxV = a.deductions.intMaxValues
    for (i in maxK.indices) {
        levelToDecisionVar.add(problem.numBoolVars + maxK[i])
        currentLevel = levelToDecisionVar.size
        currentFactor = -1
        if (!tightenIntMaxImpl(maxK[i], maxV[i], null)) return false
    }
    val holeIds = a.deductions.intHoleVarIds
    val holeVals = a.deductions.intHoleValues
    for (i in holeIds.indices) {
        levelToDecisionVar.add(problem.numBoolVars + holeIds[i])
        currentLevel = levelToDecisionVar.size
        currentFactor = -1
        if (!excludeIntValueImpl(holeIds[i], holeVals[i], null)) return false
    }
    val setKeys = a.deductions.intSetKeys
    for (i in setKeys.indices) {
        levelToDecisionVar.add(problem.numBoolVars + setKeys[i])
        currentLevel = levelToDecisionVar.size
        currentFactor = -1
        if (!restrictIntToSurvivors(setKeys[i], a.deductions.intSetSurvivorsAt(i))) return false
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
internal fun PropagationState.setIntAsDecision(v: Int, value: Long): Boolean {
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
internal fun PropagationState.setIntMaxAsDecision(v: Int, hi: Long): Boolean {
    levelToDecisionVar.add(problem.numBoolVars + v)
    currentLevel = levelToDecisionVar.size
    currentFactor = -1
    return tightenIntMaxImpl(v, hi, null)
}

/** Push an int lower-bound tightening (`v ≥ lo`) as a new decision. See [setIntMaxAsDecision]. */
internal fun PropagationState.setIntMinAsDecision(v: Int, lo: Long): Boolean {
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
    noteLearnedUse(currentFactor) // a learned clause that forces a unit counts as reused
    boolAntecedents[v] = antecedents
    boolPinOrder.add(v)
    bumpObjectiveBoolBound(v, value)
    // The native-SAT lane sweeps the pin trail directly and never drains dirtyBools; skipping the
    // enqueue keeps that queue from growing unbounded across a solve. The general path still uses it.
    if (nativeEngine == null) dirtyBools.addLast(v)
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

/** Citation cap for a snapped bound's crossed holes; a wider block takes the decision-cut reason. */
private const val MAX_HOLE_CITATIONS = 4096

/** The coarse always-sound reason: [base] plus every decision variable's current pin / root-tightened
 *  bound atoms, negated. See [antecedentsAcrossHoles]'s over-wide fallback. */
private fun PropagationState.decisionCutAntecedents(base: IntArray?): IntArray {
    val lits = IntArrayList()
    base?.forEach { lits.add(it) }
    val numBools = problem.numBoolVars
    for (i in 0 until levelToDecisionVar.size) {
        val dv = levelToDecisionVar[i]
        if (dv < numBools) {
            boolValues[dv]?.let { pin -> lits.add(Lit.make(dv, !pin)) }
        } else {
            val iv = dv - numBools
            val d = intDomains[iv]
            val root = rootDomains[iv]
            if (d.min > root.min) lits.add(Lit.make(atomVarGe(iv, d.min), false))
            if (d.max < root.max) lits.add(Lit.make(atomVarLe(iv, d.max), false))
        }
    }
    return lits.toIntArray()
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
internal fun PropagationState.antecedentsAcrossHoles(v: Int, crossed: LongRange, base: IntArray?): IntArray? {
    var out: IntArrayList? = null
    val orig = rootDomains[v]
    fun cite(value: Long) {
        val o = out ?: IntArrayList().also { fresh ->
            out = fresh
            base?.forEach { fresh.add(it) }
        }
        o.add(Lit.make(atomVarEq(v, value), true))
    }
    // Cite the search-carved values the bound snapped past — those in [crossed] still in the root
    // domain. Walk the root's members inside [crossed] by neighbour steps: both the crossed span and
    // the root can be astronomically wide (a clamped Long domain), but the members visited are
    // bounded by the search-removal events that carved them, so this never scans value-by-value.
    if (!crossed.isEmpty()) {
        val start = if (crossed.first in orig) {
            crossed.first
        } else if (crossed.first < orig.max) {
            orig.higher(crossed.first)
        } else {
            crossed.last + 1 // past the range: nothing to cite
        }
        // Count before citing (citing materializes eq-atoms): a block past the cap gets the coarse
        // decision-cut reason instead — every decision variable's current pins/bounds negated. Those
        // premises are at least as strong as the decisions themselves, and everything derived at this
        // node follows from the decisions, so the implication (and the clauses learned through it)
        // stays sound; the reason is just less reusable than the per-value citation.
        var count = 0
        var probe = start
        while (probe <= crossed.last) {
            if (++count > MAX_HOLE_CITATIONS) return decisionCutAntecedents(base)
            if (probe >= orig.max) break
            probe = orig.higher(probe)
        }
        var value = start
        while (value <= crossed.last) {
            cite(value)
            if (value >= orig.max) break
            value = orig.higher(value)
        }
    }
    return out?.toIntArray() ?: base
}

internal fun PropagationState.tightenIntMinImpl(v: Int, lo: Long, antecedents: IntArray?): Boolean =
    tightenBoundImpl(v, lo, antecedents, isMin = true)

internal fun PropagationState.tightenIntMaxImpl(v: Int, hi: Long, antecedents: IntArray?): Boolean =
    tightenBoundImpl(v, hi, antecedents, isMin = false)

/**
 * Shared core for the two one-sided bound tightenings: raise `v.min` to `bound` (`isMin`) or lower
 * `v.max` to `bound`. `inline` so each caller expands a side-specialised copy with the `isMin`
 * branches folded away — the propagation hot loop pays no extra dispatch or allocation.
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun PropagationState.tightenBoundImpl(
    v: Int,
    bound: Long,
    antecedents: IntArray?,
    isMin: Boolean,
): Boolean {
    val d = intDomains[v]
    if (if (isMin) bound <= d.min else bound >= d.max) return true
    if (if (isMin) bound > d.max else bound < d.min) {
        // Two-sided narrowing emptied the domain: the existing opposite bound came from
        // `intMaxReason[v]` / `intMinReason[v]`, and `currentFactor` is the one trying to push
        // this side past it. Both go into the core seed.
        recordConflictLevels(intLevel[v], currentLevel)
        seedConflictFactor(if (isMin) intMaxReason[v] else intMinReason[v])
        seedConflictFactor(currentFactor)
        return false
    }
    if (undoLogging) logIntChange(v)
    if (currentFactor >= 0) propagations++
    // Preserve interior holes via the sparse-aware constructor path. For contiguous
    // domains this is functionally identical to `IntDomain(bound, d.max)` / `IntDomain(d.min, bound)`.
    val newDomain = if (isMin) d.withMinAtLeast(bound) else d.withMaxAtMost(bound)
    // A landing value inside a hole snaps the bound further. The snapped bound rests on the requested
    // bound plus the crossed holes. The requested-bound atom is cited only for a *decision* move
    // (`antecedents == null`): a decision to set `v ≥ bound` has no factor reason, so its sole
    // representation in conflict analysis is that atom. A *propagation* supplies `antecedents` that
    // already imply the bound, so citing the atom too is redundant — and since the atom resolves
    // back to this very bound it would be a same-var cycle 1UIP cannot collapse.
    val snapped = if (isMin) newDomain.min > bound else newDomain.max < bound
    val ant = if (snapped) {
        val priorLit = Lit.make(if (isMin) atomVarGe(v, bound) else atomVarLe(v, bound), false)
        val root = rootDomains[v]
        val cite = (if (isMin) bound > root.min else bound < root.max) && antecedents == null
        val crossed = if (isMin) bound until newDomain.min else (newDomain.max + 1)..bound
        appendPriorBound(priorLit, cite, antecedentsAcrossHoles(v, crossed, antecedents))
    } else {
        antecedents
    }
    intDomains[v] = newDomain
    intLevel[v] = maxOf(intLevel[v], currentLevel)
    if (isMin) {
        intMinLevel[v] = currentLevel
        intMinReason[v] = currentFactor
        intMinAntecedents[v] = ant
    } else {
        intMaxLevel[v] = currentLevel
        intMaxReason[v] = currentFactor
        intMaxAntecedents[v] = ant
    }
    markIntDirty(v, if (isMin) IntEvent.LB_RAISED_BIT else IntEvent.UB_LOWERED_BIT)
    if (isMin) {
        propagateAtomsForVar(v, antNear = antecedents, antFar = ant, reqMin = bound, oldMin = d.min, oldMax = d.max)
    } else {
        propagateAtomsForVar(v, antNear = antecedents, antFar = ant, reqMax = bound, oldMin = d.min, oldMax = d.max)
    }
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
internal fun PropagationState.excludeIntValueImpl(v: Int, value: Long, antecedents: IntArray?): Boolean {
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
            d.min > rootDomains[v].min,
            antecedents,
        )

        newDomain.max != d.max -> appendPriorBound(
            Lit.make(atomVarLe(v, d.max), false),
            d.max < rootDomains[v].max,
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
    // An interior carve (no endpoint moved) records the hole-carve record — the level/reason source
    // for an eq atom materialized after its value was already carved. (A bound move instead stamps
    // the level/reason directly on each crossed order literal's trail slot, see [wakeAtom].)
    if (newDomain.min == d.min && newDomain.max == d.max) pushHoleHist(v, value, currentLevel, antecedents)
    // Reason attribution: which side (min/max) "moved" depends on where the hole
    // landed. Pure interior holes don't shift either endpoint; in that case the
    // current factor still becomes the relevant reason for any future propagator
    // walking back through this variable.
    if (newDomain.min != d.min) {
        intMinLevel[v] = currentLevel
        intMinReason[v] = currentFactor
        intMinAntecedents[v] = ant
    }
    if (newDomain.max != d.max) {
        intMaxLevel[v] = currentLevel
        intMaxReason[v] = currentFactor
        intMaxAntecedents[v] = ant
    }
    // Edge exclusions advance a bound (LB/UB); a pure interior carve leaves both intact.
    val kindBit = when {
        newDomain.min != d.min -> IntEvent.LB_RAISED_BIT
        newDomain.max != d.max -> IntEvent.UB_LOWERED_BIT
        else -> IntEvent.VALUE_REMOVED_BIT
    }
    markIntDirty(v, kindBit)
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

/**
 * Like [antecedentsAcrossHoles] but for a batched exclusion: cite only the *pre-existing*
 * search-carved holes crossed in `[from, until)`. A value still present in [prior] is one this
 * very batch is excluding — its reason is the shared [base] (which already carries the batch's
 * antecedents), so citing its eq atom here would be circular. Values absent from [prior] but
 * inside the root domain were carved by an *earlier* propagation and must be cited; values absent
 * from the root domain are global facts and need none.
 */
internal fun PropagationState.citeCrossedSearchHoles(
    v: Int,
    from: Long,
    until: Long,
    prior: IntDomain,
    base: IntArray?,
): IntArray? {
    var out: IntArrayList? = null
    val root = rootDomains[v]
    fun cite(value: Long) {
        val o = out ?: IntArrayList().also { fresh ->
            out = fresh
            base?.forEach { fresh.add(it) }
        }
        o.add(Lit.make(atomVarEq(v, value), true))
    }
    // Cite the search-carved holes crossed in the range: values absent from [prior] but inside the
    // root domain (a value still present in [prior] is one this batch excludes, covered by [base]).
    // Enumerate the smaller side — a survivor set over a wide span has span-many holes but few members,
    // so walking holes would be O(span); iterate the root's members instead. Identical cited set.
    val lo = maxOf(from, prior.min)
    val hi = minOf(until - 1, prior.max)
    val members = if (root.valueCount <= prior.holeCount) root.spanOrNull() else null
    if (members != null) {
        members.forEach { value ->
            if (value in lo..hi && value !in prior) cite(value)
        }
    } else {
        prior.forEachHoleInRange(from, until - 1) { value ->
            if (value in root) cite(value)
        }
    }
    return out?.toIntArray() ?: base
}

/**
 * Exclude every value in [values] (sorted ascending, distinct) from int var [v] in a single
 * pass — the batched form of [excludeIntValueImpl]. Element's constant-array GAC prunes a wide
 * result domain down to a small reachable set; doing that one value at a time rebuilds the hole
 * array per value (O(domain^2)), whereas `IntDomain.excludeValues` merges them
 * in O(domain).
 *
 * Reasoning matches the single-value path: the two endpoints that may move each cite the prior
 * bound, the shared [antecedents] (which justifies the whole batch — Element passes one reason for
 * every value), and any pre-existing search holes crossed on the way ([citeCrossedSearchHoles]);
 * interior carves each cite [antecedents]. Atom truth/level end state is identical to folding
 * [excludeIntValueImpl] over [values]. Returns false, seeding the conflict core, when the
 * exclusions empty the domain.
 */
internal fun PropagationState.excludeIntValues(v: Int, values: LongArray, antecedents: IntArray?): Boolean {
    if (values.isEmpty()) return true
    val d = intDomains[v]
    val newDomain = d.excludeValues(values)
    if (newDomain == null) { // exclusions emptied the domain
        recordConflictLevels(intLevel[v], currentLevel)
        seedConflictFactor(intMinReason[v])
        seedConflictFactor(intMaxReason[v])
        seedConflictFactor(currentFactor)
        return false
    }
    if (newDomain === d) return true // nothing present was excluded
    val newMin = newDomain.min
    val newMax = newDomain.max

    // Interior carves (strictly inside the surviving span); the rest land on a moved endpoint.
    var interior: LongArrayList? = null
    var excluded = 0
    for (i in values.indices) {
        val value = values[i]
        if (value !in d) continue
        excluded++
        if (value in newMin..newMax) (interior ?: LongArrayList().also { interior = it }).add(value)
    }
    if (currentFactor >= 0) propagations += excluded

    val root = rootDomains[v]
    val antMin = if (newMin != d.min) {
        citeCrossedSearchHoles(
            v,
            d.min,
            newMin,
            d,
            appendPriorBound(Lit.make(atomVarGe(v, d.min), false), d.min > root.min, antecedents),
        )
    } else {
        null
    }
    val antMax = if (newMax != d.max) {
        citeCrossedSearchHoles(
            v,
            newMax + 1,
            d.max + 1,
            d,
            appendPriorBound(Lit.make(atomVarLe(v, d.max), false), d.max < root.max, antecedents),
        )
    } else {
        null
    }

    if (undoLogging) logIntChange(v) // one record restores the full prior domain + bound atoms

    intDomains[v] = newDomain
    intLevel[v] = maxOf(intLevel[v], currentLevel)
    if (newMin != d.min) {
        intMinLevel[v] = currentLevel
        intMinReason[v] = currentFactor
        intMinAntecedents[v] = antMin
    }
    if (newMax != d.max) {
        intMaxLevel[v] = currentLevel
        intMaxReason[v] = currentFactor
        intMaxAntecedents[v] = antMax
    }
    interior?.let { iv ->
        // The carved values' domain is restored wholesale by the single [logIntChange] record above,
        // and each carved value's `[v = value]` eq atom flips back on the reversible atom trail
        // (the batch wakes it false through [propagateAtomsForExclusionBatch]); no per-carve record.
        for (i in 0 until iv.size) pushHoleHist(v, iv[i], currentLevel, antecedents)
    }
    // A batched exclusion can move both endpoints and carve interior holes at once; raise every
    // event kind that actually occurred so typed-event advisors (e.g. a bounds-consistent [Linear]
    // subscribed to LB_RAISED / UB_LOWERED, dropped from this var's occurrence-list wakeup) wake.
    // Marking only [dirtyInts] without the kind bits under-sets the event and silently drops the
    // wake — the single-value [excludeIntValueImpl] / [tightenBoundImpl] paths mark the kind too.
    var kindMask = 0
    if (newMin != d.min) kindMask = kindMask or IntEvent.LB_RAISED_BIT
    if (newMax != d.max) kindMask = kindMask or IntEvent.UB_LOWERED_BIT
    if (interior != null) kindMask = kindMask or IntEvent.VALUE_REMOVED_BIT
    markIntDirty(v, kindMask)
    propagateAtomsForExclusionBatch(v, d.min, d.max, antMin, antMax, interior, antecedents)
    return true
}

internal fun PropagationState.seedConflictFactor(fid: Int) {
    if (fid < 0) return
    noteLearnedUse(fid) // a learned clause that detects a conflict counts as reused
    conflictSeedFactors.add(fid)
}

internal fun PropagationState.setIntImpl(v: Int, value: Long, antecedents: IntArray?): Boolean =
    tightenIntMinImpl(v, value, antecedents) && tightenIntMaxImpl(v, value, antecedents)

/**
 * Restrict `v`'s domain to [survivors] (ascending, the bake's folded fixpoint for a sparse variable) as
 * one seed decision — the compact counterpart to excluding each hole individually. Sets the domain in a
 * single step and wakes the affected order/eq atoms off the value-sorted atom index
 * ([propagateAtomsForSetRestriction]), so both the domain write and the atom wakeups are O(survivors +
 * eq-atoms) rather than O(span). It is a root-seed input (no antecedents; presolve does not learn), so
 * per-hole reasons are not recorded — the excluded values are unconditional facts of the folded domain.
 */
internal fun PropagationState.restrictIntToSurvivors(v: Int, survivors: LongArray): Boolean {
    val d = intDomains[v]
    var kept = 0
    for (s in survivors) if (s in d) kept++
    if (kept == 0) { // the restriction empties the domain
        recordConflictLevels(intLevel[v], currentLevel)
        seedConflictFactor(intMinReason[v])
        seedConflictFactor(intMaxReason[v])
        seedConflictFactor(currentFactor)
        return false
    }
    // A domain with more values than the survivor list can hold is never fully covered by it.
    if (kept == d.spanOrNull()?.size) return true // survivors already cover every live value
    val filtered = if (kept == survivors.size) {
        survivors
    } else {
        LongArray(kept).also { out ->
            var j = 0
            for (s in survivors) if (s in d) out[j++] = s
        }
    }
    val oldMin = d.min
    val oldMax = d.max
    if (undoLogging) logIntChange(v) // one record restores the full prior domain + bound atoms
    val newDomain = intDomainFromSurvivors(filtered)
    intDomains[v] = newDomain
    intLevel[v] = maxOf(intLevel[v], currentLevel)
    var kindMask = IntEvent.VALUE_REMOVED_BIT
    if (newDomain.min != oldMin) {
        intMinLevel[v] = currentLevel
        intMinReason[v] = currentFactor
        intMinAntecedents[v] = null
        kindMask = kindMask or IntEvent.LB_RAISED_BIT
    }
    if (newDomain.max != oldMax) {
        intMaxLevel[v] = currentLevel
        intMaxReason[v] = currentFactor
        intMaxAntecedents[v] = null
        kindMask = kindMask or IntEvent.UB_LOWERED_BIT
    }
    markIntDirty(v, kindMask)
    propagateAtomsForSetRestriction(v, oldMin, oldMax, filtered, null)
    return true
}

internal fun PropagationState.recordConflictLevels(a: Int, b: Int) {
    conflictLevels = when {
        a > 0 && b > 0 && a != b -> intArrayOf(a, b)
        a > 0 -> intArrayOf(a)
        b > 0 -> intArrayOf(b)
        else -> EmptyIntArray
    }
}
