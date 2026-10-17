package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialNonzeroReductionTest {
    @Test
    fun `a required nonzero product supplies independent equation factor premises`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (distinct (* x0 x2) 0)) (assert (= (* x0 (- x1 1)) 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic()
        assertTrue(algebraic.sign(witness.realValue(0)) != 0)
        assertTrue(algebraic.sign(witness.realValue(2)) != 0)
        assertEquals(0, algebraic.compare(witness.realValue(1), algebraic.rational(BigFraction.ONE)))
    }

    @Test
    fun `cancelled nonzero factors retain irrational roots without projection matrices`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (distinct x0 0)) " +
                "(assert (= (* x0 (- (* x1 x1) 2)) 0)) (assert (> x1 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic()
        assertTrue(algebraic.sign(witness.realValue(0)) != 0)
        assertTrue(algebraic.sign(witness.realValue(1)) > 0)
        assertEquals(0, algebraic.compare(algebraic.multiply(witness.realValue(1), witness.realValue(1)),
            algebraic.rational(BigFraction.ofLong(2))))
    }

    @Test
    fun `closed guards retain zero equation factors`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (>= x0 0)) (assert (= x0 0)) (assert (= x1 0)) " +
                "(assert (= (* x0 (- (* x1 x1) 2)) 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic()
        assertEquals(0, algebraic.sign(witness.realValue(0)))
        assertEquals(0, algebraic.sign(witness.realValue(1)))
    }

    @Test
    fun `unknown common factors retain their zero fibres`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (distinct x0 0)) (assert (= x2 0)) (assert (= x1 0)) " +
                "(assert (= (* x0 x2 (- (* x1 x1) 2)) 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic()
        assertTrue(algebraic.sign(witness.realValue(0)) != 0)
        assertEquals(0, algebraic.sign(witness.realValue(1)))
        assertEquals(0, algebraic.sign(witness.realValue(2)))
    }

    @Test
    fun `negative factor premises preserve equation truth`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (< x0 0)) (assert (= (* x0 (- x1 1)) 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic()
        assertTrue(algebraic.sign(witness.realValue(0)) < 0)
        assertEquals(0, algebraic.compare(witness.realValue(1), algebraic.rational(BigFraction.ONE)))
    }

    @Test
    fun `nonzero equation premises leave signed inequalities intact`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (< x0 0)) (assert (>= (* x0 x1) 0)) (assert (<= x1 (- 1))) " +
                "(assert (= (* x0 (- x2 1)) 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic()
        assertTrue(algebraic.sign(witness.realValue(0)) < 0)
        assertTrue(algebraic.compare(witness.realValue(1), algebraic.rational(BigFraction.MINUS_ONE)) <= 0)
        assertEquals(0, algebraic.compare(witness.realValue(2), algebraic.rational(BigFraction.ONE)))
    }

    @Test
    fun `nonzero premises stay inside their Boolean region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (or (and (distinct x0 0) (= (* x0 (- (* x1 x1) 2)) 0) " +
                "(= (* x1 x1) 3)) (and (= x0 0) (= x1 0))))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic()
        assertEquals(0, algebraic.sign(witness.realValue(0)))
        assertEquals(0, algebraic.sign(witness.realValue(1)))
    }

    @Test
    fun `interruption during factor cancellation publishes no reduction`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (distinct x 0)) (assert (= (* x (- y 1)) 0))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))
        var stopped = false
        val interrupted = PolynomialArithmetic(Cancellation { stopped })
        val pending = preparePolynomialNonzeroReduction(atoms, interrupted)
        assertNull(pending.next())
        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.next() }
    }
}
