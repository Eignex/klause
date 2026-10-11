package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableLongIntMap

/**
 * Per-literal wakeup index for factors opting into [Propagator.initialBoolWatchers], plus the
 * blocking-literal and back-pointer companions held in lockstep. Mutation lives in
 * `Watches.kt` (`installLitWatch` / `moveBoolWatcher`) and the `forgetLearnedClauses` compaction.
 *
 * Like `refPayload`, the index drifts across snapshot / restore on purpose. After a pop the watches
 * reflect their state at the deepest level reached — that's still sound, since the invariant is
 * "watch is on a non-false literal", and pop reverts pins which only *adds* non-false literals.
 */
internal class BoolWatcherIndex(
    numBoolVars: Int,
    preparedWatches: Array<IntArrayList>? = null,
    preparedBlockers: Array<IntArrayList>? = null,
    preparedPositions: MutableLongIntMap? = null,
) {
    /**
     * Slot `byLit[lit]` lists factor ids that should fire when literal `lit` transitions to false.
     * Sized `2 * numBoolVars`; lit ids are the standard [Lit.make] encoding. Populated at
     * construction from each factor's initial watch set; factors with dynamic watches (Clause)
     * keep it in sync via `moveBoolWatcher` as their watches drift during propagation.
     */
    val byLit: Array<IntArrayList> = preparedWatches ?: Array(2 * numBoolVars) { IntArrayList(initialCapacity = 2) }

    /**
     * Blocking literals paired index-for-index with [byLit]. Entry `i` holds a literal
     * that, if currently true, proves the watcher at the same index is already satisfied — so
     * `enqueueForBoolChange` can skip waking that factor entirely, removing a large fraction of
     * clause touches in the hot BCP loop on dense instances. [NO_BLOCKER] means "no blocker,
     * always fire", the default for every factor that doesn't supply
     * [Propagator.initialBoolWatcherBlockers] (e.g. cardinality). A stale blocker is always
     * sound because it is still a real literal of the factor — if true the factor really is
     * satisfied; if not the watcher simply fires.
     */
    val blockersByLit: Array<IntArrayList> =
        preparedBlockers ?: Array(2 * numBoolVars) { IntArrayList(initialCapacity = 2) }

    /**
     * Back-pointer index for O(1) `moveBoolWatcher` removal: maps `pack(fid, lit)` to
     * `fid`'s position inside `byLit[lit]`, so removing a moved watch is a swap-pop at a known
     * index instead of a linear scan of a possibly-long popular-literal list.
     *
     * Kept in sync at every list mutation: `installLitWatch` records the appended position,
     * `moveBoolWatcher` swap-pops and fixes the swapped element's recorded position, and
     * `forgetLearnedClauses` rebuilds it wholesale after compacting/remapping the lists.
     *
     * Correctness guard: `moveBoolWatcher` verifies `list[pos] == fid` before swap-popping and
     * falls back to the linear scan on any mismatch — a desynced index can never silently remove
     * the wrong watcher.
     */
    val pos: MutableLongIntMap = preparedPositions ?: MutableLongIntMap()
    companion object {
        fun preparation(numBoolVars: Int, watcherCount: Int): Sequence<BoolWatcherIndex?> = sequence {
            val watches = arrayOfNulls<IntArrayList>(2 * numBoolVars)
            yield(null)
            val blockers = arrayOfNulls<IntArrayList>(2 * numBoolVars)
            yield(null)
            for (lit in watches.indices) {
                watches[lit] = IntArrayList(initialCapacity = 2)
                blockers[lit] = IntArrayList(initialCapacity = 2)
                if ((lit + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            @Suppress("UNCHECKED_CAST")
            val positions = MutableLongIntMap(watcherCount + 1)
            yield(null)
            yield(BoolWatcherIndex(
                numBoolVars, watches as Array<IntArrayList>, blockers as Array<IntArrayList>, positions,
            ))
        }
    }

}
