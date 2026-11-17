package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolynomialBooleanEqualityRegionsTest {
    @Test
    fun `graph substitutions expose strict nonzero factors within the matrix allowance`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        for ((predicate, sign) in listOf(
            "(distinct x2 0)" to 0, "(> x2 0)" to 1, "(< x2 0)" to -1,
            "(not (= x2 0))" to 0, "(not (<= x2 0))" to 1, "(not (>= x2 0))" to -1,
        )) {
            val source = SmtLibPolynomial.parse(
                """
                $declarations
                (assert (and (= x2 (* x0 x1)) $predicate (= (* x0 x3 x3) 1)
                             (distinct (+ (* x3 x3 x3) (* x0 x0 x0)) 0)
                             (distinct (- (* x3 x3 x3) (* x0 x0 x0)) 0)))
                """.trimIndent(),
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount,
                QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 25)))

            val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
            val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
            val x = witness.realValue(0)
            val y = witness.realValue(1)
            val z = witness.realValue(2)
            val u = witness.realValue(3)
            val xx = algebraic.multiply(x, x)
            val uu = algebraic.multiply(u, u)
            val xxx = algebraic.multiply(xx, x)
            val uuu = algebraic.multiply(uu, u)
            assertEquals(0, algebraic.compare(z, algebraic.multiply(x, y)))
            assertEquals(0, algebraic.compare(algebraic.multiply(x, uu), algebraic.rational(BigFraction.ONE)))
            if (sign == 0) assertTrue(algebraic.sign(z) != 0) else assertEquals(sign, algebraic.sign(z))
            assertTrue(algebraic.sign(algebraic.add(uuu, xxx)) != 0)
            assertTrue(algebraic.sign(algebraic.subtract(uuu, xxx)) != 0)
            assertEquals(33, witness.realCount)
        }
    }

    @Test
    fun `a nonzero rewritten sum retains its zero coordinate solutions`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (assert (and (= x2 (+ x0 x1)) (distinct x2 0) (= (* x0 x3 x3) 0)
                         (= (* x3 x3) 1) (= (* x1 x1) 1) (> x1 0)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        assertEquals(0, algebraic.sign(witness.realValue(0)))
        assertEquals(0, algebraic.compare(witness.realValue(2), witness.realValue(1)))
        assertTrue(algebraic.sign(witness.realValue(2)) > 0)
    }

    @Test
    fun `a weak rewritten predicate retains its zero factors`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (assert (and (= x2 (* x0 x1)) (>= x2 0) (= (* x0 x3 x3) 0)
                         (= (* x3 x3) 1) (= (* x1 x1) 1)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        assertEquals(0, algebraic.sign(witness.realValue(0)))
        assertEquals(0, algebraic.sign(witness.realValue(2)))
    }

    @Test
    fun `graph derived nonzero factors stay within their Boolean region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= x2 (* x0 x1)) (distinct x2 0)
                             (distinct x3 0) (= (* x0 x3 x3) 0))
                        (and b (= x0 0) (= x1 3) (= x2 0) (= x3 1))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(witness.boolValue(0))
        assertEquals(BigFraction.ZERO, witness.realValue(0).rational)
        assertEquals(BigFraction.ZERO, witness.realValue(2).rational)
    }

    @Test
    fun `retracted pins remove graph derived nonzero factors`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and (=> b (and (= x2 (* x0 x1)) (distinct x2 0) (= (* x0 x3 x3) 1)))
                         (=> (not b) (and (= x0 0) (= x1 3) (= x2 0) (= x3 1)))))
            """.trimIndent(),
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            for (pin in listOf(true, false, true)) {
                val result = invocation.check(PolynomialCondition(bools = mapOf(0 to pin)))

                val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
                val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
                assertEquals(pin, witness.boolValue(0))
                if (pin) {
                    assertTrue(algebraic.sign(witness.realValue(2)) != 0)
                    assertEquals(0, algebraic.compare(witness.realValue(2),
                        algebraic.multiply(witness.realValue(0), witness.realValue(1))))
                } else {
                    assertEquals(0, algebraic.sign(witness.realValue(0)))
                    assertEquals(0, algebraic.sign(witness.realValue(2)))
                }
            }
        }
    }

    @Test
    fun `a rewritten nonzero contradiction refutes without exploring cells`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (assert (and (= x2 (+ (* x0 x1) 1)) (distinct (- x2 (* x0 x1) 1) 0)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(0L, result.stats.liftedCells)
    }

    @Test
    fun `zero resultants do not accept a noncommon required root`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= (* x0 x0) 1) (= (* (- x0 1) (- x0 2)) 0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        assertEquals(0, algebraic.compare(witness.realValue(0), algebraic.rational(BigFraction.ONE)))
    }

    @Test
    fun `required root projection fits the source matrix allowance`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (assert (and (= (+ (* x0 x0) (* x1 x1)) 1)
                         (distinct (+ (* x1 x1 x1) (* x0 x0 x0)) 0)
                         (distinct (- (* x1 x1 x1) (* x0 x0 x0)) 0)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 25)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        val x = witness.realValue(0)
        val y = witness.realValue(1)
        val xx = algebraic.multiply(x, x)
        val yy = algebraic.multiply(y, y)
        val xxx = algebraic.multiply(xx, x)
        val yyy = algebraic.multiply(yy, y)
        assertEquals(0, algebraic.compare(algebraic.add(xx, yy), algebraic.rational(BigFraction.ONE)))
        assertTrue(algebraic.sign(algebraic.add(yyy, xxx)) != 0)
        assertTrue(algebraic.sign(algebraic.subtract(yyy, xxx)) != 0)
        assertEquals(33, witness.realCount)
        for (variable in 2..32) assertEquals(BigFraction.ZERO, witness.realValue(variable).rational)
    }

    @Test
    fun `nonzero root premises stay within their source Boolean region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (> x0 0) (= (* x0 x1) 1) (< x1 0))
                        (and b (= x0 0) (= x1 3))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(witness.boolValue(0))
        assertEquals(BigFraction.ZERO, witness.realValue(0).rational)
        assertEquals(BigFraction.ofLong(3), witness.realValue(1).rational)
    }

    @Test
    fun `retracted source pins remove nonzero root premises`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and (=> b (> x0 0)) (=> b (= (* x0 x1) 1))
                         (=> (not b) (= x0 0)) (=> (not b) (= x1 3))))
            """.trimIndent(),
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            for (pin in listOf(true, false, true)) {
                val result = invocation.check(PolynomialCondition(bools = mapOf(0 to pin)))

                val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
                val x = assertNotNull(witness.realValue(0).rational)
                val y = assertNotNull(witness.realValue(1).rational)
                assertEquals(pin, witness.boolValue(0))
                if (pin) {
                    assertTrue(x > BigFraction.ZERO)
                    assertEquals(BigFraction.ONE, x * y)
                } else {
                    assertEquals(BigFraction.ZERO, x)
                    assertEquals(BigFraction.ofLong(3), y)
                }
            }
        }
    }

    @Test
    fun `unproved leading coefficients retain their zero fibers`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= (* (- x0 1) x1 x1) 0) (> x0 2) (< (* x1 x1) 1)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(assertNotNull(witness.realValue(0).rational) > BigFraction.ofLong(2))
        assertEquals(BigFraction.ZERO, witness.realValue(1).rational)
    }

    @Test
    fun `shared zero factors satisfy weak inequalities on required roots`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (assert (and (= (+ (* x0 x0) (* x1 x1)) 1)
                         (>= (* (- (+ (* x0 x0) (* x1 x1)) 1) (+ x1 x0 1)) 0)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        val xx = algebraic.multiply(witness.realValue(0), witness.realValue(0))
        val yy = algebraic.multiply(witness.realValue(1), witness.realValue(1))
        assertEquals(0, algebraic.compare(algebraic.add(xx, yy), algebraic.rational(BigFraction.ONE)))
    }

    @Test
    fun `shared zero factors cannot satisfy strict inequalities on required roots`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (assert (and (= (+ (* x0 x0) (* x1 x1)) 1)
                         (> (* (- (+ (* x0 x0) (* x1 x1)) 1) (+ x1 x0 1)) 0)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `required repeated roots retain their exact algebraic coordinate`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (* (- (* x0 x0) 2) (- (* x0 x0) 2)) 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        assertEquals(0, algebraic.compare(algebraic.multiply(witness.realValue(0), witness.realValue(0)),
            algebraic.rational(BigFraction.ofLong(2))))
        assertEquals(33, witness.realCount)
    }

    @Test
    fun `a required polynomial without real roots refutes its complete region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (+ (* x0 x0) 1) 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `degree ordered complete projection retains original inequality coordinates`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (> x0 (* x1 x1)) (> x1 0) (> x0 0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val x = assertNotNull(witness.realValue(0).rational)
        val y = assertNotNull(witness.realValue(1).rational)
        assertEquals(33, witness.realCount)
        assertTrue(y > BigFraction.ZERO)
        assertTrue(x > y * y)
        for (variable in 2..32) assertEquals(BigFraction.ZERO, witness.realValue(variable).rational)
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

    @Test
    fun `conditional equality sections reconstruct all original coordinates after a refuted region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= x0 x1) (= x0 (+ x1 1)))
                        (and b (= x0 (* x1 x1)) (= (* x1 x1) 2))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(witness.boolValue(0))
        assertEquals(33, witness.realCount)
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        assertEquals(0, algebraic.compare(witness.realValue(0), algebraic.rational(BigFraction.ofLong(2))))
        for (variable in 2..32) assertEquals(BigFraction.ZERO, witness.realValue(variable).rational)
        assertEquals(2L, result.stats.booleanTheoryRegions)
        assertEquals(2L, result.stats.equationalSections)
    }

    @Test
    fun `negated disequalities select exact zero sections within their region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (not (distinct x0 (* x1 x1))) (= (* x1 x1) 2)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        assertEquals(0, algebraic.compare(witness.realValue(0), algebraic.rational(BigFraction.ofLong(2))))
        assertEquals(1L, result.stats.equationalSections)
    }

    @Test
    fun `required zero products retain their singular coordinate sections`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= x0 0) (= (* x0 (+ (* x1 x1) (* x2 x2) (- 1))) 0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        for (variable in 0..32) assertEquals(BigFraction.ZERO, witness.realValue(variable).rational)
        assertEquals(1L, result.stats.equationalSections)
    }

    @Test
    fun `Boolean pin retraction rebuilds conditional equality sections`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and (=> b (= x0 (* x1 x1)))
                         (=> (not b) (= x0 (+ (* x1 x1) 1))) (= (* x1 x1) 2)))
            """.trimIndent(),
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation(QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0))).use { invocation ->
            for (pin in listOf(false, true, false)) {
                val result = invocation.check(PolynomialCondition(bools = mapOf(0 to pin)))

                val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
                assertEquals(pin, witness.boolValue(0))
                val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
                assertEquals(0, algebraic.compare(witness.realValue(0),
                    algebraic.rational(BigFraction.ofLong(if (pin) 2L else 3L))))
            }
        }
    }
}
