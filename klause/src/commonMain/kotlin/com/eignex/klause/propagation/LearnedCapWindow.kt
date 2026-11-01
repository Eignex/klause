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
        learned.capRanking.advance(cap, learned.permanentClauseCount + learned.permanentArithmeticCount, budget,
            generalCapPolicy(budget, canDrop, keep))
    } catch (_: ReclamationPreparation.Deferred) {
        learned.capPreparationDefers++
        // Completed row transactions retain their handles; each resumed row rechecks its current owners.
    }
    return learned.retiredCount - retiredBefore
}

private fun PropagationState.generalCapPolicy(
    budget: ReclamationPreparation,
    mayDrop: (Int) -> Boolean,
    retain: (Int) -> Boolean,
): LearnedCapPolicy = object : LearnedCapPolicy {
    override fun alive(index: Int): Boolean = !learned.retiredOf(index)
    private var references: BooleanArray? = null
    private var prepared = -1
    private var protected = false
    private fun retainedReason(index: Int): Boolean {
        if (learned.stages[index] and 16 != 0) {
            return learned.boolReasons[index] > 0 || learned.stages[index] and 4 != 0
        }
        val snapshot = references ?: run {
            budget.visit(learned.size.toLong(), learned.size.toLong())
            BooleanArray(learned.size).also {
                collectLearnedReferences(it) { budget.visit() }
                references = it
            }
        }
        return snapshot[index]
    }
    override fun lbd(index: Int): Int = learned.lbds[index]
    override fun prepare(index: Int): Boolean {
        budget.visit(32L)
        protected = retainedReason(index)
        prepared = index
        return true
    }
    override fun keep(index: Int): Boolean {
        val retained = retain(index)
        val prop = learned.store[index]
        return retained || protected || (prop is ClausePropagator && prop.literals.size <= 1) ||
            learned.stages[index] and 1 == 0
    }
    override fun visited() { learned.capVisits++ }
    override fun retire(index: Int): Boolean {
        val prop = learned.store[index]
        val pinned = if (prepared == index) protected else retainedReason(index)
        if (!mayDrop(index) || pinned || (prop is ClausePropagator && prop.literals.size <= 1) ||
            learned.stages[index] and 1 == 0
        ) {
            return false
        }
        budget.visit(if (prop is LearnedIntegerPropagator) 16L else 8L)
        budget.visit(0L)
        val kind = learnedKind(index)
        learned.retire(index, kind)
        factorAttribution?.retire(prop)
        if (prop is ClausePropagator) forgetDeferredClause(prop.literals)
        if (prop is LearnedIntegerPropagator) checkNotNull(learned.integerRows).unlink(prop)
        learned.store[index] = RetiredLearnedPropagator
        refPayloadStore[baseFactorCount + index] = null
        return true
    }
}
