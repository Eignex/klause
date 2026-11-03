package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.factor.bool.PseudoBooleanPropagator
import com.eignex.klause.factor.litVars
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.search.LearnedKind
import com.eignex.klause.solver.search.LearnedStage
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList

/**
 * Invoke [action] with the *other* literal of every binary clause that contains [lit].
 * Binary clauses watch both their literals and never relocate a watch (there is
 * no third literal to move to), so [BoolWatcherIndex.byLit] reliably lists every binary clause
 * on [lit]. No-op for atom-literal [lit] (the bool watcher index only covers bool vars).
 */
internal fun PropagationState.forEachBinaryPartner(lit: Int, action: (other: Int) -> Unit) {
    if (Lit.variable(lit) >= problem.numBoolVars) return
    nativeEngine?.let {
        it.forEachBinaryPartner(lit, action)
        return
    }
    val list = watches.byLit[lit]
    for (i in 0 until list.size) {
        work++
        if (retiredLearnedFactor(list[i])) continue
        val f = factorAt(list[i])
        if (f is ClausePropagator && f.literals.size == 2) {
            val a = f.literals[0]
            val b = f.literals[1]
            when (lit) {
                a -> action(b)
                b -> action(a)
            }
        }
    }
}

/** Unified factor accessor; routes static factor ids to `Problem.factors`, and tail ids
 *  (≥ `baseFactorCount`) to the mid-life presolve store in [PropagationState.incremental] mode
 *  (a tombstoned factor reads [NoPropagator]) or to [PropagationState.learnedClauses] otherwise. */
internal fun PropagationState.factorAt(fid: Int): Propagator = when {
    incremental && !factorAliveAt(fid) -> NoPropagator

    retiredLearnedFactor(fid) -> NoPropagator

    fid < baseFactorCount ->
        if (nativeEngine == null) baseFactors[fid] else problem.factors[fid].propagatorProjection()

    incremental -> midlife.store[fid - baseFactorCount]

    nativeEngine != null -> {
        val literals = nativeEngine.literalsOf(fid - baseFactorCount)
        ClausePropagator(literals.litVars(), EmptyIntArray, literals)
    }

    else -> learned.store[fid - baseFactorCount]
}

/**
 * Register a learned clause and return its assigned factor id: append it to [LearnedClauseDb.store] with its
 * policy metadata and mutable payload reference in one stable paged row, and install its initial watches.
 *
 * Does not propagate; [PropagationSession.addLearnedClause] does.
 */
internal fun PropagationState.addLearnedClause(
    clause: ClausePropagator,
    lbd: Int,
    permanent: Boolean = false,
    origin: Int = OWN_ORIGIN,
): Int {
    learned.lifecycle.note(LearnedKind.Clause, LearnedStage.Constructed)
    learned.lifecycle.note(LearnedKind.Clause, LearnedStage.Admitted)
    val newFid = totalFactorCount
    var initialStage = 16
    for (literal in clause.literals) {
        work++
        if (Lit.variable(literal) >= problem.numBoolVars) {
            initialStage = 0
            break
        }
    }
    addGeneralLearnedRow(clause, initialStage, lbd, permanent, origin)
    if (clause.literals.size == 1) learned.units.add(newFid)
    work += learned.trackCapObject(learned.size - 1, permanent)
    if (clause.literals.size == 2) learned.binaryClauseCount++ // keep the binary-resolution gate current
    val watchers = clause.initialBoolWatchers
    val blockers = clause.initialBoolWatcherBlockers
    if (watchers.size == 2 && watchers[0] != watchers[1] &&
        watchers.all { Lit.variable(it) < problem.numBoolVars }) {
        val first = watches.byLit[watchers[0]]
        val second = watches.byLit[watchers[1]]
        work += watches.pos.putPair(newFid, watchers[0], first.size, watchers[1], second.size)
        first.add(newFid)
        second.add(newFid)
        watches.blockersByLit[watchers[0]].add(blockers?.getOrNull(0) ?: NO_BLOCKER)
        watches.blockersByLit[watchers[1]].add(blockers?.getOrNull(1) ?: NO_BLOCKER)
    } else {
        for (i in watchers.indices) installLitWatch(watchers[i], newFid, blockers?.getOrNull(i) ?: NO_BLOCKER)
    }
    return newFid
}

