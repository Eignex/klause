package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.util.arrays.binarySearchLong
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList

internal fun PropagationState.markAtomWatched(atomId: Int) {
    val v = atoms.intVar[atomId]
    val kind = atoms.kind[atomId]
    val k = atoms.threshold[atomId]
    val idx = atoms.watchedByVar[v] ?: VarAtomIndex().also { atoms.watchedByVar[v] = it }
    // A membership test, so it goes through the merge-free lookup: reading the ordered array here would
    // fold the staging buffer on every watch installation, which is the whole cost this index avoids.
    if (idx.find(kind, k) >= 0) return
    idx.insert(kind, k, atomId)
}

internal fun PropagationState.atomIdOf(v: Int): Int = v - problem.numBoolVars

internal fun PropagationState.atomLitWatchIndex(lit: Int): Int =
    (atomIdOf(Lit.variable(lit)) shl 1) or (if (Lit.isPositive(lit)) 0 else 1)

// A 0 truth slot can be an atom materialized after its bound crossed, so derive it from the live domain.
internal fun PropagationState.atomCurrentTruth(atomId: Int): Boolean? = when (atoms.truth[atomId]) {
    1 -> true
    2 -> false
    else -> atomTruthOf(atoms.intVar[atomId], atoms.kind[atomId], atoms.threshold[atomId])
}

// An undetermined atom reports the live decision count: it can only constrain at the current level.
internal fun PropagationState.atomLevelForConflict(atomId: Int): Int {
    val stored = atoms.lvl[atomId]
    if (stored >= 0) return stored
    // Materialized after its bound crossed: a bound atom takes the level of the move that first reached its
    // threshold. Without the undo log the live endpoint's level over-states it, which is sound but weaker.
    val v = atoms.intVar[atomId]
    val k = atoms.threshold[atomId]
    val truth = atomCurrentTruth(atomId) ?: return levelToDecisionVar.size
    boundAtomEstablishmentLevel(atomId, truth)?.let { return it }
    return when (atoms.kind[atomId]) {
        AtomKind.GE -> endpointLevel(v, viaMax = !truth)

        AtomKind.LE -> endpointLevel(v, viaMax = truth)

        AtomKind.EQ -> if (truth) {
            // A singleton: both endpoints pin it.
            maxOf(endpointLevel(v, viaMax = false), endpointLevel(v, viaMax = true))
        } else {
            val d = intDomains[v]
            // A carved or interior value was ruled out at its carve, not by a later bound move that snapped
            // over it. A bound exclusion takes the first crossing's level.
            when {
                holeHistHas(v, k) -> holeLevelFor(v, k)
                k < d.min -> boundEstablishmentLevel(v, k + 1, lower = true)
                    ?: endpointLevel(v, viaMax = false)
                k > d.max -> boundEstablishmentLevel(v, k - 1, lower = false)
                    ?: endpointLevel(v, viaMax = true)
                else -> holeLevelFor(v, k)
            }
        }
    }
}

internal fun PropagationState.allocAtom(intVar: Int, kind: AtomKind, threshold: Long): Int {
    val byVar = atoms.byIntVar[intVar]
    if (byVar != null) {
        val existing = byVar.find(kind, threshold)
        if (existing >= 0) return problem.numBoolVars + existing
    }
    val id = atoms.intVar.size
    atoms.intVar.add(intVar)
    atoms.kind.add(kind)
    atoms.threshold.add(threshold)
    // Undetermined regardless of the live domain: the cached truth belongs to the reversible atom trail, which
    // cannot undo a crossing made before this atom existed. Such an atom derives truth, level and reason from
    // the live domain.
    atoms.truth.add(0)
    atoms.lvl.add(-1)
    atoms.ant.add(null)
    atoms.watchersByLit.add(null) // positive-literal watcher slot for this atom
    atoms.watchersByLit.add(null) // negative-literal watcher slot
    (byVar ?: VarAtomIndex().also { atoms.byIntVar[intVar] = it }).insert(kind, threshold, id)
    registerChannelingFor(id)
    return problem.numBoolVars + id
}

