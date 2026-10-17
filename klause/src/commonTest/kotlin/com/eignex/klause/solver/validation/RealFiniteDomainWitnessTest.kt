package com.eignex.klause.solver.validation

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.RationalInterval
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.arithmetic.real.ExactRealExpression
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class RealFiniteDomainWitnessTest {
    @Test
    fun `source membership should reject a rational outside a sparse domain`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(RealFiniteDomain(0, doubleArrayOf(-2.5, 0.125, 7.0))),
            numRealVars = 1, realLower = doubleArrayOf(-10.0), realUpper = doubleArrayOf(10.0))
        for ((value, accepted) in listOf(-2.5 to true, 0.125 to true, 7.0 to true, 0.25 to false)) {
            val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(value),
                exactReals = listOf(requireNotNull(BigFraction.ofDouble(value))))

            val verdict = verifyRealCoordinates(source, point)

            if (accepted) assertIs<Verification.Accepted<*, *>>(verdict) else assertIs<Verification.Rejected>(verdict)
        }
    }

    @Test
    fun `rational algebraic roots should match membership without point storage`() {
        val half = AlgebraicReal(RationalPolynomial.of(-1, 2), 1,
            RationalInterval(BigFraction.ZERO, BigFraction.ONE))
        val source = Problem(0, 0, emptyArray(), arrayOf(RealFiniteDomain(0, doubleArrayOf(0.5))),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.5), algebraicReals = listOf(half))
        assertNull(half.rational)

        val verdict = verifyRealCoordinates(source, point)

        assertIs<Verification.Accepted<*, *>>(verdict)
    }

    @Test
    fun `an enclosing algebraic interval should not establish finite membership`() {
        val root = AlgebraicReal(RationalPolynomial.of(-2, 0, 1), 2,
            RationalInterval(BigFraction.ONE, BigFraction.ofLong(2)))
        val source = Problem(0, 0, emptyArray(), arrayOf(RealFiniteDomain(0, doubleArrayOf(1.4, 1.5))),
            numRealVars = 1, realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(2.0))
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.4142135623730951),
            algebraicReals = listOf(root))

        val verdict = verifyRealCoordinates(source, point)

        assertIs<Verification.Rejected>(verdict)
    }

    @Test
    fun `symbolic algebraic leaves should retain exact membership authority`() {
        val half = AlgebraicReal(RationalPolynomial.of(-1, 2), 1,
            RationalInterval(BigFraction.ZERO, BigFraction.ONE))
        val source = Problem(0, 0, emptyArray(), arrayOf(RealFiniteDomain(0, doubleArrayOf(0.5))),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.5),
            symbolicReals = listOf(ExactRealValue(ExactRealExpression.Algebraic(half))))

        val verdict = verifyRealCoordinates(source, point)

        assertIs<Verification.Accepted<*, *>>(verdict)
    }

    @Test
    fun `interrupted membership checks should withhold source acceptance`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(RealFiniteDomain(0, doubleArrayOf(0.5))),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.5),
            exactReals = listOf(BigFraction.ONE / BigFraction.ofLong(2)))

        val verdict = verifyRealCoordinates(source, point, Cancellation { true })

        assertIs<Verification.Indeterminate>(verdict)
    }
}
