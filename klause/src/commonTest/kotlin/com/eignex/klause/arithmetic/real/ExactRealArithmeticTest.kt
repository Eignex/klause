package com.eignex.klause.arithmetic.real

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.arithmetic.transcendental.TranscendentalArithmetic
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactRealArithmeticTest {
    @Test
    fun `log of two retains a symbolic authoritative value`() {
        val arithmetic = arithmetic()

        val value = arithmetic.function(ExactRealFunction.LOG, arithmetic.rational(q(2)))

        assertNull(value.algebraic)
        assertEquals(ExactRealFunction.LOG, (value.expression as ExactRealExpression.Function).function)
        assertEquals(1, arithmetic.compare(value, arithmetic.rational(q(0))))
    }

    @Test
    fun `inverse functions preserve the original exact scalar`() {
        val arithmetic = arithmetic()
        val original = arithmetic.rational(q(2))
        val logarithm = arithmetic.function(ExactRealFunction.LOG, original)

        val reconstructed = arithmetic.function(ExactRealFunction.EXP, logarithm)

        assertEquals(0, arithmetic.compare(original, reconstructed))
    }

    @Test
    fun `logarithm of exponential has exact original negative value`() {
        val arithmetic = arithmetic()
        val original = arithmetic.rational(q(-8))
        val exponential = arithmetic.function(ExactRealFunction.EXP, original)

        val reconstructed = arithmetic.function(ExactRealFunction.LOG, exponential)

        assertEquals(q(-8), reconstructed.rational)
    }

    @Test
    fun `logarithm rejects an invalid exact domain`() {
        val arithmetic = arithmetic()

        assertFailsWith<RealDomainInvalid> {
            arithmetic.function(ExactRealFunction.LOG, arithmetic.rational(q(0)))
        }
    }

    @Test
    fun `trigonometric identity is checked symbolically`() {
        val arithmetic = arithmetic()
        val x = arithmetic.function(ExactRealFunction.EXP, arithmetic.rational(q(1)))
        val sine = arithmetic.power(arithmetic.function(ExactRealFunction.SIN, x), 2)
        val cosine = arithmetic.power(arithmetic.function(ExactRealFunction.COS, x), 2)

        val sum = arithmetic.add(listOf(sine, cosine))

        assertEquals(q(1), sum.rational)
    }

    @Test
    fun `symbolic cancellation retains exact zero`() {
        val arithmetic = arithmetic()
        val value = arithmetic.function(ExactRealFunction.EXP, arithmetic.rational(q(1)))

        val difference = arithmetic.add(listOf(value, arithmetic.negate(value)))

        assertEquals(q(0), difference.rational)
    }

    @Test
    fun `rational multiples of pi retain exact special values`() {
        val arithmetic = arithmetic()
        val halfPi = arithmetic.multiply(listOf(arithmetic.rational(q(1) * q(2).reciprocal()), arithmetic.pi()))

        val sine = arithmetic.function(ExactRealFunction.SIN, halfPi)
        val cosine = arithmetic.function(ExactRealFunction.COS, halfPi)

        assertEquals(q(1), sine.rational)
        assertEquals(q(0), cosine.rational)
    }

    @Test
    fun `algebraic leaves preserve their exact polynomial coordinate`() {
        val arithmetic = arithmetic()
        val root = arithmetic.algebraic.roots(RationalPolynomial(listOf(q(-2), q(0), q(1)))).last()

        val value = arithmetic.scalar(root)

        assertTrue(value.algebraic === root)
        assertEquals(q(2), arithmetic.power(value, 2).rational)
    }

    @Test
    fun `insufficient interval refinement gives no equality verdict`() {
        val arithmetic = arithmetic()
        val exponential = arithmetic.function(ExactRealFunction.EXP, arithmetic.rational(q(1)))
        val interval = arithmetic.enclose(exponential, 4)
        val midpoint = (interval.lower + interval.upper) * q(2).reciprocal()

        val comparison = arithmetic.compare(exponential, arithmetic.rational(midpoint), maximumTerms = 4)

        assertNull(comparison)
    }

    private fun arithmetic(): ExactRealArithmetic = ExactRealArithmetic(
        TranscendentalArithmetic(), AlgebraicArithmetic(),
    )

    private fun q(value: Long): BigFraction = BigFraction.ofLong(value)
}
