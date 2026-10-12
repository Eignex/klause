package com.eignex.klause.portfolio

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Publication
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PortfolioIncumbentsTest {
    @Test
    fun `algebraic sharing orders exact rational objectives with identical floating scores`() {
        val arithmetic = AlgebraicArithmetic()
        val sourceValue = { result: MinimizeResult.WithSample -> requireNotNull(result.sample.algebraicReals).single() }
        val incumbents = PortfolioIncumbents.algebraic(sourceValue, arithmetic)
        val first = arithmetic.rational(BigFraction.of(parseBigInt("100000000000000000001"), bigIntOf(1)))
        val better = arithmetic.rational(BigFraction.of(parseBigInt("100000000000000000000"), bigIntOf(1)))
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(first.rational!!.toDouble()),
            algebraicReals = listOf(first))
        assertIs<Publication.Installed<Sample, AlgebraicReal>>(incumbents.exchange.offer(sample, first))
        val proposed = MinimizeResult.BestFound(sample.copy(
            reals = doubleArrayOf(better.rational!!.toDouble()), algebraicReals = listOf(better),
        ), first.rational!!.toDouble(), TerminationReason.BudgetExhausted)

        val candidate = requireNotNull(incumbents.valueOf(proposed))
        assertTrue(incumbents.isImproving(candidate))
        assertIs<Publication.Installed<Sample, AlgebraicReal>>(incumbents.exchange.offer(proposed.sample, candidate))

        assertEquals(better, incumbents.exchange.current()?.objective)
        assertEquals(sample.approximateRealValue(0), proposed.sample.approximateRealValue(0))
    }

    @Test
    fun `algebraic sharing compares witnesses whose isolating intervals overlap`() {
        val arithmetic = AlgebraicArithmetic()
        val larger = arithmetic.roots(RationalPolynomial.of(-3, 0, 1)).last()
        val smaller = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.5), algebraicReals = listOf(larger))
        val incumbents = PortfolioIncumbents.algebraic({ it.sample.algebraicReals?.single() }, arithmetic)
        assertIs<Publication.Installed<Sample, AlgebraicReal>>(incumbents.exchange.offer(sample, larger))

        assertTrue(incumbents.isImproving(smaller))
        assertIs<Publication.Installed<Sample, AlgebraicReal>>(incumbents.exchange.offer(sample.copy(
            reals = doubleArrayOf(1.5), algebraicReals = listOf(smaller),
        ), smaller))

        assertEquals(smaller, incumbents.exchange.current()?.objective)
        assertTrue(requireNotNull(BigFraction.ofDouble(incumbents.bound())) >= smaller.upperBound)
    }

    @Test
    fun `rounded cutoff cannot fall below a negative exact rational incumbent`() {
        val arithmetic = AlgebraicArithmetic()
        val value: AlgebraicReal = arithmetic.rational(BigFraction.of(bigIntOf(-1), bigIntOf(3)))
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(-1.0 / 3.0), algebraicReals = listOf(value))
        val incumbents = PortfolioIncumbents.algebraic({ value }, arithmetic)

        assertIs<Publication.Installed<Sample, AlgebraicReal>>(incumbents.exchange.offer(sample, value))

        assertTrue(requireNotNull(BigFraction.ofDouble(incumbents.bound())) >= value.rational!!)
    }

    @Test
    fun `algebraic telemetry retains a finite midpoint across wide isolating bounds`() {
        val arithmetic = AlgebraicArithmetic()
        val magnitude = BigFraction.of(parseBigInt("1" + "0".repeat(400)), bigIntOf(1))
        val value = arithmetic.roots(RationalPolynomial(listOf(
            magnitude.negated(), BigFraction.ZERO, BigFraction.ZERO, BigFraction.ONE,
        ))).single()
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), algebraicReals = listOf(value))
        val incumbents = PortfolioIncumbents.algebraic({ value }, arithmetic)

        assertIs<Publication.Installed<Sample, AlgebraicReal>>(incumbents.exchange.offer(sample, value))

        assertEquals(value, incumbents.exchange.current()?.objective)
        assertTrue(requireNotNull(incumbents.projected()).objective.isFinite())
    }

}
