package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList

/**
 * Invoke [action] with the *other* literal of every binary clause that contains [lit]
 * (#202). Binary clauses watch both their literals and never relocate a watch (there is
 * no third literal to move to), so [PropagationState.boolWatchersByLit] reliably lists every binary clause
 * on [lit]. No-op for atom-literal [lit] (the bool watcher index only covers bool vars).
 */
internal fun PropagationState.forEachBinaryPartner(lit: Int, action: (other: Int) -> Unit) {
    if (Lit.variable(lit) >= problem.numBoolVars) return
    val list = boolWatchersByLit[lit]
    for (i in 0 until list.size) {
        val f = factorAt(list[i])
        if (f is Clause && f.literals.size == 2) {
            val a = f.literals[0]
            val b = f.literals[1]
            when (lit) {
                a -> action(b)
                b -> action(a)
            }
        }
    }
}

/** Unified factor accessor; routes static factor ids to [Problem.factors] and learned
 *  factor ids (≥ `problem.numFactors`) to [PropagationState.learnedClauses]. */
internal fun PropagationState.factorAt(fid: Int): Factor = if (fid < baseFactorCount) {
    baseFactors[fid]
} else {
    learnedClauseStore[fid - baseFactorCount]
}

/**
 * Register a learned clause and return its assigned factor id. Performs four things:
 *   - append to [PropagationState.learnedClauseStore];
 *   - record the clause's [lbd] in [PropagationState.learnedLbds] (parallel array);
 *   - grow [PropagationState.refPayloadStore] by one slot so [Clause.propagate]'s
 *     `state.refPayload[factorId]` access stays in-bounds;
 *   - install the clause's initial watch literals in [PropagationState.boolWatchersByLit] so it
 *     participates in the wakeup index.
 *
 * Does NOT eagerly propagate — that's the session-level
 * [PropagationSession.addLearnedClause]'s job. Returns the new factor id.
 */
internal fun PropagationState.addLearnedClause(clause: Clause, lbd: Int, permanent: Boolean = false): Int {
    val newFid = totalFactorCount
    learnedClauseStore.add(clause)
    learnedLbds.add(lbd)
    learnedPermanent.add(if (permanent) 1 else 0)
    learnedTier.add(ClauseTier.UNSET.ordinal)
    learnedUsedFlags.add(0)
    if (clause.literals.size == 2) binaryClauseCount++ // keep the #202 gate current
    refPayloadStore.add(null)
    val watchers = clause.initialBoolWatchers
    val blockers = clause.initialBoolWatcherBlockers
    for (i in watchers.indices) installLitWatch(watchers[i], newFid, blockers?.getOrNull(i) ?: NO_BLOCKER)
    return newFid
}

/** Read-only view of LBDs for tests / introspection. Parallel to [PropagationState.learnedClauses]. */
internal fun PropagationState.learnedClauseLbd(learnedIndex: Int): Int = learnedLbds[learnedIndex]

/** True iff learned clause [learnedIndex] must survive every forgetting pass. */
internal fun PropagationState.learnedClausePermanent(learnedIndex: Int): Boolean = learnedPermanent[learnedIndex] == 1

/** Three-tier (#201) DB tier of learned clause [learnedIndex] ([ClauseTier.UNSET] until the
 *  reduction policy classifies it). */
internal fun PropagationState.learnedClauseTier(learnedIndex: Int): ClauseTier =
    ClauseTier.entries[learnedTier[learnedIndex]]

/** Set the three-tier DB tier of learned clause [learnedIndex] (promotion / demotion /
 *  initial classification by the reduction policy). */
internal fun PropagationState.setLearnedClauseTier(learnedIndex: Int, tier: ClauseTier) {
    learnedTier[learnedIndex] = tier.ordinal
}

/** True iff learned clause [learnedIndex] was used (conflict or unit) since the last
 *  reduction. */
internal fun PropagationState.learnedClauseUsedSinceReduction(learnedIndex: Int): Boolean =
    learnedUsedFlags[learnedIndex] == 1

/** Clear the reuse flag for learned clause [learnedIndex] — called for survivors at the
 *  end of a reduction so the next window measures fresh activity. */
internal fun PropagationState.clearLearnedClauseUsed(learnedIndex: Int) {
    learnedUsedFlags[learnedIndex] = 0
}

/** Mark learned clause [fid] (a factor id; ignored when it isn't a learned clause) as
 *  used since the last reduction — it just detected a conflict or forced a unit. Drives
 *  three-tier promotion (#201). */
internal fun PropagationState.noteLearnedUse(fid: Int) {
    val idx = fid - problem.numFactors
    if (idx in 0 until learnedUsedFlags.size) learnedUsedFlags[idx] = 1
}

