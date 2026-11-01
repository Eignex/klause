package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.ClausePropagator

internal fun PropagationState.retiredLearnedFactor(factor: Int): Boolean =
    learned.retiredCount > 0 && !incremental && nativeEngine == null && factor >= baseFactorCount &&
        factor - baseFactorCount < learned.size && learned.retiredOf(factor - baseFactorCount)

internal fun PropagationState.maintainGeneralCapWindow(
    cap: Int,
    maxWork: Long,
    maxScratchBytes: Long,
    cancelled: () -> Boolean,
    keep: (Int) -> Boolean,
): Int {
    learned.capCalls++
    if (incremental || nativeEngine != null || pendingFiniteMutation != null || rootFixpointPending || runCancelled) {
        learned.capBoundaryDefers++
        return 0
    }
    if (learned.permanentClauseCount < cap) {
        learned.capFloorDefers++
        return 0
    }
    val budget = ReclamationPreparation(maxWork, maxScratchBytes, cancelled) {
        work += it
        learned.capWork += it
    }
    var retired = 0
    try {
        budget.visit(0L, 64L)
        budget.visit(learned.size.toLong(), learned.size.toLong())
        val protected = BooleanArray(learned.size)
        collectLearnedReferences(protected) { budget.visit() }
        budget.visit(0L)
        while (learned.capRead < learned.capCandidates.size) {
            budget.visit(32L)
            budget.visit(0L)
            val index = learned.capCandidates[learned.capRead]
            val clause = learned.store[index] as ClausePropagator
            val pinned = protected[index] || clause.literals.size <= 1 || learned.stages[index] and 1 == 0
            if (!keep(index) && !pinned) {
                learned.retire(index)
                factorAttribution?.retire(clause)
                retired++
            }
            if (!learned.retiredOf(index)) learned.capCandidates[learned.capWrite++] = index
            learned.capRead++
            learned.capVisits++
        }
        learned.capCandidates.truncateTo(learned.capWrite)
        learned.capRead = 0
        learned.capWrite = 0
    } catch (_: ReclamationPreparation.Deferred) {
        learned.capPreparationDefers++
        // Completed row transactions retain their handles; the next root rebuilds reason protection.
    }
    return retired
}
