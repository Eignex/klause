package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.quadratic.QuadraticControl
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConvexPolynomialOptimumTest {
    @Test
    fun `exact source affine boundary is retained by numerical reconstruction`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (>= x 2)) (check-sat)")
        val objective = PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2)

        val result = tryConvexPolynomialOptimum(
            source.formula, objective, source.boolCount, source.realCount, emptyMap(),
            PolynomialArithmetic(), QuadraticControl(),
        )

        assertEquals(BigFraction.ofLong(4), assertNotNull(result).certificate.bound.value)
        assertEquals(BigFraction.ofLong(2), result.assignment.realValue(0).rational)
    }

    @Test
    fun `source strictness prevents numerical boundary attainment`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 2)) (check-sat)")
        val objective = PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2)

        val result = tryConvexPolynomialOptimum(
            source.formula, objective, source.boolCount, source.realCount, emptyMap(),
            PolynomialArithmetic(), QuadraticControl(),
        )

        assertNull(result)
    }

    @Test
    fun `polynomial constraints require the exact image fallback`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2)) (check-sat)")
        val objective = PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2)

        val result = tryConvexPolynomialOptimum(
            source.formula, objective, source.boolCount, source.realCount, emptyMap(),
            PolynomialArithmetic(), QuadraticControl(),
        )

        assertNull(result)
    }

    @Test
    fun `nonconvex source objective cannot receive a convex certificate`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (and (>= x (- 1)) (<= x 1))) (check-sat)")
        val objective = PolynomialExpression.Product(listOf(
            PolynomialExpression.Constant.of(BigFraction.MINUS_ONE.num),
            PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2),
        ))

        val result = tryConvexPolynomialOptimum(
            source.formula, objective, source.boolCount, source.realCount, emptyMap(),
            PolynomialArithmetic(), QuadraticControl(),
        )

        assertNull(result)
    }
}
