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
    canDrop: (Int) -> Boolean,
    keep: (Int) -> Boolean,
): Int {
    learned.capCalls++
    if (incremental || nativeEngine != null || pendingFiniteMutation != null || rootFixpointPending || runCancelled) {
        learned.capBoundaryDefers++
        return 0
    }
    val budget = ReclamationPreparation(maxWork, maxScratchBytes, cancelled) {
        work += it
        learned.capWork += it
    }
    val retiredBefore = learned.retiredCount
    try {
        budget.visit(0L, 64L)
        budget.visit(learned.size.toLong(), learned.size.toLong())
        val protected = BooleanArray(learned.size)
        collectLearnedReferences(protected) { budget.visit() }
        budget.visit(0L)
        learned.capRanking.advance(cap, learned.permanentClauseCount + learned.nonClauseCount, budget,
            generalCapPolicy(protected, budget, canDrop, keep))
    } catch (_: ReclamationPreparation.Deferred) {
        learned.capPreparationDefers++
        // Completed row transactions retain their handles; the next root rebuilds reason protection.
    }
    return learned.retiredCount - retiredBefore
}

private fun PropagationState.generalCapPolicy(
    protected: BooleanArray,
    budget: ReclamationPreparation,
    mayDrop: (Int) -> Boolean,
    retain: (Int) -> Boolean,
): LearnedCapPolicy = object : LearnedCapPolicy {
    override fun lbd(index: Int): Int = learned.lbds[index]
    override fun prepare(index: Int): Boolean {
        budget.visit(32L)
        return true
    }
    override fun keep(index: Int): Boolean {
        val retained = retain(index)
        val clause = learned.store[index] as ClausePropagator
        return retained || protected[index] || clause.literals.size <= 1 || learned.stages[index] and 1 == 0
    }
    override fun visited() { learned.capVisits++ }
    override fun retire(index: Int): Boolean {
        val clause = learned.store[index] as ClausePropagator
        if (!mayDrop(index) || protected[index] || clause.literals.size <= 1 || learned.stages[index] and 1 == 0) {
            return false
        }
        budget.visit(8L)
        budget.visit(0L)
        learned.retire(index)
        factorAttribution?.retire(clause)
        forgetDeferredClause(clause.literals)
        learned.store[index] = RetiredLearnedPropagator
        refPayloadStore[baseFactorCount + index] = null
        return true
    }
}