/**
 * Prune the learned-clause database. The [keep] predicate decides per (learnedIndex,
 * lbd) whether to retain that clause; dropped clauses' factor ids vanish and the
 * remaining clauses are renumbered contiguously starting at `problem.numFactors`.
 * Three things are rebuilt:
 *   - [PropagationState.learnedClauseStore] / [PropagationState.learnedLbds] compacted to the kept entries in order;
 *   - the learned-clause tail of [PropagationState.refPayloadStore] compacted similarly;
 *   - every list in [PropagationState.boolWatchersByLit] walked once, with learned factor ids
 *     remapped through `oldFid → newFid` or removed when dropped.
 *
 * Watchers' positions inside each clause's `refPayload[fid]` are watch *indices*
 * (into `clause.literals`), not factor ids — they survive the compaction unchanged.
 * Cost is amortised across infrequent calls (typical: once per Luby restart).
 */
internal fun PropagationState.forgetLearnedClauses(keep: (learnedIndex: Int, lbd: Int) -> Boolean) {
    val n = learnedClauseStore.size
    if (n == 0) return
    val remap = IntArray(n) // remap[i] = new learned index, or -1 if dropped
    var newCount = 0
    for (i in 0 until n) {
        remap[i] = if (keep(i, learnedLbds[i])) newCount++ else -1
    }
    if (newCount == n) return // nothing dropped

    // Compact learnedClauseStore + learnedLbds in place using a two-pointer walk —
    // every kept entry slides down to its new position; tail beyond newCount is
    // trimmed at the end.
    var w = 0
    for (i in 0 until n) {
        if (remap[i] >= 0) {
            learnedClauseStore[w] = learnedClauseStore[i]
            learnedLbds[w] = learnedLbds[i]
            learnedPermanent[w] = learnedPermanent[i]
            learnedTier[w] = learnedTier[i]
            learnedUsedFlags[w] = learnedUsedFlags[i]
            w++
        }
    }
    while (learnedClauseStore.size > newCount) learnedClauseStore.removeAt(learnedClauseStore.size - 1)
    learnedLbds.truncateTo(newCount)
    learnedPermanent.truncateTo(newCount)
    learnedTier.truncateTo(newCount)
    learnedUsedFlags.truncateTo(newCount)

    // Compact the learned tail of refPayloadStore similarly. Static-factor entries stay
    // at indices [0, problem.numFactors) untouched.
    val refBase = problem.numFactors
    var rw = refBase
    for (i in 0 until n) {
        if (remap[i] >= 0) {
            refPayloadStore[rw] = refPayloadStore[refBase + i]
            rw++
        }
    }
    while (refPayloadStore.size > rw) refPayloadStore.removeAt(refPayloadStore.size - 1)

    // Remap each per-literal watcher list. Static fids pass through; learned fids
    // either rewrite to their new factor id or get dropped.
    for (lit in boolWatchersByLit.indices) {
        val list = boolWatchersByLit[lit]
        val blockers = boolBlockersByLit[lit] // compacted in lockstep so indices stay aligned
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
    // bound atom registers here, not in the bool-var lists. Skipping this remap left
    // stale fids pointing past the compacted clause array, crashing the next atom wake
    // on any model whose conflicts learn atom-literal clauses.
    for (list in atomWatchersByLit.values) {
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
    boolWatchPos.clear()
    for (lit in boolWatchersByLit.indices) {
        val list = boolWatchersByLit[lit]
        for (i in 0 until list.size) boolWatchPos.put(packWatch(list[i], lit), i)
    }

    // A conflict return leaves the propagation queues holding in-flight fids, and the
    // engine forgets at the following restart — so the queues can still carry learned
    // fids from before the compaction. Remap them like the watcher lists: a stale fid
    // surviving here indexes past the compacted clause array on the next drain.
    remapQueue(propQueue, remap, refBase)
    remapQueue(dirtyAtomFactors, remap, refBase)

    // The per-variable reason fields record which factor forced each currently-implied
    // value, learned-clause ids included. Level-0 facts — e.g. a permanent blocking nogood
    // or a learned unit that propagated at the root — survive the restart's pop-to-root, so
    // their reason fids outlive this renumber. Left unremapped, the next conflict's
    // [extractConflictFactors] would dereference a stale learned fid through [factorAt] and
    // index past the compacted clause array (the php8 crash). Remap them like the watchers:
    // a kept clause's reason rewrites to its new id, a dropped clause's reason clears to -1.
    remapReasons(boolReason, remap, refBase)
    remapReasons(intMinReason, remap, refBase)
    remapReasons(intMaxReason, remap, refBase)
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
