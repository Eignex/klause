package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.util.collections.IntArrayList

internal fun PropagationState.packWatch(fid: Int, lit: Int): Long =
    (fid.toLong() shl 32) or (lit.toLong() and 0xFFFFFFFFL)

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
                posting.removeAtSwap(position)
                rewindWatchCleanup(problem.numBoolVars * 2 + index, position)
            }
        }
    }
    installLitWatch(newLit, factorId, blocker)
}

// A stale back pointer falls back to a linear scan and self-heals, so it never removes the wrong watcher.
internal fun PropagationState.removeBoolWatch(factorId: Int, lit: Int, onlyIndexed: Boolean = false) {
    val list = watches.byLit[lit]
    val blockers = watches.blockersByLit[lit]
    val key = packWatch(factorId, lit)
    val recorded = watches.pos.removeAndGet(key, -1)
    work += watches.pos.lastWork
    if (recorded < 0 && onlyIndexed) return
    if (recorded < 0 || recorded >= list.size || list[recorded] != factorId) {
        // Index desync: linear scan, then resync this literal's positions.
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

internal fun PropagationState.removeInlineBoolWatches(factor: Int, pairExpected: Boolean): Boolean {
    val positions = watches.pos.detachInline(factor, pairExpected, watches.byLit)
    work += watches.pos.lastWork
    if (positions == null) return false
    val firstLiteral = positions.firstKey
    val firstPosition = positions.firstValue
    val secondLiteral = positions.secondKey
    val secondPosition = positions.secondValue
    fun moved(literal: Int, position: Int): Int {
        if (literal < 0) return -1
        val list = watches.byLit[literal]
        val movedFactor = if (position < list.size - 1) list[list.size - 1] else -1
        swapPopWatch(list, watches.blockersByLit[literal], position)
        rewindWatchCleanup(literal, position)
        return movedFactor
    }
    val firstMoved = moved(firstLiteral, firstPosition)
    val secondMoved = moved(secondLiteral, secondPosition)
    if (firstMoved >= 0 && firstMoved == secondMoved) {
        work += watches.pos.updatePair(firstMoved, firstLiteral, firstPosition, secondLiteral, secondPosition)
    } else {
        if (firstMoved >= 0) work += watches.pos.put(packWatch(firstMoved, firstLiteral), firstPosition)
        if (secondMoved >= 0) work += watches.pos.put(packWatch(secondMoved, secondLiteral), secondPosition)
    }
    return true
}

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

internal fun PropagationState.resyncBoolWatchPos(lit: Int) {
    val list = watches.byLit[lit]
    for (i in 0 until list.size) {
        work += watches.pos.put(packWatch(list[i], lit), i)
    }
}
