package com.eignex.klause.portfolio

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.VerifiedIncumbent
import com.eignex.klause.solver.result.MinimizeResult

internal class PortfolioIncumbents<V>(
    val valueOf: (MinimizeResult.WithSample) -> V?,
    val improves: (V, V) -> Boolean,
    val approximateValue: (V) -> Double,
    val gain: (V, V) -> Double,
) {
    val exchange = IncumbentExchange<Sample, V>(improves)

    fun isImproving(value: V): Boolean = exchange.current()?.let { improves(value, it.objective) } ?: true

    // Finite worker cutoffs and public results use doubles; neither projection orders the exchange.
    fun bound(): Double = exchange.current()?.let { approximateValue(it.objective) } ?: Double.POSITIVE_INFINITY

    fun projected(): VerifiedIncumbent<Sample, Double>? = exchange.current()?.let {
        VerifiedIncumbent(it.assignment, approximateValue(it.objective), it.version)
    }

    companion object {
        fun floating(): PortfolioIncumbents<Double> = PortfolioIncumbents(
            valueOf = { it.objective.takeIf(Double::isFinite) },
            improves = { candidate, standing -> candidate < standing },
            approximateValue = { it },
            gain = { standing, candidate -> standing - candidate },
        )
    }
}
