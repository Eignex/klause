package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason

internal interface QuadraticPortfolioSearch : ResumableSearch {
    val completion: QuadraticOptimizationResult?
}

internal fun quadraticScalar(
    point: QuadraticIncumbent, sense: ObjectiveSense, stats: SolveStats,
): MinimizeResult.BestFound {
    val value = point.value.rational?.toDouble() ?: ((point.value.lowerBound + point.value.upperBound) *
        BigFraction.ofLong(2).reciprocal()).toDouble()
    return MinimizeResult.BestFound(point.sample, if (sense == ObjectiveSense.MINIMIZE) value else -value,
        TerminationReason.BudgetExhausted, stats)
}
