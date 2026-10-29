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
 * policy columns, grow [PropagationState.refPayloadStore] by one slot so the clause's `refPayload[factorId]`
 * access stays in bounds, and install its initial watches.
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
    learned.stages.add(0)
    learned.store.add(clause)
    if (clause.literals.size == 1) learned.units.add(newFid)
    learned.lbds.add(lbd)
    learned.permanent.add(if (permanent) 1 else 0)
    learned.tier.add(ClauseTier.UNSET.ordinal)
    learned.usedFlags.add(0)
    learned.origins.add(origin)
    if (clause.literals.size == 2) learned.binaryClauseCount++ // keep the binary-resolution gate current
    refPayloadStore.add(null)
    val watchers = clause.initialBoolWatchers
    val blockers = clause.initialBoolWatcherBlockers
    for (i in watchers.indices) installLitWatch(watchers[i], newFid, blockers?.getOrNull(i) ?: NO_BLOCKER)
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
    learned.stages.add(0)
    learned.store.add(prop)
    learned.lbds.add(lbd)
    learned.permanent.add(if (permanent) 1 else 0)
    learned.tier.add(ClauseTier.UNSET.ordinal)
    learned.usedFlags.add(0)
    learned.origins.add(OWN_ORIGIN)
    refPayloadStore.add(null)
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
    val propagator = LearnedIntegerPropagator(row)
    val factor = totalFactorCount
    learned.stages.add(0)
    learned.store.add(propagator)
    val rows = learned.integerRows ?: ArrayList<LearnedIntegerPropagator>().also { learned.integerRows = it }
    rows.add(propagator)
    arithmeticLearningTrace?.stored(factor, "integer")
    learned.lbds.add(lbd)
    learned.permanent.add(0)
    learned.tier.add(ClauseTier.UNSET.ordinal)
    learned.usedFlags.add(0)
    learned.origins.add(OWN_ORIGIN)
    refPayloadStore.add(null)
    for (watch in propagator.initialIntEventWatches) intEvents.subscribeMidlife(watch, factor)
    if (row.guard != Lit.NONE) installLitWatch(row.guard xor 1, factor)
    return factor
}

/** Convenience overload that converts a structural [Clause] to its [ClausePropagator] before registering. */
internal fun PropagationState.addLearnedClause(
    clause: Clause,
    lbd: Int,
    permanent: Boolean = false,
    origin: Int = OWN_ORIGIN,
): Int {
    // The native-SAT lane keeps learned clauses in its own arena-backed store with its own watches
    // and policy columns, not the general learned database.
    nativeEngine?.let { return it.addLearned(clause.literals, lbd, permanent, origin) }
    val literals = clause.literals.copyOf()
    return addLearnedClause(ClausePropagator(literals.litVars(), EmptyIntArray, literals), lbd, permanent, origin)
}

/** Read-only view of LBDs for tests / introspection. Parallel to [PropagationState.learnedClauses]. */
internal fun PropagationState.learnedClauseLbd(learnedIndex: Int): Int =
    nativeEngine?.lbdOf(learnedIndex) ?: learned.lbds[learnedIndex]

/** True iff learned clause [learnedIndex] must survive every forgetting pass. */
internal fun PropagationState.learnedClausePermanent(learnedIndex: Int): Boolean =
    nativeEngine?.permanentOf(learnedIndex) ?: (learned.permanent[learnedIndex] == 1)

/** The arm learned clause [learnedIndex] was imported from, or [OWN_ORIGIN] for one learned here. */
internal fun PropagationState.learnedClauseOrigin(learnedIndex: Int): Int =
    nativeEngine?.originOf(learnedIndex) ?: learned.origins[learnedIndex]

/** Three-tier DB tier of learned clause [learnedIndex] ([ClauseTier.UNSET] until the
 *  reduction policy classifies it). */
internal fun PropagationState.learnedClauseTier(learnedIndex: Int): ClauseTier =
    nativeEngine?.tierOf(learnedIndex) ?: ClauseTier.entries[learned.tier[learnedIndex]]

/** Set the three-tier DB tier of learned clause [learnedIndex] (promotion / demotion /
 *  initial classification by the reduction policy). */
