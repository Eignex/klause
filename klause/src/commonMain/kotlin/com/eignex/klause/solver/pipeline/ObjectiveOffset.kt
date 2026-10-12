package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.AttributedImprovement
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation

internal fun LinearObjective.searchObjective(): LinearObjective =
    if (constant == 0L || realCoefficientValues.any { it != 0.0 }) this
    else LinearObjective(boolWeightValues, intCoefficientValues, realCoefficients = realCoefficientValues)

internal fun MinimizeResult.sourceValue(objective: LinearObjective): MinimizeResult {
    if (objective.constant == 0L || objective.realCoefficientValues.any { it != 0.0 }) return this
    fun value(result: MinimizeResult.WithSample) = objective.evaluateExact(result.sample).toDouble()
    return when (this) {
        is MinimizeResult.Optimal -> copy(objective = value(this))
        is MinimizeResult.BestFound -> copy(objective = value(this))
        is MinimizeResult.Unbounded -> copy(objective = value(this))
        else -> this
    }
}

internal fun PortfolioExecutor.sourceValues(objective: LinearObjective): PortfolioExecutor {
    val delegate = this
    return object : PortfolioExecutor by delegate {
        override fun minimize(cancellation: Cancellation,
            onImprovement: ((AttributedImprovement) -> Unit)?): MinimizeResult = delegate.minimize(cancellation,
            onImprovement?.let { sink -> { improvement ->
                sink(improvement.copy(result = improvement.result.sourceValue(objective)))
            } }).sourceValue(objective)
    }
}
