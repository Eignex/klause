package com.eignex.klause.theory.qfnia

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.QfNraAssignment
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NonlinearSourceCheckTest {
    @Test
    fun `original source checking rejects wrapped integer product witnesses`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const n Int) (assert (= (* n n) 10000000000000000000000000000000000000000))",
            allowIntegers = true,
        )
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val exact = QfNraAssignment(booleanArrayOf(), listOf(
            algebraic.rational(BigFraction.of(parseBigInt("100000000000000000000"), BIG_ONE)),
        ))
        val wrapped = QfNraAssignment(booleanArrayOf(), listOf(
            algebraic.rational(BigFraction.of(parseBigInt("7766279631452241920"), BIG_ONE)),
        ))

        assertTrue(checkNonlinearSource(source.formula, exact, arithmetic))
        assertFalse(checkNonlinearSource(source.formula, wrapped, arithmetic))
    }

    @Test
    fun `original source checking retains Boolean correlation with integer powers`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const n Int) (declare-const p Bool) (assert (= p (= (^ n 2) 4)))",
            allowIntegers = true,
        )
        val arithmetic = PolynomialArithmetic()
        val integer = AlgebraicArithmetic(arithmetic).rational(BigFraction.ofLong(2))

        val accepted = checkNonlinearSource(
            source.formula, QfNraAssignment(booleanArrayOf(true), listOf(integer)), arithmetic,
        )
        val rejected = checkNonlinearSource(
            source.formula, QfNraAssignment(booleanArrayOf(false), listOf(integer)), arithmetic,
        )

        assertTrue(accepted)
        assertFalse(rejected)
    }
}
