package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolynomialProjectionTest {
    private val arithmetic = PolynomialArithmetic()
    private val x = Polynomial.variable(0)
    private val y = Polynomial.variable(1)

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

    @Test
    fun `coordinate factors retain intersections under a linear matrix allowance`() {
        val factor = arithmetic.multiply(arithmetic.power(x, 4), arithmetic.power(y, 6))
        val polynomial = arithmetic.multiply(factor, arithmetic.subtract(y, x))
        val limited = PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 4))

        val projection = PolynomialProjection.compute(listOf(polynomial), 2, limited)

        assertTrue(hasBoundary(projection, BigFraction.ZERO))
        assertTrue(projection.polynomials(1).any {
            arithmetic.substitute(arithmetic.substitute(it, 0, Polynomial.ONE), 1, Polynomial.ONE).isZero
        })
    }

    @Test
    fun `a product equality retains a branch with a nonzero coordinate factor`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= (* x0 x1) 0) (> x0 0)))",
        )

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 0, 33))

        assertEquals(33, result.assignment.realCount)
        assertTrue(AlgebraicArithmetic().sign(result.assignment.realValue(0)) > 0)
        assertEquals(BigFraction.ZERO, result.assignment.realValue(1).rational)
    }

    @Test
    fun `a product equality retains its nonmonomial zero factor`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= (* x0 x1 (+ x0 x1)) 0) (> x0 0) (< x1 0)))",
        )
        val algebraic = AlgebraicArithmetic()

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 0, 33))

        val first = result.assignment.realValue(0)
        val second = result.assignment.realValue(1)
        assertTrue(algebraic.sign(first) > 0)
        assertTrue(algebraic.sign(second) < 0)
        assertEquals(0, algebraic.sign(algebraic.add(first, second)))
    }

    @Test
    fun `equality section rewriting retains whole product premises`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= x0 (* x2 x2)) (= (* x0 x1) 0) (> x1 0)))",
        )

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 0, 33))

        assertEquals(BigFraction.ZERO, result.assignment.realValue(0).rational)
        assertEquals(BigFraction.ZERO, result.assignment.realValue(2).rational)
        assertTrue(AlgebraicArithmetic().sign(result.assignment.realValue(1)) > 0)
        assertTrue(result.stats.equationalSections > 0)
    }

    @Test
    fun `a vanishing product leaves no nonlinear boundary on its required zero section`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= x0 0) (= (* x0 (- (+ (* x1 x1) (* x2 x2)) 1)) 0)))",
        )
        val params = QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0))

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 0, 33, params))

        assertEquals(33, result.assignment.realCount)
        assertEquals(BigFraction.ZERO, result.assignment.realValue(0).rational)
        assertEquals(1L, result.stats.equationalSections)
        assertEquals(0L, result.stats.projectedPolynomials)
    }

    @Test
    fun `factor boundaries preserve even multiplicity in strict inequalities`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (< (* x0 x0 x1) 0) (> x1 0) (not (= x0 0))))",
        )

        val result = solveQfNra(source.formula, 0, 33)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `factor boundaries preserve opposite source coefficient signs`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (> (* x0 x1) 0) (> (* (- 2) x0 x1) 0)))",
        )

        val result = solveQfNra(source.formula, 0, 33)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `interrupted factor preparation publishes no partial family`() {
        val polynomial = arithmetic.multiply(arithmetic.power(x, 3), arithmetic.power(y, 5))
        var completedWork = 0L
        val counter = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { completedWork += it }
        }
        PolynomialProjection.compute(listOf(polynomial), 2, PolynomialArithmetic(counter))

        for (limit in listOf(1L, completedWork / 2, completedWork - 1)) {
            var spent = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = spent >= limit
                override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            }
            assertFailsWith<PolynomialInterrupted> {
                PolynomialProjection.compute(listOf(polynomial), 2, PolynomialArithmetic(stop))
            }
        }
    }

    private fun hasBoundary(projection: PolynomialProjection, at: BigFraction): Boolean =
        projection.polynomials(0).any { polynomial ->
            val specialized = arithmetic.substitute(polynomial, 0, Polynomial.constant(at))
            specialized.coefficient(Monomial.ONE).isZero
        }
}
