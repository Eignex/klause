package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals

class ExactNraAssignmentTest {
    @Test
    fun `exact witnesses retain coordinates after caller buffer mutation`() {
        val arithmetic = AlgebraicArithmetic(PolynomialArithmetic())
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val bools = booleanArrayOf(true)
        val reals = mutableListOf(root)
        val assignment = ExactNraAssignment(bools, reals)

        bools[0] = false
        reals[0] = arithmetic.rational(BigFraction.ZERO)
        assignment.bools[0] = false

        assertEquals(1, assignment.boolCount)
        assertEquals(1, assignment.realCount)
        assertEquals(true, assignment.boolValue(0))
        assertEquals(0, arithmetic.compare(root, assignment.realValue(0)))
        assertEquals(0, arithmetic.compare(root, assignment.reals.single()))
    }

    @Test
    fun `retained irrational coordinates reconstruct in a fresh arithmetic context`() {
        val original = AlgebraicArithmetic(PolynomialArithmetic())
        val assignment = ExactNraAssignment(
            BooleanArray(0),
            listOf(original.roots(RationalPolynomial.of(-2, 0, 1)).last()),
        )
        val fresh = AlgebraicArithmetic(PolynomialArithmetic())
        val value = assignment.realValue(0)

        val reconstructed = fresh.roots(RationalPolynomial(value.definingCoefficients))[value.rootIndex - 1]

        assertEquals(0, fresh.compare(value, reconstructed))
        assertEquals(BigFraction.ofLong(2), fresh.multiply(value, value).rational)
    }
}
