package com.eignex.klause.solver.objective

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AffineQuadraticObjectiveTest {
    @Test
    fun `affine capture keeps each source namespace and full width integer coefficients`() {
        val source = LinearObjective(
            boolWeights = longArrayOf(0, -3), intCoefficients = longArrayOf(Long.MIN_VALUE, 0),
            realCoefficients = doubleArrayOf(-0.0, 0.5), constant = Long.MIN_VALUE,
        )

        val exact = source.toQuadraticObjectiveSpec()

        assertEquals(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-3), QuadraticVariable.Bool(SourceBoolId(1))),
            QuadraticObjectiveTerm(
                QuadraticCoefficient.ofLong(Long.MIN_VALUE), QuadraticVariable.Int(SourceIntId(0)),
            ),
            QuadraticObjectiveTerm(
                QuadraticCoefficient.of(BigFraction.ofLong(1).num, BigFraction.ofLong(2).num),
                QuadraticVariable.Real(SourceRealId(1)),
            ),
        ), exact.terms)
        assertEquals(QuadraticCoefficient.ofLong(Long.MIN_VALUE), exact.constant)
    }

    @Test
    fun `real coefficients retain binary64 authority rather than decimal reinterpretation`() {
        val source = LinearObjective(realCoefficients = doubleArrayOf(0.1))
        val declared = requireNotNull(BigFraction.ofDouble(0.1))

        val exact = source.toQuadraticObjectiveSpec().terms.single().coefficient

        assertEquals(declared.num, exact.numerator)
        assertEquals(declared.den, exact.denominator)
    }

    @Test
    fun `normalized evaluation agrees with affine evaluation on exact rational coordinates`() {
        val source = LinearObjective(longArrayOf(-3), longArrayOf(7), 11, doubleArrayOf(0.1))
        val sample = Sample(booleanArrayOf(true), longArrayOf(-2), doubleArrayOf(0.5),
            exactReals = listOf(BigFraction.ONE / BigFraction.ofLong(2)))

        val exact = source.toQuadraticObjectiveSpec().evaluateExact(sample, AlgebraicArithmetic())

        assertEquals(source.evaluateExact(sample), exact.rational)
    }

    @Test
    fun `lowered objective capture agrees with the solver objective`() {
        val source = LinearObjectiveSpec(longArrayOf(-3), longArrayOf(7), 11, doubleArrayOf(0.1))

        val exact = source.toQuadraticObjectiveSpec()

        assertEquals(source.toLinearObjective().toQuadraticObjectiveSpec(), exact)
    }

    @Test
    fun `nonfinite real coefficients cannot become exact affine terms`() {
        for (coefficient in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            val source = LinearObjective(realCoefficients = doubleArrayOf(coefficient))

            assertFailsWith<IllegalArgumentException> { source.toQuadraticObjectiveSpec() }
        }
    }
}