// Lazy clause generation channeling (O. Ohrimenko, P. J. Stuckey, M. Codish, "Propagation via lazy clause
// generation", Constraints 14(3), 2009, https://doi.org/10.1007/s10601-008-9064-x). Only immediate same-var
// neighbours are linked; transitivity covers the rest, and each atom is materialized once, so no dedup is
// needed. Clauses are queued, not registered, because [allocAtom] also runs inside conflict analysis where
// the clause store must not be mutated.
//
// Skipped in incremental presolve: no conflict analysis needs them there, and registering them would push
// factors into the learned store while incremental [factorAt] routes every tail id to the mid-life store.
internal fun PropagationState.registerChannelingFor(newAtomId: Int) {
    if (incremental) return
    val v = atoms.intVar[newAtomId]
    val k = atoms.threshold[newAtomId]
    val idx = atoms.byIntVar[v] ?: return
    val nb = problem.numBoolVars
    val newVar = nb + newAtomId
    when (atoms.kind[newAtomId]) {
        AtomKind.GE -> {
            val run = idx.runOf(AtomKind.GE)
            val up = run.above(k)
            if (up >= 0) enqueueChannel(nb + up, newVar) // [x≥kt] → [x≥k]
            val down = run.below(k)
            if (down >= 0) enqueueChannel(newVar, nb + down) // [x≥k] → [x≥kl]
            val eq = idx.find(AtomKind.EQ, k)
            if (eq >= 0) enqueueChannel(nb + eq, newVar) // [x=k] → [x≥k]
            val dual = idx.find(AtomKind.LE, k - 1)
            if (dual >= 0) enqueueDuality(newVar, nb + dual) // [x≥k] ⟺ ¬[x≤k-1]
            val le = idx.find(AtomKind.LE, k)
            if (eq >= 0 && le >= 0) enqueueBoundsEq(newVar, nb + le, nb + eq) // [x≥k]∧[x≤k] → [x=k]
        }

        AtomKind.LE -> {
            val run = idx.runOf(AtomKind.LE)
            val down = run.below(k)
            if (down >= 0) enqueueChannel(nb + down, newVar) // [x≤kl] → [x≤k]
            val up = run.above(k)
            if (up >= 0) enqueueChannel(newVar, nb + up) // [x≤k] → [x≤kt]
            val eq = idx.find(AtomKind.EQ, k)
            if (eq >= 0) enqueueChannel(nb + eq, newVar) // [x=k] → [x≤k]
            val dual = idx.find(AtomKind.GE, k + 1)
            if (dual >= 0) enqueueDuality(nb + dual, newVar) // [x≥k+1] ⟺ ¬[x≤k]
            val ge = idx.find(AtomKind.GE, k)
            if (eq >= 0 && ge >= 0) enqueueBoundsEq(nb + ge, newVar, nb + eq) // [x≥k]∧[x≤k] → [x=k]
        }

        AtomKind.EQ -> {
            val ge = idx.find(AtomKind.GE, k)
            if (ge >= 0) enqueueChannel(newVar, nb + ge) // [x=k] → [x≥k]
            val le = idx.find(AtomKind.LE, k)
            if (le >= 0) enqueueChannel(newVar, nb + le) // [x=k] → [x≤k]
            if (ge >= 0 && le >= 0) enqueueBoundsEq(nb + ge, nb + le, newVar) // [x≥k]∧[x≤k] → [x=k]
        }
    }
}

private fun PropagationState.enqueueChannel(negVar: Int, posVar: Int) {
    pendingChanneling.add(intArrayOf(Lit.make(negVar, false), Lit.make(posVar, true)))
}

// `[x≥m] ⟺ ¬[x≤m-1]` as two clauses, so a bound move cascades across the GE/LE split.
private fun PropagationState.enqueueDuality(geVar: Int, leVar: Int) {
    pendingChanneling.add(intArrayOf(Lit.make(geVar, false), Lit.make(leVar, false)))
    pendingChanneling.add(intArrayOf(Lit.make(geVar, true), Lit.make(leVar, true)))
}

