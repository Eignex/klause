package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialEquationalProjectionTest {
    private val arithmetic = PolynomialArithmetic()
    private val x = Polynomial.variable(0)
    private val y = Polynomial.variable(1)
    private val z = Polynomial.variable(2)

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
    fun `guarded and negated equations supply no mandatory zeroes`() {
        for (assertion in listOf(
            "(or (= x y) (> x y))",
            "(not (= x y))",
            "(=> a (= x y))",
            "(not (and (= x y) (< x 0)))",
        )) {
            val source = SmtLibPolynomial.parse(
                "(declare-const a Bool) (declare-const x Real) (declare-const y Real) (assert $assertion)",
            )
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            assertTrue(formula.requiredZeroPolynomials(arithmetic).isEmpty(), assertion)
        }
    }

    @Test
    fun `a mandatory equality remains available beside guarded alternatives`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real) (declare-const z Real)
            (assert (and (= z (+ x y)) (or (= x y) (= x (+ y 1)))))
            """.trimIndent(),
        )
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val equations = formula.requiredZeroPolynomials(arithmetic)

        assertEquals(1, equations.size)
        assertEquals(setOf(0, 1, 2), equations.single().variables.toSet())
    }

    @Test
    fun `complete CAD equality sections preserve every declared source coordinate`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= x0 (* x1 x1)) (= (* x1 x1) 2) (> x0 0)))",
        )

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 0, 33))

        val point = result.assignment
        val algebraic = AlgebraicArithmetic(arithmetic)
        assertEquals(33, point.realCount)
        assertTrue(result.stats.equationalSections > 0)
        assertEquals(0, algebraic.compare(point.realValue(0), algebraic.rational(BigFraction.ofLong(2))))
        assertEquals(0, algebraic.compare(
            algebraic.multiply(point.realValue(1), point.realValue(1)), point.realValue(0),
        ))
        for (id in 2..32) assertEquals(BigFraction.ZERO, point.realValue(id).rational)
    }

    @Test
    fun `Boolean alternatives remain obligations after equality section projection`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const a Bool)
            (assert (and (= x0 (* x1 x1))
              (or (and a (> x0 4) (< x1 (- 2))) (and (not a) (= x1 0) (= x0 0)))))
            """.trimIndent(),
        )
        val algebraic = AlgebraicArithmetic(arithmetic)

        for (pin in listOf(true, false)) {
            val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 1, 33,
                condition = PolynomialCondition(bools = mapOf(0 to pin))))

            val point = result.assignment
            assertEquals(pin, point.boolValue(0))
            assertEquals(0, algebraic.compare(
                point.realValue(0), algebraic.multiply(point.realValue(1), point.realValue(1)),
            ))
            if (pin) assertTrue(algebraic.compare(point.realValue(1), algebraic.rational(BigFraction.ofLong(-2))) < 0)
            else assertEquals(BigFraction.ZERO, point.realValue(1).rational)
        }
    }

    @Test
    fun `fixed source coordinates survive a reordered lifting prefix`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse("$declarations (assert (and (= x0 (* x1 x1)) (= (* x1 x1) 2)))")
        val pin = mapOf(2 to BigFraction.ofLong(7))

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 0, 33,
            condition = PolynomialCondition(lower = pin, upper = pin)))

        assertEquals(BigFraction.ofLong(7), result.assignment.realValue(2).rational)
        assertTrue(result.stats.equationalSections > 0)
    }

    @Test
    fun `a zero equality section cannot admit a strict positive source relation`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse("$declarations (assert (and (= x0 0) (> x0 0)))")

        val result = assertIs<QfNraResult.Unsatisfiable>(solveQfNra(source.formula, 0, 33))

        assertEquals(1, result.stats.equationalSections)
        assertTrue(result.stats.liftedCells > 0)
    }

    @Test
    fun `a negative rational coefficient retains its exact equality section`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= (+ (* (- 2) x0) (* x1 x1)) 0) (= (* x1 x1) 2)))",
        )

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 0, 33))

        assertEquals(0, AlgebraicArithmetic(arithmetic).compare(result.assignment.realValue(0),
            AlgebraicArithmetic(arithmetic).rational(BigFraction.ONE)))
        assertEquals(1, result.stats.equationalSections)
    }

    @Test
    fun `an unproved nonzero coefficient retains a feasible singular source branch`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= (* x0 x1) 0) (= (* x0 x0) 0) (> x1 0)))",
        )

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 0, 33))

        assertEquals(BigFraction.ZERO, result.assignment.realValue(0).rational)
        assertTrue(AlgebraicArithmetic(arithmetic).sign(result.assignment.realValue(1)) > 0)
        assertEquals(0, result.stats.equationalSections)
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

    @Test
    fun `containing work exhaustion withholds a completed equality conflict`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= x0 (+ x1 x2)) (= x0 (+ x1 x2 1))))",
        )
        val completed = assertIs<QfNraResult.Unsatisfiable>(solveQfNra(source.formula, 0, 33))

        val result = solveQfNra(source.formula, 0, 33,
            QfNraParams(maximumWork = completed.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }
}
