package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PolynomialPredicateSharingTest {
    @Test
    fun `Boolean definition substitution retains numeric predicate order`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const b Bool) " +
                "(assert (and (or b (> y 0)) (= b (> x 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val reduced = assertNotNull(formula.reduceBooleanDefinitions(arithmetic))

        assertEquals(formula.atoms, reduced.atoms)
    }
}