// With the eq→bound clauses this realizes `[x=k] ⟺ [x≥k] ∧ [x≤k]`.
private fun PropagationState.enqueueBoundsEq(geVar: Int, leVar: Int, eqVar: Int) {
    pendingChanneling.add(intArrayOf(Lit.make(geVar, false), Lit.make(leVar, false), Lit.make(eqVar, true)))
}

// Runs at the top of runToFixpoint so the store is never mutated mid-analysis. The clauses are tautologies of
// the integer semantics, so they stay permanent across every backtrack and forgetting pass.
internal fun PropagationState.flushPendingChanneling() {
    if (pendingChanneling.isEmpty()) return
    val batch = pendingChanneling.toTypedArray()
    pendingChanneling.clear()
    for (lits in batch) addLearnedClause(Clause(lits), lbd = lits.size, permanent = true, origin = CHANNELING_ORIGIN)
}

// A bound atom cites the move that first took its bound to the threshold, never a later one that overshot it:
// that would explain the atom by premises established after it, a back edge in the reason graph. `null` marks a
// root/bake fact, a decision, or an undetermined atom.
internal fun PropagationState.atomAntecedentsDerived(atomId: Int): IntArray? {
    if (atoms.lvl[atomId] >= 0) return reasonOf(atoms.ant[atomId])
    val v = atoms.intVar[atomId]
    val k = atoms.threshold[atomId]
    val d = intDomains[v]
    val truth = atomCurrentTruth(atomId) ?: return null
    boundAtomEstablishment(atomId, truth)?.let { return it.reason }
    // Only a threshold the live endpoint sits exactly on can be explained: that endpoint move is the
    // one that established this atom's truth, so its premises ([endpointReason], over other
    // variables) are all established before it, and before anything that cites the atom. A true eq
    // atom cites both endpoint bounds; a carved eq-false atom cites the carve reason, and a bound
    // exclusion cites the adjacent order atom whose reason preserves the first crossing. Every reason is
    // a valid standalone clause, so recursive clause minimization resolving through it stays sound.
    return when (atoms.kind[atomId]) {
        AtomKind.GE ->
            if (truth) {
                if (k >= d.min) endpointReason(v, viaMax = false) else null
            } else {
                if (k - 1 <= d.max) endpointReason(v, viaMax = true) else null
            }

        AtomKind.LE ->
            if (truth) {
                if (k <= d.max) endpointReason(v, viaMax = true) else null
            } else {
                if (k + 1 >= d.min) endpointReason(v, viaMax = false) else null
            }

        AtomKind.EQ ->
            if (truth) {
                composeIntVarAtomAntecedents(intArrayOf(v))
            } else {
                when {
                    holeHistHas(v, k) || k in d.min..d.max -> reasonOf(holeReasonFor(v, k))
                    k < d.min -> intArrayOf(Lit.make(atomVarGe(v, k + 1), false))
                    else -> intArrayOf(Lit.make(atomVarLe(v, k - 1), false))
                }
            }
    }
}

// Resolving directly to other variables' bounds avoids a same-variable GE/LE reason cycle that can leave a
// learned clause non-asserting. A threshold past the endpoint inherits the endpoint's establishment level,
// which can be later than the atom first became determined; resolution consumes the endpoint reason at its
// actual level, so this stays sound.
internal fun PropagationState.endpointReason(v: Int, viaMax: Boolean): IntArray? =
    reasonOf(if (viaMax) intMaxAntecedents[v] else intMinAntecedents[v])

internal fun PropagationState.endpointLevel(v: Int, viaMax: Boolean): Int {
    val lvl = if (viaMax) intMaxLevel[v] else intMinLevel[v]
    return if (lvl >= 0) lvl else 0
}

