package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AlgebraicIntegersTest {
    private val arithmetic = AlgebraicArithmetic()

    @Test
    fun `negative irrational ceiling should use exact root order`() {
        val value = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).first()

        val ceiling = arithmetic.ceilInteger(value)

        assertEquals(bigIntOf(-1), ceiling)
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
