package com.eignex.klause.solver.objective

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals

class ExactQuadraticEvaluationTest {
    @Test
    fun `source squares retain exact algebraic cancellation`() {
        val arithmetic = AlgebraicArithmetic()
        val x = QuadraticVariable.Real(SourceRealId(0))
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(BigFraction.ONE, x, x)))

        val value = objective.evaluateExact(arithmetic) { root }

        assertEquals(BigFraction.ofLong(2), value.rational)
    }

    @Test
    fun `source cross terms retain irrational objective values`() {
        val arithmetic = AlgebraicArithmetic()
        val x = QuadraticVariable.Real(SourceRealId(0))
        val y = QuadraticVariable.Real(SourceRealId(1))
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(BigFraction.ofLong(3), x, y)))

        val value = objective.evaluateExact(arithmetic) {
            if (it == x) root else arithmetic.rational(BigFraction.ofLong(2))
        }

        assertEquals(0, arithmetic.signAt(RationalPolynomial.of(-72, 0, 1), value))
        assertEquals(1, arithmetic.sign(value))
    }
}