internal fun PropagationState.atomTruthOf(v: Int, kind: AtomKind, k: Long): Boolean? {
    val d = intDomains[v]
    return when (kind) {
        AtomKind.GE -> when {
            d.min >= k -> true
            d.max < k -> false
            else -> null
        }

        AtomKind.LE -> when {
            d.max <= k -> true
            d.min > k -> false
            else -> null
        }

        AtomKind.EQ -> when {
            d.min == d.max && d.min == k -> true
            k !in d -> false
            else -> null
        }
    }
}

// Soundness rests on visiting every crossed threshold: a missed wake strands a stale atom truth. Inline so
// the eq callback and the range visits stay allocation-free.
private inline fun PropagationState.wakeMinCrossing(
    idx: VarAtomIndex,
    oldMin: Long,
    newMin: Long,
    ant: IntArray?,
    eqAction: (atomId: Int) -> Unit,
) {
    atoms.pendingMoveAnt = ant
    // The frontier `[v ≥ newMin]` goes on the trail before the looser thresholds that cite it as their
    // monotonicity anchor ([channelingReasonAtWake]): 1UIP resolves the trail in reverse establishment
    // order, so a premise stamped *after* the literal it explains would be resolved out first and then
    // recur as a genuine premise. The ascending visit reaches the frontier last, hence the explicit wake.
    val frontier = idx.find(AtomKind.GE, newMin)
    if (frontier >= 0) wakeAtom(frontier, true)
    idx.ge.visitRange(oldMin + 1, newMin - 1) { id -> wakeAtom(id, true) }
    idx.le.visitRange(oldMin, newMin - 1) { id -> wakeAtom(id, false) }
    idx.eq.visitRange(oldMin, newMin - 1, eqAction)
}

private inline fun PropagationState.wakeMaxCrossing(
    idx: VarAtomIndex,
    newMax: Long,
    oldMax: Long,
    ant: IntArray?,
    eqAction: (atomId: Int) -> Unit,
) {
    atoms.pendingMoveAnt = ant
    idx.le.visitRange(newMax, oldMax - 1) { id -> wakeAtom(id, true) }
    idx.ge.visitRange(newMax + 1, oldMax) { id -> wakeAtom(id, false) }
    idx.eq.visitRange(newMax + 1, oldMax, eqAction)
}

internal fun PropagationState.propagateAtomsForVar(
    v: Int,
    antNear: IntArray?,
    antFar: IntArray? = antNear,
    reqMin: Long = intDomains[v].min,
    reqMax: Long = intDomains[v].max,
    oldMin: Long,
    oldMax: Long,
    carved: Long = NO_CARVE,
) {
    // Each crossed order literal takes the weakest reason set that still implies its truth: [antNear]
    // justifies the requested bound, [antFar] adds the hole-crossing chain for a bound that snapped past
    // holes. The requested-bound atom itself ([v ≥ reqMin] / [v ≤ reqMax]) must take [antNear], which does
    // NOT list that atom; [antFar] does (via [antecedentsAcrossHoles]), so giving it [antFar] would be
    // the same-var cycle 1UIP cannot collapse.
    val idx = atoms.byIntVar[v] ?: return
    if (finiteMutationInterruptible && !finiteMaintenanceActive) {
        completeDomainMutation(0, {}) {
            propagateAtomsForVar(v, antNear, antFar, reqMin, reqMax, oldMin, oldMax, carved)
        }
        return
    }
    val d = intDomains[v]
    val newMin = d.min
    val newMax = d.max
    // Materialize the frontier bound atoms before the crossing loop, so a crossed looser atom can cite the
    // frontier as its monotonicity anchor without a mid-iteration insert into this var's atom index.
    if (newMin > oldMin) atomVarGe(v, newMin)
    if (newMax < oldMax) atomVarLe(v, newMax)
    if (newMin > oldMin) wakeRaisedMin(v, idx, oldMin, newMin, reqMin, antNear, antFar)
    if (newMax < oldMax) wakeLoweredMax(v, idx, newMax, oldMax, reqMax, antNear, antFar)
    if (carved != NO_CARVE && carved in (newMin + 1) until newMax) {
        atoms.pendingMoveAnt = antFar
        idx.eq.visitRange(carved, carved) { id -> wakeAtom(id, false) }
    }
    if (newMin == newMax && (newMin > oldMin || newMax < oldMax)) {
        atoms.pendingMoveAnt = antFar
        idx.eq.visitRange(newMin, newMin) { id -> wakeAtom(id, true) }
    }
}

