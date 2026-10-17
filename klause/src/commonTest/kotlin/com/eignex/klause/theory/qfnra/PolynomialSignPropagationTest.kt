package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialSignPropagationTest {
    @Test
    fun `complete regions propagate strict signs through polynomial definitions`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val equations = (1..16).joinToString(" ") { "(assert (= x$it (* x${it - 1} x${it - 1})))" }
        val source = SmtLibPolynomial.parse("$declarations $equations (assert (> x0 0)) (assert (= x16 0))")

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 50_000))

        val refuted = assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(0L, refuted.stats.dyadicAttempts)
        assertEquals(0L, refuted.stats.equationalSections)
    }

    @Test
    fun `nonpositive even power outputs retain their zero fiber`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= x1 (* x0 x0))) (assert (<= x1 0)) (assert (= x0 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic()
        assertEquals(0, algebraic.sign(witness.realValue(0)))
        assertEquals(0, algebraic.sign(witness.realValue(1)))
    }

    @Test
    fun `opposite signed terms can cancel in a required zero`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (> x0 0)) (assert (< x1 0)) " +
                "(assert (= x2 (+ x0 x1))) (assert (= x2 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic()
        assertTrue(algebraic.sign(witness.realValue(0)) > 0)
        assertTrue(algebraic.sign(witness.realValue(1)) < 0)
        assertEquals(0, algebraic.sign(algebraic.add(witness.realValue(0), witness.realValue(1))))
    }

    @Test
    fun `negative coefficients retain even power contradiction direction`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (< x0 0)) (assert (= x1 (- (* x0 x0)))) (assert (>= x1 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 50_000))

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `a sign refuted region leaves a feasible Boolean alternative`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (or (and (> x0 0) (= x1 (* x0 x0)) (< x1 0)) " +
                "(and (= x0 0) (= x1 0))))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic()
        assertEquals(0, algebraic.sign(witness.realValue(0)))
        assertEquals(0, algebraic.sign(witness.realValue(1)))
    }

    @Test
    fun `interruption during sign propagation withholds the refutation`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 0)) (assert (= (* x x) 0))")
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))
        var stopped = false
        val interrupted = PolynomialArithmetic(Cancellation { stopped })
        val pending = preparePolynomialSignRefutation(atoms, source.realCount, interrupted)
        repeat(3) { assertNull(pending.next()) }

        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.next() }
    }
}
