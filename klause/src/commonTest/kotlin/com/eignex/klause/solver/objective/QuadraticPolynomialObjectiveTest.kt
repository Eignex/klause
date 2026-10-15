package com.eignex.klause.solver.objective

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuadraticPolynomialObjectiveTest {
    @Test
    fun `evaluateExact should square wide integers exactly`() {
        val value = parseBigInt("18446744073709551617")
        val sample = Sample(booleanArrayOf(), longArrayOf(), exactInts = listOf(value))
        val x = QuadraticVariable.Int(SourceIntId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x)))

        val evaluated = objective.evaluateExact(sample, AlgebraicArithmetic())

        assertEquals(
            BigFraction.of(parseBigInt("340282366920938463500268095579187314689"), bigIntOf(1)),
            evaluated.rational,
        )
    }

    @Test
    fun `evaluateExact should reject approximate real coordinates`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.5))
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x)))

        assertFailsWith<IllegalArgumentException> { objective.evaluateExact(sample, AlgebraicArithmetic()) }
    }
}