internal fun PropagationState.setLearnedClauseTier(learnedIndex: Int, tier: ClauseTier) {
    nativeEngine?.let {
        it.setTierOf(learnedIndex, tier)
        return
    }
    learned.tier[learnedIndex] = tier.ordinal
}

/** True iff learned clause [learnedIndex] was used (conflict or unit) since the last
 *  reduction. */
internal fun PropagationState.learnedClauseUsedSinceReduction(learnedIndex: Int): Boolean =
    nativeEngine?.usedOf(learnedIndex) ?: (learned.usedFlags[learnedIndex] == 1)

/** Clear the reuse flag for learned clause [learnedIndex] — called for survivors at the
 *  end of a reduction so the next window measures fresh activity. */
internal fun PropagationState.clearLearnedClauseUsed(learnedIndex: Int) {
    nativeEngine?.let {
        it.clearUsed(learnedIndex)
        return
    }
    learned.usedFlags[learnedIndex] = 0
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
    if (idx !in 0 until learned.usedFlags.size) return
    factorAttribution?.used(learned.store[idx])
    noteLearnedStage(idx, LearnedStage.Consumed, 2)
    if (learned.usedFlags[idx] == 0) learned.importUses.note(learned.origins[idx])
    learned.usedFlags[idx] = 1
}

/**
 * Prune the learned-clause database. The [keep] predicate decides per (learnedIndex,
 * lbd) whether to retain that clause; dropped clauses' factor ids vanish and the
 * remaining clauses are renumbered contiguously starting at `problem.numFactors`.
 * The [LearnedClauseDb] columns and the learned tail of [PropagationState.refPayloadStore] are
 * compacted in order; the bool and atom watcher lists, the propagation queues and the per-variable
 * reason fields have their learned factor ids remapped through `oldFid → newFid` or dropped.
 *
 * Watchers' positions inside each clause's `refPayload[fid]` are watch *indices*
 * (into `clause.literals`), not factor ids — they survive the compaction unchanged.
 * Cost is amortised across infrequent calls (typically once per restart).
 */
