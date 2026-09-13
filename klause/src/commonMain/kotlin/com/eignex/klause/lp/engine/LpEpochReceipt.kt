package com.eignex.klause.lp.engine

import kotlin.time.Duration

internal data class LpEpochReceipt(
    val authority: ExactLpModel,
    val rows: LpScopedRows,
    val continuation: LpEpochBudget?,
    val refinement: LpRefinementBudget,
)

internal data class LpRefinementBudget(
    val work: Long,
    val allocation: Long,
    val pivots: Int,
    val elapsed: Duration,
    val attemptedCurrent: Boolean,
    val lastLimits: LpRefinementLimits?,
) {
    init {
        require(work >= 0L && allocation >= 0L && pivots >= 0 && elapsed >= Duration.ZERO)
        require(!attemptedCurrent || lastLimits != null)
    }
}

internal fun LpRefinementCache.epochBudget(current: LpExactState): LpRefinementBudget = LpRefinementBudget(
    work,
    allocation,
    pivots,
    elapsed,
    attempted === current,
    lastLimits,
)

internal fun LpRefinementCache.pristineForEpoch(): Boolean = work == 0L && allocation == 0L && pivots == 0 &&
    elapsed == Duration.ZERO && attempted == null && lastLimits == null

internal fun LpRefinementCache.restoreEpochBudget(budget: LpRefinementBudget, current: LpExactState) {
    work = budget.work
    allocation = budget.allocation
    pivots = budget.pivots
    elapsed = budget.elapsed
    attempted = if (budget.attemptedCurrent) current else null
    lastLimits = budget.lastLimits
}
