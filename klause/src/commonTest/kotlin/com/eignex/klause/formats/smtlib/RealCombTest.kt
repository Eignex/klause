package com.eignex.klause.formats.smtlib

import com.eignex.klause.simplex.exact.BigFraction
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RealCombTest {
    @Test
    fun `rational scaling retains one third exactly`() {
        val third = BigFraction.of(BigInteger.ONE, BigInteger.fromInt(3))
        val source = RealComb(mapOf(0 to BigFraction.ONE), mapOf(0 to third), third)

        val scaled = source.scaled(third)

        assertEquals(third, scaled.intCoeffs.getValue(0))
        assertEquals(BigFraction.of(BigInteger.ONE, BigInteger.fromInt(9)), scaled.realCoeffs.getValue(0))
        assertEquals(BigFraction.of(BigInteger.ONE, BigInteger.fromInt(9)), scaled.constant)
    }

    @Test
    fun `wide adjacent integers stay distinct through combination`() {
        val first = BigFraction.ofLong(9007199254740992L)
        val second = BigFraction.ofLong(9007199254740993L)
        val sum = sumRealCombs(
            listOf(
                RealComb(emptyMap(), mapOf(0 to first), BigFraction.ZERO),
                RealComb(emptyMap(), mapOf(0 to second), BigFraction.ZERO),
            ),
            negateTail = true,
        )

        assertEquals(BigFraction.MINUS_ONE, sum.realCoeffs.getValue(0))
        assertEquals(first.toDouble(), second.toDouble())
    }

    @Test
    fun `combination owns its exact coefficient maps`() {
        val ints = mutableMapOf(0 to BigFraction.ONE)
        val reals = mutableMapOf(1 to BigFraction.MINUS_ONE)
        val source = RealComb(ints, reals, BigFraction.ZERO)

        ints.clear()
        reals.clear()

        assertFalse(source.intCoeffs.isEmpty())
        assertFalse(source.realCoeffs.isEmpty())
    }
}
