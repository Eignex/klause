package com.eignex.klause.propagation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import com.eignex.klause.propagation.Assumptions
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList

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
    // Bound tightenings, holes and set restrictions are seed-time inputs, not propagated conclusions.
    // Each takes its own decision level so the conflict analyzer can attribute backjumps to it.
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

internal fun PropagationState.pinBoolAsDecision(v: Int, value: Boolean): Boolean {
    levelToDecisionVar.add(v)
    currentLevel = levelToDecisionVar.size
    currentFactor = -1
    return pinBoolImpl(v, value, antecedents = null)
}

// With the undo log on, the decision is `[v = value]`, stamped without a reason and cited by both bound moves, so
// the level carries one unexplained literal for 1UIP to stop at instead of two bound atoms it cannot collapse.
internal fun PropagationState.setIntAsDecision(v: Int, value: Long): Boolean {
    levelToDecisionVar.add(problem.numBoolVars + v)
    currentLevel = levelToDecisionVar.size
    currentFactor = -1
    val d = intDomains[v]
    if (!undoLogging || value !in d || d.min == d.max) return setIntImpl(v, value, null)
    val reason = stampDecision(atomVarEq(v, value))
    return tightenIntMinImpl(v, value, reason) && tightenIntMaxImpl(v, value, reason)
}

// Stamped ahead of the moves it causes, so their wakes find it already true and leave it unexplained.
private fun PropagationState.stampDecision(atom: Int): IntArray {
    val atomId = atom - problem.numBoolVars
    recordAtomTruthChange(atomId)
    boolPinOrder.add(atom)
    atoms.truth[atomId] = 1
    atoms.lvl[atomId] = currentLevel
    atoms.ant[atomId] = null
    return intArrayOf(Lit.make(atom, false))
}

// The caller ensures `hi` strictly narrows the domain. With the undo log on, `[v ≤ hi]` is stamped before the
// move: a move snapping past a hole lands on a tighter bound citing `[v ≤ hi]`, and woken later `[v ≤ hi]` would
// take that bound as its reason, leaving no unexplained literal at the level. The move keeps no reason, which
// marks a decided bound when a reason lifts it.
internal fun PropagationState.setIntMaxAsDecision(v: Int, hi: Long): Boolean {
    levelToDecisionVar.add(problem.numBoolVars + v)
    currentLevel = levelToDecisionVar.size
    currentFactor = -1
    val d = intDomains[v]
    if (undoLogging && hi in d.min until d.max) stampDecision(atomVarLe(v, hi))
    return tightenIntMaxImpl(v, hi, null)
}

internal fun PropagationState.setIntMinAsDecision(v: Int, lo: Long): Boolean {
    levelToDecisionVar.add(problem.numBoolVars + v)
    currentLevel = levelToDecisionVar.size
    currentFactor = -1
    val d = intDomains[v]
    if (undoLogging && lo in (d.min + 1)..d.max) stampDecision(atomVarGe(v, lo))
    return tightenIntMinImpl(v, lo, null)
}