internal fun PropagationState.forgetLearnedClauses(keep: (learnedIndex: Int, lbd: Int) -> Boolean) {
    nativeEngine?.let {
        it.forget(keep)
        return
    }
    val n = learned.size
    if (n == 0) return
    val referenced = referencedLearned(n)
    val remap = IntArray(n) // remap[i] = new learned index, or -1 if dropped
    var newCount = 0
    for (i in 0 until n) {
        remap[i] = if (learned.permanent[i] == 1 || referenced[i] || keep(i, learned.lbds[i])) newCount++ else -1
    }
    if (newCount == n) return // nothing dropped
    arithmeticLearningTrace?.remap(baseFactorCount, remap)
    for (i in 0 until n) if (remap[i] < 0) {
        learned.lifecycle.note(learnedKind(i), LearnedStage.Retired)
        factorAttribution?.retire(learned.store[i])
    }

    learned.units.clear()
    learned.integerRows?.clear()
    var w = 0
    for (i in 0 until n) {
        if (remap[i] >= 0) {
            val propagator = learned.store[i]
            if (propagator is LearnedIntegerPropagator) learned.integerRows?.add(propagator)
            if (propagator is ClausePropagator && propagator.literals.size == 1) learned.units.add(baseFactorCount + w)
            learned.store[w] = learned.store[i]
            learned.stages[w] = learned.stages[i]
            learned.lbds[w] = learned.lbds[i]
            learned.permanent[w] = learned.permanent[i]
            learned.tier[w] = learned.tier[i]
            learned.usedFlags[w] = learned.usedFlags[i]
            learned.origins[w] = learned.origins[i]
            w++
        }
    }
    while (learned.store.size > newCount) learned.store.removeAt(learned.store.size - 1)
    learned.stages.truncateTo(newCount)
    learned.lbds.truncateTo(newCount)
    learned.permanent.truncateTo(newCount)
    learned.tier.truncateTo(newCount)
    learned.usedFlags.truncateTo(newCount)
    learned.origins.truncateTo(newCount)

    // Static-factor entries at [0, problem.numFactors) stay untouched.
    val refBase = problem.numFactors
    var rw = refBase
    for (i in 0 until n) {
        if (remap[i] >= 0) {
            refPayloadStore[rw] = refPayloadStore[refBase + i]
            rw++
        }
    }
    while (refPayloadStore.size > rw) refPayloadStore.removeAt(refPayloadStore.size - 1)

    for (lit in watches.byLit.indices) {
        val list = watches.byLit[lit]
        val blockers = watches.blockersByLit[lit] // compacted in lockstep so indices stay aligned
        var wi = 0
        for (r in 0 until list.size) {
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
    }

    // Atom-literal watcher lists carry learned fids too — a learned clause watching a
    // bound atom registers here, not in the bool-var lists.
    for (list in atoms.watchersByLit) {
        if (list == null) continue
        var wi = 0
        for (r in 0 until list.size) {
            val fid = list[r]
            if (fid < refBase) {
                list[wi++] = fid
            } else {
                val newLearnedIdx = remap[fid - refBase]
                if (newLearnedIdx >= 0) list[wi++] = refBase + newLearnedIdx
            }
        }
        list.truncateTo(wi)
    }

    // The compaction renumbered learned fids and shifted every list position, so the
    // back-pointer index is stale — rebuild it wholesale from the final lists. Cheap
    // relative to the rest of forget, which is itself infrequent (≈ once per restart).
    watches.pos.clear()
    for (lit in watches.byLit.indices) {
        val list = watches.byLit[lit]
        for (i in 0 until list.size) watches.pos.put(packWatch(list[i], lit), i)
    }

    // A conflict return leaves the propagation queues holding in-flight fids, and the
    // engine forgets at the following restart — so the queues can still carry learned
    // fids from before the compaction. Remap them like the watcher lists: a stale fid
    // surviving here indexes past the compacted clause array on the next drain.
    remapQueue(propQueue, remap, refBase)
    var pending = 0
    for (i in 0 until learned.pendingAttach.size) {
        val old = learned.pendingAttach[i] - refBase
        if (remap[old] >= 0) learned.pendingAttach[pending++] = refBase + remap[old]
    }
    learned.pendingAttach.truncateTo(pending)
    remapQueue(atoms.dirtyFactors, remap, refBase)
    if (learned.integerRows != null) intEvents.remapLearned(remap, refBase)

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
        undo.minReason[i] = remappedReason(undo.minReason[i], remap, refBase)
        undo.maxReason[i] = remappedReason(undo.maxReason[i], remap, refBase)
    }
}

/** Rewrite learned-clause factor ids stored in a per-variable reason array through [remap]
 *  (static fids `< refBase` pass through; dropped clauses' reasons clear to -1). */
internal fun PropagationState.remapReasons(reasons: IntArray, remap: IntArray, refBase: Int) {
    for (i in reasons.indices) {
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
    if (learned.stages[index] and 4 != 0) return
    learned.stages[index] = learned.stages[index] or 4
    learned.pendingAttach.add(fid)
}

private fun PropagationState.noteLearnedStage(index: Int, stage: LearnedStage, bit: Int) {
    if (learned.stages[index] and bit != 0) return
    learned.stages[index] = learned.stages[index] or bit
    learned.lifecycle.note(learnedKind(index), stage)
    if (stage == LearnedStage.Consumed) factorAttribution?.consumed(learned.store[index])
}

internal fun PropagationState.referencedLearned(count: Int): BooleanArray {
    val retained = BooleanArray(count)
    fun protect(fid: Int) {
        val index = fid - baseFactorCount
        if (index in retained.indices) retained[index] = true
    }
    boolReason.forEach(::protect)
    intMinReason.forEach(::protect)
    intMaxReason.forEach(::protect)
    for (i in 0 until undo.size) if (undo.tag[i] == 1) {
        protect(undo.minReason[i])
        protect(undo.maxReason[i])
    }
    for (i in 0 until learned.pendingAttach.size) {
        val fid = learned.pendingAttach[i]
        if (learned.stages[fid - baseFactorCount] and 2 == 0) protect(fid)
    }
    return retained
}

private fun remappedReason(fid: Int, remap: IntArray, base: Int): Int =
    if (fid < base) fid else remap.getOrNull(fid - base)?.takeIf { it >= 0 }?.let { base + it } ?: -1