/**
 * Register a learned pseudo-Boolean constraint `Σ weightsᵢ·literalsᵢ ≥ degree` and return
 * its factor id. Stored in the same [LearnedClauseDb] as clauses (as a [LearnedPropagator]), with the
 * forgetting policy columns. Watches cover only the weighted covering set from
 * [PseudoBooleanPropagator.selectWatched], kept in the factor's `refPayload` slot; the constraint wakes when a
 * watched literal goes false.
 */
internal fun PropagationState.addLearnedPb(
    weights: LongArray,
    literals: IntArray,
    degree: Long,
    lbd: Int,
    permanent: Boolean = false,
): Int {
    learned.lifecycle.note(LearnedKind.Pb, LearnedStage.Constructed)
    val prop = PseudoBooleanPropagator(
        literals.litVars(),
        EmptyIntArray,
        weights.copyOf(),
        literals.copyOf(),
        PbOp.GE,
        degree,
        watched = true,
    )
    val newFid = totalFactorCount
    addGeneralLearnedRow(prop, 0, lbd, permanent, OWN_ORIGIN)
    learned.nonClauseCount++
    work++
    work += learned.trackCapObject(learned.size - 1, permanent, LearnedKind.Pb)

    learned.lifecycle.note(LearnedKind.Pb, LearnedStage.Admitted)
    val watched = prop.selectWatched(this)
    arithmeticLearningTrace?.stored(newFid, "pb")
    for (lit in watched) installLitWatch(lit, newFid, NO_BLOCKER)
    refPayload[newFid] = watched
    return newFid
}

internal fun PropagationState.addLearnedIntegerRow(row: IntegerConflictRow, lbd: Int): Int {
    check(intEvents.on)
    learned.lifecycle.note(LearnedKind.Integer, LearnedStage.Constructed)
    learned.lifecycle.note(LearnedKind.Integer, LearnedStage.Admitted)
    if (row.transientSteps > 0) integerTransientAdmitted++
    if (row.retainedSources > 0) {
        integerChainedAdmitted++
        if (row.transientSteps > 0) integerChainedTransientAdmitted++
        if (row.hasNonOwnerSource) integerNonOwnerAdmitted++
        if (row.hasNonOwnerOriginalSource) integerNonOwnerOriginalAdmitted++
        if (row.declaredBoundTerms > 0) integerDeclaredBoundAdmitted++
        if (row.declaredBoundModes != 0) integerDeclaredBoundExtendedAdmitted++
    }
    val propagator = LearnedIntegerPropagator(row)
    val factor = totalFactorCount
    addGeneralLearnedRow(propagator, 0, lbd, false, OWN_ORIGIN)
    learned.nonClauseCount++
    work++
    work += learned.trackCapObject(learned.size - 1, false, LearnedKind.Integer)
    val rows = learned.integerRows ?: LearnedIntegerRows().also { learned.integerRows = it; work++ }
    work += rows.admissionWork
    rows.admit(propagator)
    arithmeticLearningTrace?.integerCensus?.admit(factor, row, problem.intBounds) { variable ->
        val domain = intDomains[variable]
        // A fixed binary endpoint cannot be tightened further and lose its root level.
        if ((domain.min == 1L && intMinLevel[variable] == 0) ||
            (domain.max == 0L && intMaxLevel[variable] == 0)) domain else rootDomains[variable]
    }
    arithmeticLearningTrace?.stored(factor, "integer")

    for (watch in propagator.initialIntEventWatches) work += intEvents.subscribeMidlife(watch, factor)
    if (row.guard != Lit.NONE) installLitWatch(row.guard xor 1, factor)
    return factor
}

/** Convenience overload that converts a structural [Clause] to its [ClausePropagator] before registering. */
internal fun PropagationState.addLearnedClause(
    clause: Clause,
    lbd: Int,
    permanent: Boolean = false,
    origin: Int = OWN_ORIGIN,
    ownedLiterals: Boolean = false,
): Int {
    // The native-SAT lane keeps learned clauses in its own arena-backed store with its own watches
    // and policy columns, not the general learned database.
    nativeEngine?.let { return it.addLearned(clause.literals, lbd, permanent, origin, ownedLiterals) }
    val literals = if (ownedLiterals) clause.literals else clause.literals.copyOf()
    return addLearnedClause(ClausePropagator(literals.litVars(), EmptyIntArray, literals), lbd, permanent, origin)
}