// Each side of a move is its own method: inlined together, the crossing visits and every [wakeAtom] they inline make
// one body C2 compiles at many times the cost of the two apart.
@Suppress("LongParameterList")
private fun PropagationState.wakeRaisedMin(
    v: Int,
    idx: VarAtomIndex,
    oldMin: Long,
    newMin: Long,
    reqMin: Long,
    antNear: IntArray?,
    antFar: IntArray?,
) {
    val near = antNear ?: decidedFrontier(idx.find(AtomKind.GE, newMin))
    wakeMinCrossing(idx, oldMin, newMin, antFar) { id ->
        recordEqDeath(v, atoms.threshold[id], near = atoms.threshold[id] < reqMin, near, antFar)
        wakeAtom(id, false)
    }
}

@Suppress("LongParameterList")
private fun PropagationState.wakeLoweredMax(
    v: Int,
    idx: VarAtomIndex,
    newMax: Long,
    oldMax: Long,
    reqMax: Long,
    antNear: IntArray?,
    antFar: IntArray?,
) {
    val near = antNear ?: decidedFrontier(idx.find(AtomKind.LE, newMax))
    wakeMaxCrossing(idx, newMax, oldMax, antFar) { id ->
        recordEqDeath(v, atoms.threshold[id], near = atoms.threshold[id] > reqMax, near, antFar)
        wakeAtom(id, false)
    }
}

// Both endpoints may move in one step on different reasons. The end state equals folding
// [propagateAtomsForVar] over the same exclusions one at a time, with each changed literal woken once.
internal fun PropagationState.propagateAtomsForExclusionBatch(
    v: Int,
    oldMin: Long,
    oldMax: Long,
    antMin: IntArray?,
    antMax: IntArray?,
    interiorVals: LongArrayList?,
    interiorAnt: IntArray?,
) {
    val idx = atoms.byIntVar[v] ?: return
    val d = intDomains[v]
    val newMin = d.min
    val newMax = d.max
    if (newMin > oldMin) {
        wakeMinCrossing(idx, oldMin, newMin, antMin) { id -> wakeAtom(id, false) }
    }
    if (newMax < oldMax) {
        wakeMaxCrossing(idx, newMax, oldMax, antMax) { id -> wakeAtom(id, false) }
    }
    if (interiorVals != null) {
        atoms.pendingMoveAnt = interiorAnt
        for (i in 0 until interiorVals.size) {
            checkFiniteAtomWork()
            val x = interiorVals[i]
            idx.eq.visitRange(x, x) { id -> wakeAtom(id, false) }
        }
    }
    if (newMin == newMax && (newMin > oldMin || newMax < oldMax)) {
        idx.eq.visitRange(newMin, newMin) { id -> wakeAtom(id, true) }
    }
}

// The interior is driven off the value-sorted eq-atom index rather than the hole list, so cost is
// O(eq-atoms in range), never O(span).
internal fun PropagationState.propagateAtomsForSetRestriction(
    v: Int,
    oldMin: Long,
    oldMax: Long,
    survivors: LongArray,
    ant: IntArray?,
) {
    val idx = atoms.byIntVar[v] ?: return
    if (finiteMutationInterruptible && !finiteMaintenanceActive) {
        completeDomainMutation(0, {}) {
            propagateAtomsForSetRestriction(v, oldMin, oldMax, survivors, ant)
        }
        return
    }
    val d = intDomains[v]
    val newMin = d.min
    val newMax = d.max
    if (newMin > oldMin) {
        wakeMinCrossing(idx, oldMin, newMin, ant) { id -> wakeAtom(id, false) }
    }
    if (newMax < oldMax) {
        wakeMaxCrossing(idx, newMax, oldMax, ant) { id -> wakeAtom(id, false) }
    }
    atoms.pendingMoveAnt = ant
    idx.eq.visitRange(newMin, newMax) { id ->
        checkFiniteAtomWork()
        if (survivors.binarySearchLong(atoms.threshold[id]) < 0) wakeAtom(id, false)
    }
    if (newMin == newMax && (newMin > oldMin || newMax < oldMax)) {
        idx.eq.visitRange(newMin, newMin) { id -> wakeAtom(id, true) }
    }
}

