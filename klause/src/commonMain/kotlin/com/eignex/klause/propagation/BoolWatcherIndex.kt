package com.eignex.klause.propagation

import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableLongIntMap

/**
 * Per-literal wakeup index for factors opting into [Propagator.initialBoolWatchers], plus the
 * blocking-literal (#200) and back-pointer (#42) companions held in lockstep. Mutation lives in
 * `Watches.kt` (`installLitWatch` / `moveBoolWatcher`) and the `forgetLearnedClauses` compaction.
 *
 * Like `refPayload`, the index drifts across snapshot / restore on purpose. After a pop the watches
 * reflect their state at the deepest level reached — that's still sound, since the invariant is
 * "watch is on a non-false literal", and pop reverts pins which only *adds* non-false literals.
 */
internal class BoolWatcherIndex(numBoolVars: Int) {
    /**
     * Slot `byLit[lit]` lists factor ids that should fire when literal `lit` transitions to false.
     * Sized `2 * numBoolVars`; lit ids are the standard [Lit.make] encoding. Populated at
     * construction from each factor's initial watch set; factors with dynamic watches (Clause)
     * keep it in sync via `moveBoolWatcher` as their watches drift during propagation.
     */
    val byLit: Array<IntArrayList> = Array(2 * numBoolVars) { IntArrayList(initialCapacity = 2) }

    /**
     * Blocking literals paired index-for-index with [byLit] (#200). Entry `i` holds a literal
     * that, if currently true, proves the watcher at the same index is already satisfied — so
     * `enqueueForBoolChange` can skip waking that factor entirely, removing a large fraction of
     * clause touches in the hot BCP loop on dense instances. [NO_BLOCKER] means "no blocker,
     * always fire", the default for every factor that doesn't supply
     * [Propagator.initialBoolWatcherBlockers] (e.g. cardinality), so behaviour for those is
     * unchanged. A stale blocker is always sound because it is still a real literal of the
     * factor — if true the factor really is satisfied; if not we simply fire as before.
     */
    val blockersByLit: Array<IntArrayList> = Array(2 * numBoolVars) { IntArrayList(initialCapacity = 2) }

    /**
     * Back-pointer index for O(1) `moveBoolWatcher` removal (#42): maps `pack(fid, lit)` to
     * `fid`'s position inside `byLit[lit]`, so removing a moved watch is a swap-pop at a known
     * index instead of a linear scan of a possibly-long popular-literal list.
     *
     * Kept in sync at every list mutation: `installLitWatch` records the appended position,
     * `moveBoolWatcher` swap-pops and fixes the swapped element's recorded position, and
     * `forgetLearnedClauses` rebuilds it wholesale after compacting/remapping the lists.
     *
     * Correctness guard: `moveBoolWatcher` verifies `list[pos] == fid` before swap-popping and
     * falls back to the linear scan on any mismatch — a desynced index can never silently remove
     * the wrong watcher (the soundness hazard called out in #42).
     */
    val pos: MutableLongIntMap = MutableLongIntMap()
}
