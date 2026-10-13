package com.eignex.klause.portfolio

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.VerifiedIncumbent
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.lock

internal class PortfolioIncumbents<V>(
    val valueOf: (MinimizeResult.WithSample) -> V?,
    val improves: (V, V) -> Boolean,
    val approximateValue: (V) -> Double,
    val gain: (V, V) -> Double,
    private val upperBound: (V) -> Double = approximateValue,
) {
    val exchange = IncumbentExchange<Sample, V>(improves)

    fun isImproving(value: V): Boolean = exchange.current()?.let { improves(value, it.objective) } ?: true

    // Finite worker cutoffs and public results use doubles; neither projection orders the exchange.
    fun bound(): Double = exchange.current()?.let { upperBound(it.objective) } ?: Double.POSITIVE_INFINITY

    fun projected(): VerifiedIncumbent<Sample, Double>? = exchange.current()?.let {
        VerifiedIncumbent(it.assignment, approximateValue(it.objective), it.version)
    }

    companion object {
        fun exact(objective: LinearObjective): PortfolioIncumbents<BigFraction> = PortfolioIncumbents(
            valueOf = { objective.evaluateExact(it.sample) },
            improves = { candidate, standing -> candidate < standing },
            approximateValue = { it.toDouble() },
            gain = { standing, candidate -> (standing - candidate).toDouble() },
            upperBound = ::roundedUpperBound,
        )

        fun algebraic(
            valueOf: (MinimizeResult.WithSample) -> AlgebraicReal?,
            arithmetic: AlgebraicArithmetic,
            onResource: (PolynomialResource) -> Unit = {},
        ): PortfolioIncumbents<AlgebraicReal> = mappedAlgebraic(valueOf, { it }, arithmetic, onResource)

        fun <V> mappedAlgebraic(
            valueOf: (MinimizeResult.WithSample) -> V?,
            exactValue: (V) -> AlgebraicReal,
            arithmetic: AlgebraicArithmetic,
            onResource: (PolynomialResource) -> Unit = {},
        ): PortfolioIncumbents<V> {
            val lock = Concurrency.Strict.lock()
            fun <T> checked(action: () -> T): T? = try {
                action()
            } catch (_: PolynomialInterrupted) {
                null
            } catch (stop: PolynomialResourceLimit) {
                onResource(stop.resource)
                null
            }
            return PortfolioIncumbents(
                valueOf = { lock.withLock { checked { valueOf(it) } } },
                improves = { candidate, standing -> lock.withLock {
                    checked { arithmetic.compare(exactValue(candidate), exactValue(standing)) }?.let { it < 0 } == true
                } },
                approximateValue = { item ->
                    val value = exactValue(item)
                    value.rational?.toDouble() ?: ((value.lowerBound + value.upperBound) *
                        BigFraction.ofLong(2).reciprocal()).toDouble()
                },
                gain = { standing, candidate ->
                    (exactValue(standing).lowerBound - exactValue(candidate).upperBound).toDouble().coerceAtLeast(0.0)
                },
                upperBound = { roundedUpperBound(exactValue(it).upperBound) },
            )
        }

        fun floating(): PortfolioIncumbents<Double> = PortfolioIncumbents(
            valueOf = { it.objective.takeIf(Double::isFinite) },
            improves = { candidate, standing -> candidate < standing },
            approximateValue = { it },
            gain = { standing, candidate -> standing - candidate },
        )
    }
}

private fun roundedUpperBound(exact: BigFraction): Double {
    val approximate = exact.toDouble()
    return when {
        approximate == Double.NEGATIVE_INFINITY -> -Double.MAX_VALUE
        approximate == Double.POSITIVE_INFINITY -> approximate
        requireNotNull(BigFraction.ofDouble(approximate)) < exact ->
            Double.fromBits(approximate.toBits() + if (approximate >= 0) 1 else -1)
        else -> approximate
    }
}
