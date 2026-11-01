package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.solver.search.LearnedKind

internal fun PropagationState.validateSelectedLearnedRetirement(index: Int): LearnedRetirementStatus? {
    nativeEngine?.let {
        if (index !in 0 until it.count || it.retiredOf(index)) return LearnedRetirementStatus.StaleSelection
        return if (it.permanentOf(index)) LearnedRetirementStatus.Unsupported else null
    }
    if (index !in 0 until learned.size || learned.retiredOf(index)) return LearnedRetirementStatus.StaleSelection
    return if (incremental || learned.permanent[index] == 1 || learned.store[index] !is ClausePropagator ||
        learned.stages[index] and 16 == 0
    ) LearnedRetirementStatus.Unsupported else null
}

internal fun PropagationState.retireSelectedLearnedClause(
    index: Int,
    budget: ReclamationPreparation,
): LearnedRetirementResult {
    nativeEngine?.let { return it.retireSelectedLearnedClause(index, budget) }
    if (incremental || pendingFiniteMutation != null || rootFixpointPending || runCancelled) {
        return LearnedRetirementResult(LearnedRetirementStatus.Deferred)
    }
    if (index !in 0 until learned.size) return LearnedRetirementResult(LearnedRetirementStatus.AlreadyGone,
        payloadReleased = true, physicalReleased = true)
    if (learned.retiredOf(index)) return LearnedRetirementResult(LearnedRetirementStatus.AlreadyGone,
        payloadReleased = true)
    budget.visit(8L)
    val clause = learned.store[index] as? ClausePropagator
        ?: return LearnedRetirementResult(LearnedRetirementStatus.Unsupported)
    if (learned.stages[index] and 16 == 0) return LearnedRetirementResult(LearnedRetirementStatus.Unsupported)
    if (learned.permanent[index] == 1 || clause.literals.size <= 1 || learned.boolReasons[index] > 0 ||
        learned.stages[index] and 4 != 0 || learned.stages[index] and 1 == 0
    ) return LearnedRetirementResult(LearnedRetirementStatus.ProtectedFloor)
    budget.visit(16L)
    budget.visit(0L)
    learned.retire(index, LearnedKind.Clause)
    learned.capRanking.invalidateRetiredHandles()
    factorAttribution?.retire(clause)
    forgetDeferredClause(clause.literals)
    learned.store[index] = RetiredLearnedPropagator
    refPayloadStore[baseFactorCount + index] = null
    return LearnedRetirementResult(LearnedRetirementStatus.Retired, payloadReleased = true)
}
