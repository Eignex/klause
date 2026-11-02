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
        if (learned.store[index, GeneralLearnedRows.STAGES] and 16 != 0) {
            return learned.store[index, GeneralLearnedRows.BOOL_REASONS] > 0 ||
                learned.store[index, GeneralLearnedRows.STAGES] and 4 != 0
        }
        if (!budget.supports(learned.size.toLong(), learned.size.toLong())) {
            return hasLearnedReason(baseFactorCount + index, budget)
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
    override fun lbd(index: Int): Int = learned.store[index, GeneralLearnedRows.LBD]
    override fun prepare(index: Int): Boolean {
        budget.visit(CAP_ROW_POLICY_WORK)
        protected = retainedReason(index)
        prepared = index
        return true
    }
    override fun keep(index: Int): Boolean {
        val retained = retain(index)
        val prop = learned.store[index]
        return retained || protected || (prop is ClausePropagator && prop.literals.size <= 1) ||
            learned.store[index, GeneralLearnedRows.STAGES] and 1 == 0
    }
    override fun visited() { learned.capVisits++ }
    override fun retire(index: Int): Boolean {
        val prop = learned.store[index]
        val pinned = if (prepared == index) protected else retainedReason(index)
        if (!mayDrop(index) || pinned || (prop is ClausePropagator && prop.literals.size <= 1) ||
            learned.store[index, GeneralLearnedRows.STAGES] and 1 == 0
        ) {
            return false
        }
        budget.visit(if (prop is LearnedIntegerPropagator) 40L else 32L)
        budget.visit(0L)
        val kind = learnedKind(index)
        learned.retire(index, kind)
        factorAttribution?.retire(prop)
        if (prop is ClausePropagator) forgetDeferredClause(prop.literals)
        if (prop is LearnedIntegerPropagator) {
            val rows = checkNotNull(learned.integerRows)
            rows.unlink(prop)
            if (rows.isEmpty()) learned.integerRows = null
        }
        learned.store[index] = RetiredLearnedPropagator
        learned.store.setPayload(index, null)
        return true
    }
}

private fun PropagationState.hasLearnedReason(factor: Int, budget: ReclamationPreparation): Boolean {
    if (containsLearnedReason(boolReason, factor, budget) || containsLearnedReason(intMinReason, factor, budget) ||
        containsLearnedReason(intMaxReason, factor, budget)
    ) return true
    for (index in 0 until undo.size) {
        budget.visit()
        if (undo.tag[index] == 1) {
            budget.visit(2L)
            if (undo.minReason[index] == factor || undo.maxReason[index] == factor) return true
        }
    }
    budget.visit(2L)
    val stage = learned.store[factor - baseFactorCount, GeneralLearnedRows.STAGES]
    return stage and 4 != 0 && stage and 2 == 0
}

private fun containsLearnedReason(reasons: IntArray, factor: Int, budget: ReclamationPreparation): Boolean {
    for (reason in reasons) {
        budget.visit()
        if (reason == factor) return true
    }
    return false
}
