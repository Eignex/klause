package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayList

internal fun PropagationState.sweepRetiredGeneralWatches(budget: ReclamationPreparation) {
    if (incremental || nativeEngine != null || learned.retiredCount == 0) return
    if (learned.sweepingRetirements < 0) {
        if (learned.sweptRetirements == learned.retiredCount) return
        budget.visit(4L)
        budget.visit(0L)
        learned.sweepingRetirements = learned.retiredCount
    }
    val boolCount = problem.numBoolVars * 2
    val index = initializedWatches
    if (index == null && learned.watchCleanupLiteral < boolCount) {
        budget.visit(4L)
        budget.visit(0L)
        learned.watchCleanupLiteral = boolCount
        learned.watchCleanupIndex = 0
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
    }
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

private fun trimWatchPosting(posting: IntArrayList, blockers: IntArrayList?, budget: ReclamationPreparation) {
    val size = posting.size.coerceAtLeast(1).toLong()
    var copies = if (posting.backingData.size > size) 1L else 0L
    if (blockers != null && blockers.backingData.size > size) copies++
    if (copies == 0L) return
    val scratch = copies * size * Int.SIZE_BYTES
    val work = 4L + copies * (size * 2L + 1L)
    if (!budget.supports(work, scratch)) return
    budget.visit(work, scratch)
    budget.visit(0L)
    posting.trim()
    blockers?.trim()
}