/** Read-only view of LBDs for tests / introspection. Parallel to [PropagationState.learnedClauses]. */
internal fun PropagationState.learnedClauseLbd(learnedIndex: Int): Int =
    nativeEngine?.lbdOf(learnedIndex) ?: learned.store[learnedIndex, GeneralLearnedRows.LBD]

/** True iff learned clause [learnedIndex] must survive every forgetting pass. */
internal fun PropagationState.learnedClausePermanent(learnedIndex: Int): Boolean =
    nativeEngine?.permanentOf(learnedIndex) ?: (learned.store[learnedIndex, GeneralLearnedRows.PERMANENT] == 1)

/** The arm learned clause [learnedIndex] was imported from, or [OWN_ORIGIN] for one learned here. */
internal fun PropagationState.learnedClauseOrigin(learnedIndex: Int): Int =
    nativeEngine?.originOf(learnedIndex) ?: learned.store[learnedIndex, GeneralLearnedRows.ORIGIN]

/** Three-tier DB tier of learned clause [learnedIndex] ([ClauseTier.UNSET] until the
 *  reduction policy classifies it). */
internal fun PropagationState.learnedClauseTier(learnedIndex: Int): ClauseTier =
    nativeEngine?.tierOf(learnedIndex) ?: ClauseTier.entries[learned.store[learnedIndex, GeneralLearnedRows.TIER]]

/** Set the three-tier DB tier of learned clause [learnedIndex] (promotion / demotion /
 *  initial classification by the reduction policy). */
internal fun PropagationState.setLearnedClauseTier(learnedIndex: Int, tier: ClauseTier) {
    nativeEngine?.let {
        it.setTierOf(learnedIndex, tier)
        return
    }
    if (learned.retiredOf(learnedIndex)) return
    learned.store[learnedIndex, GeneralLearnedRows.TIER] = tier.ordinal
}

/** True iff learned clause [learnedIndex] was used (conflict or unit) since the last
 *  reduction. */
internal fun PropagationState.learnedClauseUsedSinceReduction(learnedIndex: Int): Boolean =
    nativeEngine?.usedOf(learnedIndex) ?: learned.usedOf(learnedIndex)

/** Clear the reuse flag for learned clause [learnedIndex] — called for survivors at the
 *  end of a reduction so the next window measures fresh activity. */
internal fun PropagationState.clearLearnedClauseUsed(learnedIndex: Int) {
    nativeEngine?.let {
        it.clearUsed(learnedIndex)
        return
    }
    learned.clearUsed(learnedIndex)
}

/** Mark learned clause [fid] (a factor id; ignored when it isn't a learned clause) as
 *  used since the last reduction — it just detected a conflict or forced a unit. Drives
 *  three-tier promotion. */
internal fun PropagationState.noteLearnedUse(fid: Int) {
    nativeEngine?.let {
        it.markUsed(fid)
        return
    }
    val idx = fid - problem.numFactors
    if (idx !in 0 until learned.size || learned.retiredOf(idx)) return
    arithmeticLearningTrace?.integerCensus?.used(fid)
    factorAttribution?.used(learned.store[idx])
    noteLearnedStage(idx, LearnedStage.Consumed, 2)
    if (!learned.usedOf(idx)) learned.importUses.note(learned.store[idx, GeneralLearnedRows.ORIGIN])
    learned.markUsed(idx)
}

/**
 * Prune the learned-clause database. The [keep] predicate decides per (learnedIndex,
 * lbd) whether to retain that clause; dropped clauses' factor ids vanish and the
 * remaining clauses are renumbered contiguously starting at `problem.numFactors`.
 * The [LearnedClauseDb] rows and their mutable payload references are compacted together;
 * the bool and atom watcher lists, propagation queues and per-variable
 * reason fields have their learned factor ids remapped through `oldFid → newFid` or dropped.
 *
 * Watchers' positions inside each clause's `refPayload[fid]` are watch *indices*
 * (into `clause.literals`), not factor ids — they survive the compaction unchanged.
 * A cancellable preflight must fund the complete atomic remap and its scratch before mutation.
 * An oversized or cancelled preparation returns false and preserves the generation.
 */
