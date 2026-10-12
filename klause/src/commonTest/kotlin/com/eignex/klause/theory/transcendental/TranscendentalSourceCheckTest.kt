package com.eignex.klause.theory.transcendental

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.real.ExactRealArithmetic
import com.eignex.klause.arithmetic.transcendental.TranscendentalArithmetic
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalFunction
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals

class TranscendentalSourceCheckTest {
    @Test
    fun `negating an invalid log does not create a witness`() {
        val logarithm = TranscendentalExpression.Function(TranscendentalFunction.LOG, constant(0))
        val equality = TranscendentalFormula.Atom(logarithm, PolynomialRelation.EQ, constant(1))
        val source = TranscendentalFormula.Not(equality)

        val result = checker().check(source)

        assertEquals(SourceTruth.DOMAIN_INVALID, result)
    }

    @Test
    fun `inactive numeric conditional branch does not impose a log domain`() {
        val logarithm = TranscendentalExpression.Function(TranscendentalFunction.LOG, constant(0))
        val choice = TranscendentalExpression.Conditional(
            TranscendentalFormula.BooleanVariable(SourceBoolId(0)), constant(1), logarithm,
        )
        val equality = TranscendentalFormula.Atom(choice, PolynomialRelation.EQ, constant(1))

        val result = checker(booleanArrayOf(true)).check(equality)

        assertEquals(SourceTruth.TRUE, result)
    }

    @Test
    fun `disjunction requires defined arithmetic in each atom`() {
        val logarithm = TranscendentalExpression.Function(TranscendentalFunction.LOG, constant(-1))
        val equality = TranscendentalFormula.Atom(logarithm, PolynomialRelation.EQ, constant(1))
        val source = TranscendentalFormula.Or(listOf(TranscendentalFormula.Constant(true), equality))

        val result = checker().check(source)

        assertEquals(SourceTruth.DOMAIN_INVALID, result)
    }

    @Test
    fun `strict function inequality is certified by rational separation`() {
        val exponential = TranscendentalExpression.Function(TranscendentalFunction.EXP, constant(1))
        val source = TranscendentalFormula.Atom(exponential, PolynomialRelation.GT, constant(2))

        val result = checker().check(source)

        assertEquals(SourceTruth.TRUE, result)
    }

    private fun checker(bools: BooleanArray = booleanArrayOf()): TranscendentalSourceCheck =
        TranscendentalSourceCheck(
            ExactRealArithmetic(TranscendentalArithmetic(), AlgebraicArithmetic()), emptyList(), bools,
        )

    private fun constant(value: Long): TranscendentalExpression.Constant {
        val fraction = BigFraction.ofLong(value)
        return TranscendentalExpression.Constant(PolynomialExpression.Constant.of(fraction.num, fraction.den))
    }
}
