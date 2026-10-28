package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.plus
import com.eignex.klause.util.unaryMinus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AlgebraicIntegersTest {
    private val arithmetic = AlgebraicArithmetic()

    @Test
    fun `irrational ceilings should use exact signed root order`() {
        val values = arithmetic.roots(RationalPolynomial.of(-2, 0, 1))
        for ((value, expected) in values.zip(listOf(bigIntOf(-1), bigIntOf(2)))) {
            val ceiling = arithmetic.ceilInteger(value)

            assertEquals(expected, ceiling)
        }
    }

    @Test
    fun `wide rational ceiling should preserve every integer bit`() {
        val value = arithmetic.rational(BigFraction.of(parseBigInt("18014398509481987"), bigIntOf(2)))

        val ceiling = arithmetic.ceilInteger(value)

        assertEquals(parseBigInt("9007199254740994"), ceiling)
    }

    @Test
    fun `integer root should remain integral with a nonpoint isolating interval`() {
        val value = AlgebraicReal(
            RationalPolynomial.of(-9, 0, 1),
            2,
            RationalInterval(BigFraction.ofLong(2), BigFraction.ofLong(4)),
        )

        val integer = arithmetic.exactInteger(value)

        assertEquals(bigIntOf(3), integer)
    }

    @Test
    fun `wide integer algebraic boundary should have an exact ceiling from either sign`() {
        val magnitude = parseBigInt("100000000000000000000")
        for (endpoint in listOf(magnitude, -magnitude)) {
            val value = AlgebraicReal(
                RationalPolynomial(listOf(BigFraction.of(-endpoint, BIG_ONE), BigFraction.ONE)),
                1,
                RationalInterval(
                    BigFraction.of(endpoint - BIG_ONE, BIG_ONE),
                    BigFraction.of(endpoint + BIG_ONE, BIG_ONE),
                ),
            )

            val ceiling = arithmetic.ceilInteger(value)

            assertEquals(endpoint, ceiling)
        }
    }

    @Test
    fun `irrational root should have no exact integer value`() {
        val value = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()

        val integer = arithmetic.exactInteger(value)

        assertNull(integer)
    }

    @Test
    fun `cancelled integer extraction should publish no result`() {
        val value = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val cancelled = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { true }))

        assertFailsWith<PolynomialInterrupted> { cancelled.exactInteger(value) }
    }
}