internal fun PropagationState.forgetLearnedClauses(
    maxWork: Long = 65_536L,
    maxScratchBytes: Long = 1_048_576L,
    cancelled: () -> Boolean = { false },
    keep: (learnedIndex: Int, lbd: Int) -> Boolean,
): Boolean {
    val budget = ReclamationPreparation(maxWork, maxScratchBytes, cancelled) { work += it }
    val retained = try {
        sweepRetiredLearnedMemory(budget)
        prepareReclamation(budget, keep)
    } catch (_: ReclamationPreparation.Deferred) {
        return false
    }
    nativeEngine?.let {
        it.forget(retained)
        return true
    }
    val n = learned.size
    if (n == 0) return true
    val remap = IntArray(n) // remap[i] = new learned index, or -1 if dropped
    work += n
    var newCount = 0
    for (i in 0 until n) {
        work++
        remap[i] = if (retained[i]) newCount++ else -1
    }
    if (newCount == n) return true
    work += selectedRetirements.remapWork
    selectedRetirements.remap(remap)
    arithmeticLearningTrace?.remap(baseFactorCount, remap)
    for (i in 0 until n) if (remap[i] < 0) {
        work++
        if (!learned.retiredOf(i)) {
            learned.lifecycle.note(learnedKind(i), LearnedStage.Retired)
            factorAttribution?.retire(learned.store[i])
        }
    }

    learned.units.clear()
    learned.integerRows?.let { rows -> work += rows.size.toLong() + 2L; rows.reset() }
    work += learned.resetCapIndex()
    var w = 0
    for (i in 0 until n) {
        work++
        if (remap[i] >= 0) {
            val propagator = learned.store[i]
            if (propagator is LearnedIntegerPropagator) {
                val rows = checkNotNull(learned.integerRows)
                work += rows.admissionWork
                rows.admit(propagator)
            }
            if (propagator is ClausePropagator && propagator.literals.size == 1) learned.units.add(baseFactorCount + w)
            work += learned.store.move(i, w)
            if (learned.retiredOf(w)) {
                learned.retiredCount++
                learned.enqueueRetiredMetadata(w)
            }
            else {
                work += learned.trackCapObject(w, learned.store[w, GeneralLearnedRows.PERMANENT] == 1, learnedKind(w))
                if (propagator !is ClausePropagator) {
                    learned.nonClauseCount++
                    work++
                }
            }
            w++
        }
    }
    work += learned.store.truncateTo(newCount)
    val refBase = problem.numFactors
    learned.integerRows?.let { rows ->
        work += rows.trimmingWork + 1L
        rows.trim()
        if (rows.isEmpty()) learned.integerRows = null
    }

    for (lit in watches.byLit.indices) {
        work++
        val list = watches.byLit[lit]
        val blockers = watches.blockersByLit[lit] // compacted in lockstep so indices stay aligned
        var wi = 0
        for (r in 0 until list.size) {
            work++
            val fid = list[r]
            val blocker = blockers[r]
            if (fid < refBase) {
                blockers[wi] = blocker
                list[wi++] = fid
            } else {
                val newLearnedIdx = remap[fid - refBase]
                if (newLearnedIdx >= 0) {
                    blockers[wi] = blocker
                    list[wi++] = refBase + newLearnedIdx
                }
            }
        }
        list.truncateTo(wi)
        blockers.truncateTo(wi)
        list.trim()
        blockers.trim()
    }

    // Atom-literal watcher lists carry learned fids too — a learned clause watching a
    // bound atom registers here, not in the bool-var lists.
    for (list in atoms.watchersByLit) {
        work++
        if (list == null) continue
        var wi = 0
        for (r in 0 until list.size) {
            work++
            val fid = list[r]
            if (fid < refBase) {
                list[wi++] = fid
            } else {
                val newLearnedIdx = remap[fid - refBase]
                if (newLearnedIdx >= 0) list[wi++] = refBase + newLearnedIdx
            }
        }
        list.truncateTo(wi)
        list.trim()
    }

    // Renumbering learned fids and shifting list positions invalidates every back pointer.
    work++
    watches.pos = BoolWatchPositionIndex(baseFactorCount)
    for (lit in watches.byLit.indices) {
        work++
        val list = watches.byLit[lit]
        for (i in 0 until list.size) {
            work++
            work += watches.pos.put(packWatch(list[i], lit), i)
        }
    }

    // A conflict return leaves the propagation queues holding in-flight fids, and the
    // engine forgets at the following restart — so the queues can still carry learned
    // fids from before the compaction. Remap them like the watcher lists: a stale fid
    // surviving here indexes past the compacted clause array on the next drain.
    remapQueue(propQueue, remap, refBase)
    var pending = 0
    for (i in 0 until learned.pendingAttach.size) {
        work++
        val old = learned.pendingAttach[i] - refBase
        if (remap[old] >= 0) learned.pendingAttach[pending++] = refBase + remap[old]
    }
    learned.pendingAttach.truncateTo(pending)
    remapQueue(atoms.dirtyFactors, remap, refBase)
    if (intEvents.overflow != null) {
        work += intEvents.remapLearned(remap, refBase)
    }

    // The per-variable reason fields record which factor forced each currently-implied
    // value, learned-clause ids included. Level-0 facts — e.g. a permanent blocking nogood
    // or a learned unit that propagated at the root — survive the restart's pop-to-root, so
    // their reason fids outlive this renumber. Left unremapped, the next conflict's
    // [extractConflictFactors] would dereference a stale learned fid through [factorAt] and
    // index past the compacted clause array. Remap them like the watchers:
    // a kept clause's reason rewrites to its new id, a dropped clause's reason clears to -1.
    remapReasons(boolReason, remap, refBase)
    remapReasons(intMinReason, remap, refBase)
    remapReasons(intMaxReason, remap, refBase)
    for (i in 0 until undo.size) if (undo.tag[i] == 1) {
        work++
        undo.minReason[i] = remappedReason(undo.minReason[i], remap, refBase)
        undo.maxReason[i] = remappedReason(undo.maxReason[i], remap, refBase)
    }
    for (column in listOf(learned.units, learned.pendingAttach, learned.capCandidates)) {
        work += column.learnedTrimmingWork
        column.trim()
    }
    return true
}