internal fun PropagationState.pinBoolImpl(v: Int, value: Boolean, antecedents: IntArray?): Boolean {
    // Packed bits avoid boxing a Boolean on each propagation pin.
    if (boolAssigned.get(v)) {
        if (boolValueBits.get(v) == value) return true
        // Record [v] so the analyzer can seed a clause from the prior pin when a decision contradicts it.
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
    nativeBoolTruth?.set(v, (if (value) 2 else 1).toByte())
    // The undo mark owns the pin's lifetime, even when its clause cites facts from a shallower level.
    // Dating it before that mark lets an asserting backjump erase several supposedly surviving literals.
    boolLevel[v] = maxOf(currentLevel, levelToDecisionVar.size)
    noteLearnedUse(currentFactor)
    noteLearnedBoolReason(currentFactor, 1)
    boolReason[v] = currentFactor
    arithmeticLearningTrace?.pin(currentFactor, v, if (value) 1 else 0, "bool", boolLevel[v])

    boolAntecedents[v] = antecedents
    boolPinOrder.add(v)
    bumpObjectiveBoolBound(v, value)
    // The native-SAT lane sweeps the pin trail directly and never drains dirtyBools; skipping the
    // enqueue keeps that queue from growing unbounded across a solve. The general path still uses it.
    if (nativeEngine == null) dirtyBools.addLast(v)
    return true
}

// A root-level prior bound is a global fact and needs no citation.
internal fun PropagationState.appendPriorBound(priorLit: Int, cite: Boolean, base: IntArray?): IntArray? {
    if (!cite) return base
    if (!isLazyReason(base) && base != null && base.contains(priorLit)) return base
    return extendReason(base, intArrayOf(priorLit))
}

// A wider block of crossed holes takes the decision-cut reason.
private const val MAX_HOLE_CITATIONS = 4096

private fun PropagationState.decisionCutAntecedents(base: IntArray?): IntArray {
    val lits = IntArrayList()
    reasonOf(base)?.forEach { lits.add(it) }
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

// The supplied reason justifies only the requested bound. Each crossed value in the root domain was carved during
// search and must be cited, or learned clauses can prune feasible assignments.
internal fun PropagationState.antecedentsAcrossHoles(v: Int, crossed: LongRange, base: IntArray?): IntArray? {
    var out: IntArrayList? = null
    val orig = rootDomains[v]
    fun cite(value: Long) {
        val o = out ?: IntArrayList().also { out = it }
        o.add(Lit.make(atomVarEq(v, value), true))
    }
    // Walk the root's members by neighbour steps: the span can be astronomically wide, but the members visited
    // are bounded by the carves that removed them.
    if (!crossed.isEmpty()) {
        val start = if (crossed.first in orig) {
            crossed.first
        } else if (crossed.first < orig.max) {
            orig.higher(crossed.first)
        } else {
            crossed.last + 1 // past the range: nothing to cite
        }
        // Count before citing, since citing materializes eq-atoms. Past the cap, every decision's current pins and
        // bounds negated is a sound but less reusable reason.
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
    return out?.let { extendReason(base, it.toIntArray()) } ?: base
}

internal fun PropagationState.tightenIntMinImpl(v: Int, lo: Long, antecedents: IntArray?): Boolean =
    tightenBoundImpl(v, lo, antecedents, isMin = true)

internal fun PropagationState.tightenIntMaxImpl(v: Int, hi: Long, antecedents: IntArray?): Boolean =
    tightenBoundImpl(v, hi, antecedents, isMin = false)

// Inline so each side gets a specialised copy with no dispatch or allocation in the hot loop.
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
        // The opposite bound's reason and the current factor both seed the core.
        recordConflictLevels(intLevel[v], currentLevel)
        seedConflictFactor(if (isMin) intMaxReason[v] else intMinReason[v])
        seedConflictFactor(currentFactor)
        return false
    }
    if (undoLogging) logIntChange(v)
    if (currentFactor >= 0) propagations++
    // Preserves interior holes.
    val newDomain = if (isMin) d.withMinAtLeast(bound) else d.withMaxAtMost(bound)
    // A landing value inside a hole snaps further, so the bound also rests on the crossed holes. The requested
    // bound atom is cited only for a decision move: a propagation's antecedents already imply it, and the atom
    // resolves back to this bound, a same-var cycle 1UIP cannot collapse.
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
    noteLearnedUse(currentFactor)
    arithmeticLearningTrace?.pin(currentFactor, v, if (isMin) newDomain.min else newDomain.max,
        if (isMin) "lower" else "upper", currentLevel)
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
    // An edge exclusion's new bound also rests on the prior bound and any holes crossed; without them learned
    // clauses can prune feasible assignments. Near and far sets stay separate so each flipped atom takes the
    // weakest sufficient one.
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
    noteLearnedUse(currentFactor)
    intLevel[v] = maxOf(intLevel[v], currentLevel)
    // Only an interior carve needs the hole record; a bound move stamps each crossed literal's trail slot.
    if (newDomain.min == d.min && newDomain.max == d.max) pushHoleHist(v, value, currentLevel, antecedents)
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

// A value still in [prior] is one this batch excludes, covered by [base]; citing its eq atom would be circular.
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
        val o = out ?: IntArrayList().also { out = it }
        o.add(Lit.make(atomVarEq(v, value), true))
    }
    // Enumerate the smaller side: a survivor set over a wide span has span-many holes but few members.
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
    return out?.let { extendReason(base, it.toIntArray()) } ?: base
}