// A decision moves its bound with no reason, yet each value it sweeps past is still ruled out by the decided
// frontier bound, which the crossing stamps first; citing it keeps the decision the level's one unexplained literal.
private fun PropagationState.decidedFrontier(frontier: Int): IntArray? =
    if (frontier >= 0 && currentLevel > 0) intArrayOf(Lit.make(problem.numBoolVars + frontier, false)) else null

// A value killed below the requested raised min (or above the lowered max) is ruled out by that bound alone,
// whose reason cites other variables: `antNear ⟹ v ≥ reqMin ⟹ v ≠ k`. Recording it at the first death lets a
// later move snapping across the dead value find it via holeReasonFor; deriving it from that later move,
// whose reason lists this eq-atom, would form the same-var cycle 1UIP cannot collapse.
private fun PropagationState.recordEqDeath(v: Int, k: Long, near: Boolean, antNear: IntArray?, antFar: IntArray?) {
    if (near && !holeHistHas(v, k)) pushHoleHist(v, k, currentLevel, antNear)
    atoms.pendingMoveAnt = if (near) antNear else antFar
}

// No-op without undo logging: the one-shot propagate / bake fixpoint never backtracks.
internal fun PropagationState.recordAtomTruthChange(atomId: Int) {
    if (!undoLogging) return
    atoms.undoAtomId.add(atomId)
    atoms.undoTruth.add(atoms.truth[atomId])
    atoms.undoLvl.add(atoms.lvl[atomId])
    atoms.undoAnt.add(atoms.ant[atomId])
}

internal fun PropagationState.wakeAtom(atomId: Int, newT: Boolean) {
    checkFiniteAtomWork()
    val targetState = if (newT) 1 else 2
    // A re-crossed atom keeps its first establishment. Re-stamping would mis-date the literal and append an
    // out-of-order trail entry, breaking reverse-assignment-order resolution. Watchers still fire.
    if (atoms.truth[atomId] == targetState) {
        val ww = atoms.watchersByLit[(atomId shl 1) or (if (!newT) 0 else 1)] ?: return
        for (j in 0 until ww.size) {
            checkFiniteAtomWork()
            atoms.dirtyFactors.addLast(ww[j])
        }
        return
    }
    var establishedLevel = currentLevel
    // A true eq atom rests on both endpoint bounds; a crossed hole rests on its carve reason. The move's reason
    // lists this eq-atom when it snapped across the hole, so citing it back would form the same-var cycle 1UIP
    // cannot collapse.
    val antecedents = when {
        newT && atoms.kind[atomId] == AtomKind.EQ ->
            composeIntVarAtomAntecedents(intArrayOf(atoms.intVar[atomId]))

        !newT && atoms.kind[atomId] == AtomKind.EQ && holeHistHas(atoms.intVar[atomId], atoms.threshold[atomId]) -> {
            // The value left at its carve, which may be well before this move woke the atom: its level is the
            // carve's, matching the carve's reason, or a root carve would sit at this level with no reason.
            establishedLevel = holeLevelFor(atoms.intVar[atomId], atoms.threshold[atomId])
            holeReasonFor(atoms.intVar[atomId], atoms.threshold[atomId])
        }

        // The frontier atom rests on the move's explanation; a looser one cites the single frontier literal, so
        // its reason stays a valid standalone channeling clause the analyzer can resolve through.
        else -> channelingReasonAtWake(atomId, newT)
    }
    if (finiteMaintenanceActive && runCancelled) throw FinitePropagationInterrupted()
    recordAtomTruthChange(atomId)
    boolPinOrder.add(problem.numBoolVars + atomId)
    atoms.truth[atomId] = targetState
    atoms.lvl[atomId] = establishedLevel
    atoms.ant[atomId] = antecedents
    // The false literal has polarity `!newT`; see [atomLitWatchIndex] for the slot layout.
    val w = atoms.watchersByLit[(atomId shl 1) or (if (!newT) 0 else 1)] ?: return
    for (j in 0 until w.size) {
        checkFiniteAtomWork()
        atoms.dirtyFactors.addLast(w[j])
    }
}