private fun PropagationState.addGeneralLearnedRow(
    propagator: LearnedPropagator,
    stage: Int,
    lbd: Int,
    permanent: Boolean,
    origin: Int,
) {
    val rows = learned.store
    work += rows.admissionWork + 1L
    check(nextLearnedAdmissionKey < Long.MAX_VALUE)
    rows.add(propagator, stage, lbd, permanent, origin, nextLearnedAdmissionKey++)
}

internal fun PropagationState.noteLearnedBoolReason(factor: Int, delta: Int) {
    nativeEngine?.let {
        it.noteBoolReason(factor, delta)
        return
    }
    val index = factor - baseFactorCount
    if (index !in 0 until learned.size) return
    work += 2L
    val count = learned.store[index, GeneralLearnedRows.BOOL_REASONS] + delta
    check(count >= 0)
    learned.store[index, GeneralLearnedRows.BOOL_REASONS] = count
}

/** Rewrite learned-clause factor ids stored in a per-variable reason array through [remap]
 *  (static fids `< refBase` pass through; dropped clauses' reasons clear to -1). */
internal fun PropagationState.remapReasons(reasons: IntArray, remap: IntArray, refBase: Int) {
    for (i in reasons.indices) {
        work++
        val fid = reasons[i]
        if (fid >= refBase) {
            val idx = fid - refBase
            reasons[i] = if (idx < remap.size && remap[idx] >= 0) refBase + remap[idx] else -1
        }
    }
}

/** Rewrite every learned fid in `queue` through [remap] (static fids pass through;
 *  dropped clauses' fids are removed). Preserves order. */
