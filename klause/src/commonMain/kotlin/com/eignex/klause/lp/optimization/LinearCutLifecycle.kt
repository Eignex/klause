package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.CutFamily

internal class LinearCutLifecycle(private val budget: SourceLpBudget) {
    private val counts = LinkedHashMap<String, Long>()
    var families: Set<CutFamily> = emptySet()
    fun snapshot(): Map<String, Long> = counts.toMap()

    fun observe(family: CutFamily, stage: String, count: Long = 1L) {
        val key = "numericalCutLifecycle${family.name}_$stage"
        counts[key] = (counts[key] ?: 0L) + count
    }

    fun <T> containing(block: () -> T): T {
        fun costs() = listOf(budget.measuredPreparationWork, budget.measuredFloatWork,
            budget.measuredContinuationWork, budget.measuredRefinementWork, budget.measuredBoundCertificationWork,
            budget.measuredConflictCertificationWork, budget.reservedAllocation, budget.activeNanos)
        val before = costs()
        val attempted = families
        try {
            return block()
        } finally {
            val after = costs()
            val stages = listOf("PreparationWork", "FloatWork", "ContinuationWork", "RefinementWork",
                "BoundCertificationWork", "ConflictCertificationWork", "ModeledAllocation", "ActiveNanos")
            for (family in attempted + families) for (index in stages.indices) {
                observe(family, stages[index], after[index] - before[index])
            }
        }
    }
}