private fun PropagationState.channelingReasonAtWake(atomId: Int, newT: Boolean): IntArray? {
    val v = atoms.intVar[atomId]
    val k = atoms.threshold[atomId]
    val d = intDomains[v]
    val idx = atoms.byIntVar[v]

    // Non-materializing: a batch or set-restriction path may lack the frontier atom, and then the move's own
    // explanation is still a sound reason.
    fun frontier(kind: AtomKind, threshold: Long): IntArray? {
        val f = idx?.find(kind, threshold) ?: -1
        return if (f >= 0) intArrayOf(Lit.make(problem.numBoolVars + f, false)) else atoms.pendingMoveAnt
    }
    return when (atoms.kind[atomId]) {
        AtomKind.GE ->
            if (newT) {
                if (k >= d.min) atoms.pendingMoveAnt else frontier(AtomKind.GE, d.min)
            } else {
                if (k - 1 <= d.max) atoms.pendingMoveAnt else frontier(AtomKind.LE, d.max)
            }

        AtomKind.LE ->
            if (newT) {
                if (k <= d.max) atoms.pendingMoveAnt else frontier(AtomKind.LE, d.max)
            } else {
                if (k + 1 >= d.min) atoms.pendingMoveAnt else frontier(AtomKind.GE, d.min)
            }

        // A value the move swept past without a carve is excluded by the bound it now lies beyond, a reason
        // even when the move itself is a decision with none.
        AtomKind.EQ -> when {
            k < d.min -> frontier(AtomKind.GE, d.min)

            k > d.max -> frontier(AtomKind.LE, d.max)

            // An interior value (a survivor restriction's) is excluded by the move itself, not by a bound.
            else -> atoms.pendingMoveAnt
        }
    }
}

internal fun PropagationState.installLitWatch(
    lit: Int,
    fid: Int,
    blocker: Int = NO_BLOCKER,
    preparation: Boolean = false,
) {
    val v = Lit.variable(lit)
    if (v < problem.numBoolVars) {
        val list = watches.byLit[lit]
        val indexWork = watches.pos.put(packWatch(fid, lit), list.size)
        if (preparation) preparationIndexWork += indexWork else work += indexWork
        list.add(fid)
        watches.blockersByLit[lit].add(blocker) // index-aligned with the watcher just appended
    } else {
        val idx = atomLitWatchIndex(lit)
        val list = atoms.watchersByLit[idx] ?: IntArrayList(initialCapacity = 2).also { atoms.watchersByLit[idx] = it }
        list.add(fid)
        markAtomWatched(atomIdOf(v))
    }
}

internal class BoundEstablishment(val level: Int, val reason: IntArray?)

internal fun PropagationState.boundAtomEstablishmentLevel(atomId: Int, truth: Boolean): Int? {
    val v = atoms.intVar[atomId]
    val k = atoms.threshold[atomId]
    return when (atoms.kind[atomId]) {
        AtomKind.GE -> boundEstablishmentLevel(v, if (truth) k else k - 1, lower = truth)
        AtomKind.LE -> boundEstablishmentLevel(v, if (truth) k else k + 1, lower = !truth)
        AtomKind.EQ -> null
    }
}