internal fun PropagationState.remapQueue(queue: IntArrayDeque, remap: IntArray, refBase: Int) {
    if (queue.isEmpty()) return
    val drained = IntArrayList(queue.size)
    work += queue.size * 2L
    while (queue.isNotEmpty()) drained.add(queue.removeFirst())
    for (i in 0 until drained.size) {
        val fid = drained[i]
        if (fid < refBase) {
            queue.addLast(fid)
        } else {
            val idx = fid - refBase
            if (idx < remap.size) {
                val newLearnedIdx = remap[idx]
                if (newLearnedIdx >= 0) queue.addLast(refBase + newLearnedIdx)
            }
        }
    }
}

internal fun PropagationState.learnedKind(index: Int): LearnedKind = when (learned.store[index]) {
    is PseudoBooleanPropagator -> LearnedKind.Pb
    is LearnedIntegerPropagator -> LearnedKind.Integer
    else -> LearnedKind.Clause
}

internal fun PropagationState.noteLearnedAssertion(fid: Int) {
    val index = fid - baseFactorCount
    if (index in 0 until learned.size) noteLearnedStage(index, LearnedStage.Asserted, 1)
}

internal fun PropagationState.recheckRetractedLearning(fid: Int) {
    nativeEngine?.let {
        it.recheckAfterUndo(fid)
        return
    }
    deferLearnedAttachment(fid)
}

internal fun PropagationState.deferLearnedAttachment(fid: Int) {
    val index = fid - baseFactorCount
    if (index !in 0 until learned.size) return
    if (learned.store[index, GeneralLearnedRows.STAGES] and 4 != 0) return
    learned.store[index, GeneralLearnedRows.STAGES] = learned.store[index, GeneralLearnedRows.STAGES] or 4
    learned.pendingAttach.add(fid)
}

private fun PropagationState.noteLearnedStage(index: Int, stage: LearnedStage, bit: Int) {
    if (learned.store[index, GeneralLearnedRows.STAGES] and bit != 0) return
    learned.store[index, GeneralLearnedRows.STAGES] = learned.store[index, GeneralLearnedRows.STAGES] or bit
    learned.lifecycle.note(learnedKind(index), stage)
    if (stage == LearnedStage.Consumed) {
        val propagator = learned.store[index]
        if (propagator is LearnedIntegerPropagator && propagator.row.transientSteps > 0) integerTransientConsumed++
        if (propagator is LearnedIntegerPropagator && propagator.row.retainedSources > 0) {
            integerChainedConsumed++
            if (propagator.row.transientSteps > 0) integerChainedTransientConsumed++
            if (propagator.row.hasNonOwnerSource) integerNonOwnerConsumed++
            if (propagator.row.hasNonOwnerOriginalSource) integerNonOwnerOriginalConsumed++
            if (propagator.row.declaredBoundTerms > 0) integerDeclaredBoundConsumed++
            if (propagator.row.declaredBoundModes != 0) integerDeclaredBoundExtendedConsumed++
        }
        factorAttribution?.consumed(propagator)
    }
}

internal fun PropagationState.referencedLearned(count: Int): BooleanArray {
    work += count
    val retained = BooleanArray(count)
    collectLearnedReferences(retained) { work++ }
    return retained
}

internal fun PropagationState.collectLearnedReferences(retained: BooleanArray, visit: () -> Unit) {
    fun protect(fid: Int) {
        visit()
        val index = fid - baseFactorCount
        if (index in retained.indices) retained[index] = true
    }
    boolReason.forEach(::protect)
    intMinReason.forEach(::protect)
    intMaxReason.forEach(::protect)
    for (i in 0 until undo.size) {
        visit()
        if (undo.tag[i] == 1) {
            protect(undo.minReason[i])
            protect(undo.maxReason[i])
        }
    }
    for (i in 0 until learned.pendingAttach.size) {
        visit()
        val fid = learned.pendingAttach[i]
        if (learned.store[fid - baseFactorCount, GeneralLearnedRows.STAGES] and 2 == 0) protect(fid)
    }
}

private fun remappedReason(fid: Int, remap: IntArray, base: Int): Int =
    if (fid < base) fid else remap.getOrNull(fid - base)?.takeIf { it >= 0 }?.let { base + it } ?: -1
