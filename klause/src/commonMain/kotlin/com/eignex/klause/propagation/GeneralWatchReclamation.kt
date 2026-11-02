package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableLongIntMap

internal fun PropagationState.sweepRetiredGeneralWatches(budget: ReclamationPreparation) {
    if (incremental || nativeEngine != null || learned.retiredCount == 0) return
    if (learned.sweepingRetirements < 0) {
        if (learned.sweptRetirements == learned.retiredCount &&
            (learned.deferredWatchTrimWork == 0L ||
                !budget.supports(learned.deferredWatchTrimWork, learned.deferredWatchTrimScratch))
        ) {
            trimEmptyWatchPositions(budget)
            return
        }
        budget.visit(8L)
        budget.visit(0L)
        learned.sweepingRetirements = learned.retiredCount
        learned.deferredWatchTrimWork = 0L
        learned.deferredWatchTrimScratch = 0L
    }
    val boolCount = problem.numBoolVars * 2
    val index = initializedWatches
    if (index == null && learned.watchCleanupLiteral < boolCount) {
        budget.visit(4L)
        budget.visit(0L)
        learned.watchCleanupLiteral = boolCount
        learned.watchCleanupIndex = 0
        budget.advanced()
    }
    while (learned.watchCleanupLiteral < boolCount + atoms.watchersByLit.size) {
        budget.visit(4L)
        budget.visit(0L)
        val literal = learned.watchCleanupLiteral
        val posting = if (literal < boolCount) index?.byLit?.get(literal)
            else atoms.watchersByLit[literal - boolCount]
        if (posting == null || learned.watchCleanupIndex >= posting.size) {
            posting?.let { trimWatchPosting(it, if (literal < boolCount) index?.blockersByLit?.get(literal)
                else null, budget) }
            learned.watchCleanupLiteral++
            learned.watchCleanupIndex = 0
        } else if (retiredLearnedFactor(posting[learned.watchCleanupIndex])) {
            if (!removeRetiredWatch(literal, posting, budget)) return
        } else learned.watchCleanupIndex++
        budget.advanced()
    }
    trimEmptyWatchPositions(budget)
    budget.visit(4L)
    budget.visit(0L)
    learned.sweptRetirements = learned.sweepingRetirements
    learned.sweepingRetirements = -1
    learned.watchCleanupLiteral = 0
    learned.watchCleanupIndex = 0
}

private fun PropagationState.removeRetiredWatch(
    literal: Int,
    posting: IntArrayList,
    budget: ReclamationPreparation,
): Boolean {
    val position = learned.watchCleanupIndex
    budget.visit(12L)
    val boolCount = problem.numBoolVars * 2
    if (literal < boolCount) {
        val factor = posting[position]
        val moved = posting[posting.size - 1]
        val replacement = if (position != posting.size - 1 && factor != moved) packWatch(moved, literal) else null
        val removed = watches.pos.removeAndUpdate(packWatch(factor, literal), replacement, position,
            { budget.visit(it) }, { budget.visit(0L) })
        if (!removed) return false
        swapPopWatch(posting, watches.blockersByLit[literal], position)
    } else {
        budget.visit(0L)
        posting.removeAt(position)
    }
    return true
}

private fun PropagationState.trimWatchPosting(
    posting: IntArrayList,
    blockers: IntArrayList?,
    budget: ReclamationPreparation,
) {
    trimPostingArray(posting, budget)
    if (blockers != null) trimPostingArray(blockers, budget)
}

private fun PropagationState.trimPostingArray(posting: IntArrayList, budget: ReclamationPreparation) {
    val size = posting.size.coerceAtLeast(1).toLong()
    if (posting.backingData.size <= size) return
    val work = 5L + size * 2L
    val scratch = size * Int.SIZE_BYTES
    if (!budget.supports(work, scratch)) {
        if (learned.deferredWatchTrimWork == 0L || work < learned.deferredWatchTrimWork) {
            budget.visit(4L)
            budget.visit(0L)
            learned.deferredWatchTrimWork = work
            learned.deferredWatchTrimScratch = scratch
        }
        return
    }
    budget.visit(work, scratch)
    budget.visit(0L)
    posting.trim()
    budget.advanced()
}

private fun PropagationState.trimEmptyWatchPositions(budget: ReclamationPreparation) {
    val index = initializedWatches ?: return
    if (index.pos.size != 0 || index.pos.primitiveCapacityBytes <= 104L || !budget.supports(64L, 192L)) return
    budget.visit(64L, 192L)
    budget.visit(0L)
    index.pos = MutableLongIntMap()
    budget.advanced()
}
