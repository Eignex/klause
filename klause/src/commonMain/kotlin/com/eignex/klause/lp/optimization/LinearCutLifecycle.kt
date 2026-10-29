package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.CutFamily

internal class LinearCutLifecycle(private val budget: SourceLpBudget) {
    private val counts = LinkedHashMap<String, Long>()
    var families: Set<CutFamily> = emptySet()
    private var ownedFamilies: Set<CutFamily> = emptySet()
    private var recipe: String = "NO_CUT"
    fun snapshot(): Map<String, Long> = counts.toMap()

    fun observe(family: CutFamily, stage: String, count: Long = 1L) {
        val key = "numericalCutLifecycle${family.name}_$stage"
        counts[key] = (counts[key] ?: 0L) + count
    }

    fun shared(stage: String, count: Long) {
        val key = "numericalCutLifecycleRECIPE_${recipe}_$stage"
        counts[key] = (counts[key] ?: 0L) + count
        val participants = if (stage == "OwnerClosed" || stage == "GuidanceInvalidated") ownedFamilies else families
        for (family in participants) observe(family, stage, count)
        when (stage) {
            "OwnerPrepared" -> ownedFamilies = families
            "OwnerReused" -> ownedFamilies = ownedFamilies + families
            "OwnerClosed" -> ownedFamilies = emptySet()
        }
    }

    fun <T> containing(recipe: String, block: () -> T): T {
        this.recipe = recipe
        fun costs() = listOf(budget.measuredPreparationWork, budget.measuredFloatWork,
            budget.measuredContinuationWork, budget.measuredRefinementWork, budget.measuredBoundCertificationWork,
            budget.measuredConflictCertificationWork, budget.reservedAllocation, budget.activeNanos)
        val before = costs()
        val attempted = families
        val owned = ownedFamilies
        try {
            return block()
        } finally {
            val after = costs()
            val stages = listOf("PreparationWork", "FloatWork", "ContinuationWork", "RefinementWork",
                "BoundCertificationWork", "ConflictCertificationWork", "ModeledAllocation", "ActiveNanos")
            for (index in stages.indices) {
                val delta = after[index] - before[index]
                val key = "numericalCutLifecycleRECIPE_${recipe}_${stages[index]}"
                counts[key] = (counts[key] ?: 0L) + delta
                val participants = when (index) {
                    0, 6 -> attempted + families
                    7 -> owned + families
                    else -> families
                }
                for (family in participants) observe(family, stages[index], delta)
            }
        }
    }
}
