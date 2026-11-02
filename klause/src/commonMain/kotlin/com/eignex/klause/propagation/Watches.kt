package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.util.IntArrayList

internal fun PropagationState.packWatch(fid: Int, lit: Int): Long =
    (fid.toLong() shl 32) or (lit.toLong() and 0xFFFFFFFFL)

/**
 * Move factor `[factorId]`'s registration from [oldLit] to [newLit] in
 * [BoolWatcherIndex.byLit]. Called by watcher-using factors when they relocate a watch
 * during propagation. Removal swaps the tail posting into the indexed slot;
 * insertion retains its balanced back pointer.
 */
internal fun PropagationState.moveBoolWatcher(factorId: Int, oldLit: Int, newLit: Int, blocker: Int = NO_BLOCKER) {
    if (oldLit == newLit) return
    val oldV = Lit.variable(oldLit)
    if (oldV < problem.numBoolVars) {
        removeBoolWatch(factorId, oldLit)
    } else {
        val index = atomLitWatchIndex(oldLit)
        val posting = atoms.watchersByLit[index]
        if (posting != null) {
            val position = posting.indexOf(factorId)
            if (position >= 0) {
                posting.removeAt(position)
                rewindWatchCleanup(problem.numBoolVars * 2 + index, position)
            }
        }
    }
    // Install on new, carrying the blocking literal supplied by the watcher-using factor.
    // Defaults to NO_BLOCKER for factors that don't track blockers.
    installLitWatch(newLit, factorId, blocker)
}

/** Indexed removal of `[factorId]` from `watches.byLit[lit]` via the [BoolWatcherIndex.pos]
 *  back-pointer: swap the tail entry into the freed slot and fix its recorded position.
 *  Verifies the recorded position actually holds `[factorId]`; on any miss/mismatch falls
 *  back to the linear swap-remove and self-heals the index, so a stale back-pointer can
 *  never remove the wrong watcher. */
internal fun PropagationState.removeBoolWatch(factorId: Int, lit: Int, onlyIndexed: Boolean = false) {
    val list = watches.byLit[lit]
    val blockers = watches.blockersByLit[lit]
    val key = packWatch(factorId, lit)
    // Remove-and-read in a single table walk; the key is gone from the index either way.
    val recorded = watches.pos.removeAndGet(key, -1)
    work += watches.pos.lastWork
    if (recorded < 0 && onlyIndexed) return
    if (recorded < 0 || recorded >= list.size || list[recorded] != factorId) {
        // Index miss/desync — fall back to a linear scan, swap-pop both lists in lockstep
        // at the found index, and resync this lit's positions.
        var pos = -1
        for (i in 0 until list.size) {
            if (list[i] == factorId) {
                pos = i
                break
            }
        }
        if (pos >= 0) {
            swapPopWatch(list, blockers, pos)
            rewindWatchCleanup(lit, pos)
        }
        resyncBoolWatchPos(lit)
        return
    }
    val last = list.size - 1
    if (recorded != last) {
        val movedFid = list[last]
        work += watches.pos.put(packWatch(movedFid, lit), recorded)
    }
    swapPopWatch(list, blockers, recorded)
    rewindWatchCleanup(lit, recorded)
}

/** Swap-pop index [pos] from a watcher list and its parallel blocker list in lockstep,
 *  keeping the two index-aligned. The caller fixes [BoolWatcherIndex.pos] for the moved entry. */
internal fun PropagationState.swapPopWatch(list: IntArrayList, blockers: IntArrayList, pos: Int) {
    val last = list.size - 1
    if (pos != last) {
        list[pos] = list[last]
        blockers[pos] = blockers[last]
    }
    list.truncateTo(last)
    blockers.truncateTo(last)
}

internal fun PropagationState.rewindWatchCleanup(lit: Int, index: Int) {
    if (lit == learned.watchCleanupLiteral && index < learned.watchCleanupIndex) learned.watchCleanupIndex = index
}

/** Recompute every recorded position for [lit]'s watcher list (used by the self-heal
 *  fallback in [removeBoolWatch]). */
internal fun PropagationState.resyncBoolWatchPos(lit: Int) {
    val list = watches.byLit[lit]
    for (i in 0 until list.size) {
        work += watches.pos.put(packWatch(list[i], lit), i)
    }
}
