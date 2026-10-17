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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolynomialProjectionTest {
    private val arithmetic = PolynomialArithmetic()
    private val x = Polynomial.variable(0)
    private val y = Polynomial.variable(1)

    @Test
    fun `nonzero premises must belong to the input family`() {
        assertFailsWith<IllegalArgumentException> {
            PolynomialProjection.compute(listOf(x), 2, arithmetic, nonzeroPolynomials = listOf(y))
        }
    }

    @Test
    fun `nonzero constant resultants refute incompatible required equations`() {
        val first = arithmetic.subtract(arithmetic.add(arithmetic.power(x, 2), arithmetic.power(y, 2)),
            Polynomial.ONE)
        val second = arithmetic.add(first, Polynomial.ONE)

        val projection = PolynomialProjection.compute(listOf(first, second), 2,
            arithmetic, listOf(first, second))

        assertTrue(projection.inconsistentEquations)
    }

    @Test
    fun `a required equation avoids unrelated boundary pair matrices`() {
        val equation = arithmetic.subtract(arithmetic.add(arithmetic.power(x, 2), arithmetic.power(y, 2)),
            Polynomial.ONE)
        val first = arithmetic.add(arithmetic.power(y, 3), arithmetic.power(x, 3))
        val second = arithmetic.subtract(arithmetic.power(y, 3), arithmetic.power(x, 3))
        val limited = PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 25))

        val projection = PolynomialProjection.compute(listOf(equation, first, second), 2, limited,
            listOf(equation), intArrayOf(0, 1))

        assertEquals(equation, projection.rootSection(1)?.polynomial)
        assertTrue(projection.polynomials(0).isNotEmpty())
    }

    @Test
    fun `constant leading coefficients exclude reductum only root boundaries`() {
        val polynomial = arithmetic.multiply(arithmetic.add(y, x),
            arithmetic.add(arithmetic.power(y, 2), Polynomial.ONE))

        val projection = PolynomialProjection.compute(listOf(polynomial), 2, arithmetic)

        assertFalse(hasBoundary(projection, BigFraction.ofLong(2).reciprocal()))
    }

    @Test
    fun `a supplied coordinate order retains its projected root change boundary`() {
        val polynomial = arithmetic.subtract(arithmetic.power(x, 2), y)

        val projection = PolynomialProjection.compute(listOf(polynomial), 2, arithmetic,
            preferredOrder = intArrayOf(1, 0))

        assertEquals(1, projection.variable(0))
        assertEquals(0, projection.variable(1))
        assertTrue(projection.polynomials(0).any {
            arithmetic.substitute(it, 1, Polynomial.ZERO).isZero
        })
    }

    @Test
    fun `coordinate preferences must be complete permutations`() {
        for (order in listOf(intArrayOf(0), intArrayOf(0, 0), intArrayOf(0, 2))) {
            assertFailsWith<IllegalArgumentException> {
                PolynomialProjection.compute(listOf(x, y), 2, arithmetic, preferredOrder = order)
            }
        }
    }

    @Test
    fun `projection should separate a change in the number of real sections`() {
        val polynomial = arithmetic.subtract(arithmetic.power(y, 2), x)

        val projection = PolynomialProjection.compute(listOf(polynomial), 2, arithmetic)

        assertTrue(hasBoundary(projection, BigFraction.ZERO))
    }

    @Test
    fun `projection should separate coincident sections of different polynomials`() {
        val polynomials = listOf(arithmetic.subtract(y, x), arithmetic.add(y, x))

        val projection = PolynomialProjection.compute(polynomials, 2, arithmetic)

        assertTrue(hasBoundary(projection, BigFraction.ZERO))
    }

    @Test
    fun `projection should separate distinct root changes of a repeated polynomial`() {
        val quadratic = arithmetic.add(arithmetic.add(arithmetic.power(y, 2), y), x)
        val polynomial = arithmetic.power(quadratic, 2)

        val projection = PolynomialProjection.compute(listOf(polynomial), 2, arithmetic)

        assertTrue(hasBoundary(projection, BigFraction.ofLong(4).reciprocal()))
    }

    @Test
    fun `projection should retain the boundary where a leading coefficient vanishes`() {
        val polynomial = arithmetic.subtract(
            arithmetic.add(arithmetic.multiply(x, arithmetic.power(y, 2)), y),
            Polynomial.ONE,
        )

        val projection = PolynomialProjection.compute(listOf(polynomial), 2, arithmetic)

        assertTrue(hasBoundary(projection, BigFraction.ZERO))
    }

    @Test
    fun `projection should preserve lower variable boundaries through an absent dimension`() {
        val polynomials = listOf(arithmetic.subtract(x, Polynomial.ONE), y)

        val projection = PolynomialProjection.compute(polynomials, 3, arithmetic)

        assertTrue(hasBoundary(projection, BigFraction.ONE))
    }

    @Test
    fun `projection should separate equal degree polynomials sharing specialized roots`() {
        val base = arithmetic.subtract(arithmetic.power(y, 2), Polynomial.ONE)
        val mixed = arithmetic.multiply(x, y)
        val polynomials = listOf(arithmetic.add(base, mixed), arithmetic.subtract(base, mixed))

        val projection = PolynomialProjection.compute(polynomials, 2, arithmetic)

        assertTrue(hasBoundary(projection, BigFraction.ZERO))
    }

    @Test
    fun `an empty family should retain every declared real dimension`() {
        val projection = PolynomialProjection.compute(emptyList(), 3, arithmetic)

        assertEquals(3, projection.variableCount)
        assertTrue((0 until projection.variableCount).all { projection.polynomials(it).isEmpty() })
    }

    @Test
    fun `zero and rational constants should introduce no section boundaries`() {
        val projection = PolynomialProjection.compute(listOf(Polynomial.ZERO, Polynomial.ONE), 2, arithmetic)

        assertEquals(0L, projection.polynomialCount)
    }

    @Test
    fun `producer and returned list mutations should not change projection boundaries`() {
        val producer = mutableListOf(arithmetic.subtract(arithmetic.power(y, 2), x))
        val projection = PolynomialProjection.compute(producer, 2, arithmetic)

        producer.clear()
        runCatching { (projection.polynomials(0) as MutableList<Polynomial>).clear() }

        assertTrue(hasBoundary(projection, BigFraction.ZERO))
    }

    @Test
    fun `interrupted projection should publish no partial family`() {
        var polls = 0
        val interrupted = PolynomialArithmetic(Cancellation { ++polls > 15 })
        val polynomial = arithmetic.subtract(arithmetic.power(y, 3), x)

        assertFailsWith<PolynomialInterrupted> {
            PolynomialProjection.compute(listOf(polynomial), 2, interrupted)
        }
    }

    @Test
    fun `resource limited projection should publish no partial family`() {
        val limited = PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0))
        val polynomial = arithmetic.subtract(arithmetic.power(y, 2), x)

        assertFailsWith<PolynomialResourceLimit> {
            PolynomialProjection.compute(listOf(polynomial), 2, limited)
        }
    }

    @Test
    fun `projection should reject variables outside the declared coordinate space`() {
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