// Batched form of [excludeIntValueImpl]: excluding one value at a time rebuilds the hole array per value,
// O(domain²). The atom end state equals folding the single-value path over [values].
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
        checkFiniteMutationWork()
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
    noteLearnedUse(currentFactor)
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
    // Raise every kind that occurred: a missing bit drops a typed-event advisor's wake.
    var kindMask = 0
    if (newMin != d.min) kindMask = kindMask or IntEvent.LB_RAISED_BIT
    if (newMax != d.max) kindMask = kindMask or IntEvent.UB_LOWERED_BIT
    if (interior != null) kindMask = kindMask or IntEvent.VALUE_REMOVED_BIT
    completeDomainMutation(interior?.size ?: 0, { i ->
        pushHoleHist(v, requireNotNull(interior)[i], currentLevel, antecedents)
    }) {
        markIntDirty(v, kindMask)
        propagateAtomsForExclusionBatch(v, d.min, d.max, antMin, antMax, interior, antecedents)
    }
    return true
}

// Values are sorted, distinct and strictly interior. Each reason belongs to the pre-batch
// trail boundary; one domain snapshot restores both the domain and all per-value witnesses.
internal fun PropagationState.excludeInteriorIntValues(
    v: Int,
    values: LongArray,
    antecedents: (Long) -> IntArray?,
): Boolean {
    if (values.isEmpty()) return true
    val d = intDomains[v]
    require(values.first() > d.min && values.last() < d.max)
    val newDomain = checkNotNull(d.excludeValues(values))
    if (newDomain === d) return true
    val interior = LongArrayList(initialCapacity = values.size)
    val reasons = ArrayList<IntArray?>(values.size)
    for (value in values) {
        checkFiniteMutationWork()
        if (value !in d) continue
        interior.add(value)
        reasons.add(antecedents(value))
    }
    if (undoLogging) logIntChange(v)
    intDomains[v] = newDomain
    noteLearnedUse(currentFactor)
    intLevel[v] = maxOf(intLevel[v], currentLevel)
    if (currentFactor >= 0) propagations += interior.size
    completeDomainMutation(interior.size, { i ->
        pushHoleHist(v, interior[i], currentLevel, reasons[i])
    }) {
        markIntDirty(v, IntEvent.VALUE_REMOVED_BIT)
        // Equality atoms read their individual carve witnesses rather than a shared move reason.
        propagateAtomsForExclusionBatch(v, d.min, d.max, null, null, interior, null)
    }
    return true
}

internal fun PropagationState.seedConflictFactor(fid: Int) {
    if (fid < 0) return
    noteLearnedUse(fid) // a learned clause that detects a conflict counts as reused
    conflictSeedFactors.add(fid)
}

internal fun PropagationState.setIntImpl(v: Int, value: Long, antecedents: IntArray?): Boolean =
    tightenIntMinImpl(v, value, antecedents) && tightenIntMaxImpl(v, value, antecedents)

// Wakes atoms off the value-sorted index, so the write and wakeups are O(survivors + eq-atoms) rather than O(span).
// A search-time seed keeps its before/after domains in reversible history.
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
    val newDomain = intDomainFromSurvivors(filtered, problem.settings.storage)
    if (undoLogging) recordSurvivorRestriction(v, d, newDomain)
    intDomains[v] = newDomain
    noteLearnedUse(currentFactor)
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
        else -> emptyIntArray()
    }
}
