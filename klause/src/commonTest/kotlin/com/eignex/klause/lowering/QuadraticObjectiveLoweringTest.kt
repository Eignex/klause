package com.eignex.klause.lowering

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.evaluateExact
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuadraticObjectiveLoweringTest {
    @Test
    fun `distributed mixed products preserve their literal source objective`() {
        val x = PolynomialExpression.Variable(SourceRealId(0))
        val i = PolynomialExpression.Variable(SourceRealId(1))
        val expression = PolynomialExpression.Product(listOf(
            PolynomialExpression.Sum(listOf(
                PolynomialExpression.Product(listOf(PolynomialExpression.Constant.of(bigIntOf(3)), x)),
                PolynomialExpression.Product(listOf(PolynomialExpression.Constant.of(bigIntOf(2)), i)),
                PolynomialExpression.Constant.of(bigIntOf(-1)),
            )),
            PolynomialExpression.Sum(listOf(x, PolynomialExpression.Product(listOf(
                PolynomialExpression.Constant.of(bigIntOf(-1)), i,
            )))),
        ))
        val variables = mapOf(
            0 to QuadraticVariable.Real(SourceRealId(0)), 1 to QuadraticVariable.Int(SourceIntId(0)),
        )
        val arithmetic = AlgebraicArithmetic()
        val sample = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(3.0),
            algebraicReals = listOf(arithmetic.rational(BigFraction.ofLong(3))))

        val objective = assertNotNull(lowerQuadraticObjective(expression, variables))

        assertEquals(BigFraction.ofLong(12), objective.evaluateExact(sample, arithmetic).rational)
    }

    @Test
    fun `quadratic coefficient lowering keeps numerator precision beyond binary64`() {
        val x = PolynomialExpression.Variable(SourceRealId(0))
        val expression = PolynomialExpression.Product(listOf(
            PolynomialExpression.Constant.of(bigIntOf(9007199254740993L), bigIntOf(7)),
            PolynomialExpression.Power(x, 2),
        ))
        val arithmetic = AlgebraicArithmetic()
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0),
            algebraicReals = listOf(arithmetic.rational(BigFraction.ONE)))

        val objective = assertNotNull(lowerQuadraticObjective(
            expression, mapOf(0 to QuadraticVariable.Real(SourceRealId(0))),
        ))

        assertEquals(
            BigFraction.of(bigIntOf(9007199254740993L), bigIntOf(7)),
            objective.evaluateExact(sample, arithmetic).rational,
        )
    }

    @Test
    fun `a cubic objective declines quadratic lowering`() {
        val expression = PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 3)

        val objective = lowerQuadraticObjective(expression, mapOf(0 to QuadraticVariable.Real(SourceRealId(0))))

        assertNull(objective)
    }

    @Test
    fun `an unmapped auxiliary cannot become a source objective coordinate`() {
        val expression = PolynomialExpression.Variable(SourceRealId(1))

        val objective = lowerQuadraticObjective(expression, mapOf(0 to QuadraticVariable.Real(SourceRealId(0))))

        assertNull(objective)
    }
}
