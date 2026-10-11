package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PolynomialProjectionTest {
    private val arithmetic = PolynomialArithmetic()
    private val x = Polynomial.variable(0)
    private val y = Polynomial.variable(1)

    @Test
    fun `projection separates a change in the number of real sections`() {
        val polynomial = arithmetic.subtract(arithmetic.power(y, 2), x)

        val projection = PolynomialProjection.compute(listOf(polynomial), 2, arithmetic)

        assertTrue(hasBoundary(projection, BigFraction.ZERO))
    }

    @Test
    fun `projection separates coincident sections of different polynomials`() {
        val polynomials = listOf(arithmetic.subtract(y, x), arithmetic.add(y, x))

        val projection = PolynomialProjection.compute(polynomials, 2, arithmetic)

        assertTrue(hasBoundary(projection, BigFraction.ZERO))
    }

    @Test
    fun `projection separates distinct root changes of a repeated polynomial`() {
        val quadratic = arithmetic.add(arithmetic.add(arithmetic.power(y, 2), y), x)
        val polynomial = arithmetic.power(quadratic, 2)

        val projection = PolynomialProjection.compute(listOf(polynomial), 2, arithmetic)

        assertTrue(hasBoundary(projection, BigFraction.ofLong(4).reciprocal()))
    }

    @Test
    fun `projection retains the boundary where a leading coefficient vanishes`() {
        val polynomial = arithmetic.subtract(
            arithmetic.add(arithmetic.multiply(x, arithmetic.power(y, 2)), y),
            Polynomial.ONE,
        )

        val projection = PolynomialProjection.compute(listOf(polynomial), 2, arithmetic)

        assertTrue(hasBoundary(projection, BigFraction.ZERO))
    }

    @Test
    fun `projection preserves lower variable boundaries through an absent dimension`() {
        val polynomials = listOf(arithmetic.subtract(x, Polynomial.ONE), y)

        val projection = PolynomialProjection.compute(polynomials, 3, arithmetic)

        assertTrue(hasBoundary(projection, BigFraction.ONE))
    }

    @Test
    fun `projection separates equal degree polynomials sharing specialized roots`() {
        val base = arithmetic.subtract(arithmetic.power(y, 2), Polynomial.ONE)
        val mixed = arithmetic.multiply(x, y)
        val polynomials = listOf(arithmetic.add(base, mixed), arithmetic.subtract(base, mixed))

        val projection = PolynomialProjection.compute(polynomials, 2, arithmetic)

        assertTrue(hasBoundary(projection, BigFraction.ZERO))
    }

    @Test
    fun `an empty family retains every declared real dimension`() {
        val projection = PolynomialProjection.compute(emptyList(), 3, arithmetic)

        assertEquals(3, projection.variableCount)
        assertTrue((0 until projection.variableCount).all { projection.polynomials(it).isEmpty() })
    }

    @Test
    fun `zero and rational constants introduce no section boundaries`() {
        val projection = PolynomialProjection.compute(listOf(Polynomial.ZERO, Polynomial.ONE), 2, arithmetic)

        assertEquals(0L, projection.polynomialCount)
    }

    @Test
    fun `producer and returned list mutations do not change projection boundaries`() {
        val producer = mutableListOf(arithmetic.subtract(arithmetic.power(y, 2), x))
        val projection = PolynomialProjection.compute(producer, 2, arithmetic)

        producer.clear()
        runCatching { (projection.polynomials(0) as MutableList<Polynomial>).clear() }

        assertTrue(hasBoundary(projection, BigFraction.ZERO))
    }

    @Test
    fun `interrupted projection publishes no partial family`() {
        var polls = 0
        val interrupted = PolynomialArithmetic(Cancellation { ++polls > 15 })
        val polynomial = arithmetic.subtract(arithmetic.power(y, 3), x)

        assertFailsWith<PolynomialInterrupted> {
            PolynomialProjection.compute(listOf(polynomial), 2, interrupted)
        }
    }

    @Test
    fun `resource limited projection publishes no partial family`() {
        val limited = PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0))
        val polynomial = arithmetic.subtract(arithmetic.power(y, 2), x)

        assertFailsWith<PolynomialResourceLimit> {
            PolynomialProjection.compute(listOf(polynomial), 2, limited)
        }
    }

    @Test
    fun `projection rejects variables outside the declared coordinate space`() {
        assertFailsWith<IllegalArgumentException> {
            PolynomialProjection.compute(listOf(y), 1, arithmetic)
        }
    }

    private fun hasBoundary(projection: PolynomialProjection, at: BigFraction): Boolean =
        projection.polynomials(0).any { polynomial ->
            val specialized = arithmetic.substitute(polynomial, 0, Polynomial.constant(at))
            specialized.coefficient(Monomial.ONE).isZero
        }
}
