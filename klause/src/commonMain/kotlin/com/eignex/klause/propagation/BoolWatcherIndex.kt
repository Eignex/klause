package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.util.collections.IntArrayList

// The index drifts across snapshot / restore on purpose: the invariant is "watch is on a non-false literal",
// and a pop only reverts pins, which adds non-false literals.
internal class BoolWatcherIndex(
    numBoolVars: Int,
    preparedWatches: Array<IntArrayList>? = null,
    preparedBlockers: Array<IntArrayList>? = null,
    preparedPositions: BoolWatchPositionIndex? = null,
    baseFactorCount: Int = 0,
) {
    /** Factor ids to fire when the literal ([Lit.make] encoding) becomes false. */
    val byLit: Array<IntArrayList> = preparedWatches ?: Array(2 * numBoolVars) { IntArrayList(initialCapacity = 2) }

    // Index-aligned with [byLit]: a true blocker proves the watcher satisfied, so the wake is skipped. A stale
    // blocker is sound because it is still a real literal of the factor. [NO_BLOCKER] always fires.
    val blockersByLit: Array<IntArrayList> =
        preparedBlockers ?: Array(2 * numBoolVars) { IntArrayList(initialCapacity = 2) }

    // Back pointers `pack(fid, lit)` → position in `byLit[lit]`, so removing a moved watch is a swap-pop instead
    // of a scan of a popular literal's list. `removeBoolWatch` verifies `list[pos] == fid` and falls back to the
    // scan on mismatch, so a desynced index never removes the wrong watcher.
    var pos: BoolWatchPositionIndex = preparedPositions ?: BoolWatchPositionIndex(baseFactorCount)
    companion object {
        fun preparation(numBoolVars: Int, baseFactorCount: Int): Sequence<BoolWatcherIndex?> = sequence {
            val watches = arrayOfNulls<IntArrayList>(2 * numBoolVars)
            yield(null)
            val blockers = arrayOfNulls<IntArrayList>(2 * numBoolVars)
            yield(null)
            for (lit in watches.indices) {
                watches[lit] = IntArrayList(initialCapacity = 2)
                blockers[lit] = IntArrayList(initialCapacity = 2)
                if ((lit + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            val positions = BoolWatchPositionIndex(baseFactorCount)
            yield(null)
            @Suppress("UNCHECKED_CAST")
            yield(BoolWatcherIndex(
                numBoolVars, watches as Array<IntArrayList>, blockers as Array<IntArrayList>, positions,
            ))
        }
    }
}