// A true `[v ≥ k]` was established by the first move that took the lower bound to `k` or past it, a false one by
// the first that took the upper bound below `k`; `[v ≤ k]` mirrors that.
internal fun PropagationState.boundAtomEstablishment(atomId: Int, truth: Boolean): BoundEstablishment? {
    val v = atoms.intVar[atomId]
    val k = atoms.threshold[atomId]
    return when (atoms.kind[atomId]) {
        AtomKind.GE -> boundEstablishment(v, if (truth) k else k - 1, lower = truth)
        AtomKind.LE -> boundEstablishment(v, if (truth) k else k + 1, lower = !truth)
        AtomKind.EQ -> null
    }
}

// Read back from the undo log. Citing the live bound's later move would date the atom after literals that cite
// it, a back edge that leaves 1UIP stranded on an unexplained literal.
internal fun PropagationState.boundEstablishment(v: Int, k: Long, lower: Boolean): BoundEstablishment? {
    if (!undoLogging) return null
    val moves = boundMoves[v]
    val count = moves?.size ?: 0
    val lo = movesBefore(v, k, lower)
    // Moves [lo, count) start already at k; lo == 0 means k held before any logged move, a root fact.
    if (lo == 0) return BoundEstablishment(0, null)
    val next = lo
    val postLevel: Int
    val postReason: IntArray?
    val postBound: Long
    if (next < count) {
        val i = requireNotNull(moves)[next]
        postLevel = if (lower) undo.minLvl[i] else undo.maxLvl[i]
        postReason = reasonOf(if (lower) undo.minAnt[i] else undo.maxAnt[i])
        postBound = requireNotNull(if (lower) boundMovePriorMin[v] else boundMovePriorMax[v])[next]
    } else {
        postLevel = if (lower) intMinLevel[v] else intMaxLevel[v]
        postReason = reasonOf(if (lower) intMinAntecedents[v] else intMaxAntecedents[v])
        postBound = if (lower) intDomains[v].min else intDomains[v].max
    }
    return established(v, k, lower, postLevel.coerceAtLeast(0), postReason, postBound)
}

// Reason-free variant of [boundEstablishment] for the learned-clause level read on the propagation path.
internal fun PropagationState.boundEstablishmentLevel(v: Int, k: Long, lower: Boolean): Int? {
    if (!undoLogging) return null
    val lo = movesBefore(v, k, lower)
    if (lo == 0) return 0
    val moves = boundMoves[v]
    val level = if (lo < (moves?.size ?: 0)) {
        val i = requireNotNull(moves)[lo]
        if (lower) undo.minLvl[i] else undo.maxLvl[i]
    } else {
        if (lower) intMinLevel[v] else intMaxLevel[v]
    }
    return level.coerceAtLeast(0)
}

// The number of [v]'s logged bound moves that came before its lower ([lower]) or upper bound reached [k]. The
// bound only tightens along the path, so the records whose prior bound already reached k form a suffix; the one
// just before it is the move that reached k, and its post-move state is the next record's prior state, or the
// live one.
private fun PropagationState.movesBefore(v: Int, k: Long, lower: Boolean): Int {
    val priors = (if (lower) boundMovePriorMin[v] else boundMovePriorMax[v]) ?: return 0
    var lo = 0
    var hi = priors.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        val prior = priors[mid]
        if (if (lower) prior >= k else prior <= k) hi = mid else lo = mid + 1
    }
    return lo
}

// A move with no reason is a decision. The decided bound itself has none either, but a weaker one follows from
// it, `[v ≥ bound] → [v ≥ k]` (likewise `≤`), so its reason is the decision's literal; without one it would sit
// at the decision's level as a second unexplained literal.
private fun PropagationState.established(
    v: Int,
    k: Long,
    lower: Boolean,
    level: Int,
    reason: IntArray?,
    bound: Long,
): BoundEstablishment {
    if (reason != null || level == 0 || bound == k) return BoundEstablishment(level, reason)
    val decided = if (lower) atomVarGe(v, bound) else atomVarLe(v, bound)
    return BoundEstablishment(level, intArrayOf(Lit.make(decided, false)))
}
