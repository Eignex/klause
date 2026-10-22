package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialEquationalProjectionTest {
    private val arithmetic = PolynomialArithmetic()
    private val x = Polynomial.variable(0)
    private val y = Polynomial.variable(1)
    private val z = Polynomial.variable(2)

    @Test
    fun `independent sections retain their shared free coordinate`() {
        val squared = arithmetic.power(y, 2)
        val shifted = arithmetic.add(squared, y)
        val equations = listOf(arithmetic.subtract(x, squared), arithmetic.subtract(z, shifted))

        val sections = independentEquationalSections(equations, equations, arithmetic)

        assertEquals(listOf(2, 0), sections.map { it.variable })
        assertEquals(listOf(shifted, squared), sections.map { it.replacement })
    }

    @Test
    fun `dependent sections retain their substitution order`() {
        val first = arithmetic.subtract(x, arithmetic.power(y, 2))
        for ((second, variable) in listOf(
            arithmetic.subtract(y, arithmetic.power(z, 2)) to 0,
            arithmetic.subtract(y, arithmetic.power(x, 2)) to 1,
        )) {
            val equations = listOf(first, second)

            val sections = independentEquationalSections(equations, equations, arithmetic)

            assertEquals(1, sections.size)
            assertEquals(variable, sections.single().variable)
        }
    }

    @Test
    fun `a required equation can select a lower source coordinate as the final section`() {
        val equation = arithmetic.subtract(x, arithmetic.power(y, 2))
        val boundary = arithmetic.subtract(arithmetic.power(y, 2), Polynomial.constant(BigFraction.ofLong(2)))
        val limited = PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0))

        val projection = PolynomialProjection.compute(listOf(equation, boundary), 2, limited, listOf(equation))

        assertEquals(1, projection.variable(0))
        assertEquals(0, projection.variable(1))
        assertEquals(arithmetic.power(y, 2), projection.section(1))
        assertEquals(listOf(boundary), projection.polynomials(0))
    }

    @Test
    fun `nested equality sections retain their source coordinate dependencies`() {
        val first = arithmetic.subtract(x, arithmetic.power(y, 2))
        val second = arithmetic.subtract(y, arithmetic.power(z, 2))
        val boundary = arithmetic.subtract(arithmetic.power(z, 2), Polynomial.constant(BigFraction.ofLong(2)))

        val projection = PolynomialProjection.compute(listOf(first, second, boundary), 3,
            arithmetic, listOf(first, second))

        assertEquals(listOf(2, 1, 0), (0..2).map(projection::variable))
        assertEquals(arithmetic.power(y, 2), projection.section(2))
        assertEquals(arithmetic.power(z, 2), projection.section(1))
        assertEquals(2, projection.equationalSections)
    }

    @Test
    fun `incompatible required equations produce a completed empty variety`() {
        val first = arithmetic.subtract(x, y)
        val second = arithmetic.subtract(first, Polynomial.ONE)

        val projection = PolynomialProjection.compute(listOf(first, second), 2,
            arithmetic, listOf(first, second))

        assertTrue(projection.inconsistentEquations)
        assertEquals(1, projection.equationalSections)
        assertFailsWith<IllegalStateException> { projection.polynomials(0) }
    }

    @Test
    fun `a required zero must belong to the original projection input`() {
        assertFailsWith<IllegalArgumentException> {
            PolynomialProjection.compute(listOf(x), 2, arithmetic, listOf(y))
        }
    }

    @Test
    fun `a variable coefficient cannot define a total equality section`() {
        val equation = arithmetic.subtract(arithmetic.multiply(x, y), Polynomial.ONE)

        val projection = PolynomialProjection.compute(listOf(equation), 2, arithmetic, listOf(equation))

        assertEquals(0, projection.equationalSections)
        assertNull(projection.section(1))
        assertEquals(listOf(0, 1), (0..1).map(projection::variable))
    }

    @Test
    fun `nonlinear coordinate equations retain ordinary root change boundaries`() {
        val equation = arithmetic.subtract(arithmetic.add(arithmetic.power(x, 2), arithmetic.power(y, 2)),
            Polynomial.ONE)

        val projection = PolynomialProjection.compute(listOf(equation), 2, arithmetic, listOf(equation))

        assertEquals(0, projection.equationalSections)
        assertTrue(projection.polynomials(0).any {
            arithmetic.substitute(it, 0, Polynomial.ONE).isZero
        })
    }

    @Test
    fun `interruption publishes no incomplete equality projection`() {
        val first = arithmetic.subtract(x, arithmetic.power(y, 2))
        val second = arithmetic.subtract(y, arithmetic.power(z, 2))
        val boundary = arithmetic.subtract(arithmetic.power(z, 2), Polynomial.constant(BigFraction.ofLong(2)))
        val family = listOf(first, second, boundary)
        val equations = listOf(first, second)
        var completedWork = 0L
        val counter = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { completedWork += it }
        }
        val completed = PolynomialProjection.compute(family, 3, PolynomialArithmetic(counter), equations)
        assertFalse(completed.inconsistentEquations)

        for (limit in listOf(1L, completedWork / 2, completedWork - 1)) {
            var spent = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = spent >= limit
                override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            }
            assertFailsWith<PolynomialInterrupted> {
                PolynomialProjection.compute(family, 3, PolynomialArithmetic(stop), equations)
            }
        }
    }
}
