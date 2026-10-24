package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PolynomialSignRefutationTest {
    @Test
    fun `strict source signs refute incompatible polynomial signs`() {
        for ((assertions, expected) in listOf(
            "(> x 0) (> y 0) (= (+ (* x x y) (* x y y)) 0)" to true,
            "(< x 0) (< y 0) (< (* x y) 0)" to true,
            "(< x 0) (> y 0) (>= (* x y) 0)" to true,
            "(distinct x 0) (= (* x x) 0)" to true,
            "(>= x 0) (= (* x x) 0)" to false,
            "(<= x 0) (= (* x x) 0)" to false,
            "(= x 0) (> (* x y) 0)" to true,
            "(= x 0) (= (* x y) 0)" to false,
            "(not (<= x 0)) (= (* x x) 0)" to true,
            "(not (> x 0)) (= (* x x) 0)" to false,
            "(> x 0) (> y 0) (= (- (* x x) (* y y)) 0)" to false,
            "(> x 0) (< x 0)" to true,
            "(= (- x) 0) (distinct (* x x) 0)" to true,
            "(< (* x x) 0)" to true,
            "(<= (* x x) 0)" to false,
        )) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) (assert (and $assertions))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val refuted = refutePolynomialSigns(formula, arithmetic, 128, 2048)

            assertEquals(expected, refuted, assertions)
        }
    }

    @Test
    fun `guarded signs cannot refute a feasible alternative`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (or (and (> x 0) (= (* x x) 0)) (= x 0)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val refuted = refutePolynomialSigns(formula, arithmetic, 128, 2048)

        assertFalse(refuted)
    }

    @Test
    fun `incomplete admission retains no refutation`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (and (> x 0) (= (* x x) 0)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        for ((atoms, terms) in listOf(1 to 2048, 128 to 1)) {
            assertFalse(refutePolynomialSigns(formula, arithmetic, atoms, terms))
        }
    }

    @Test
    fun `containing work exhaustion withholds the completed refutation`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (and (> x 0) (= (* x x) 0)))",
        )
        val completed = assertIs<QfNraResult.Unsatisfiable>(
            solveQfNra(source.formula, source.boolCount, source.realCount),
        )

        val stopped = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = completed.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(stopped).reason)
    }

    @Test
    fun `interruption withholds the sign refutation`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (< (* x x) 0))")
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val interrupted = PolynomialArithmetic(Cancellation { true })

        assertFailsWith<PolynomialInterrupted> { refutePolynomialSigns(formula, interrupted, 128, 2048) }
    }
}
