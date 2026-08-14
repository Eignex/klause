package com.eignex.klause.formats

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Allocating digit columns for a wide variable. The property that matters is that the weights and the
 * digit domains reconstruct exactly the values the original variable could take, and that a coefficient
 * with no usable width is refused rather than silently given one whose products wrap.
 */
class WideIntColumnsTest {

    private fun big(s: String) = BigInteger.parseString(s)

    private fun counter(): () -> Int {
        var next = 0
        return { next++ }
    }

    @Test
    fun `weights reconstruct a value past Long from its digits`() {
        val v = big("18446744073709551616") // 2^64
        val c = wideIntColumns(v, BigInteger.ONE, counter())!!
        val digits = WideIntDigits.digitsOf(v, c.width, c.columns.size)
        var acc = BigInteger.ZERO
        c.weights().forEachIndexed { i, w -> acc += w * BigInteger.fromLong(digits[i]) }
        assertEquals(v, acc)
    }

    @Test
    fun `a positive and a negative vector are allocated so the sign lives in the row`() {
        val c = wideIntColumns(big("1000000000000000000000"), BigInteger.ONE, counter())!!
        assertEquals(c.columns.size, c.negative.size)
        assertTrue(c.columns.none { it in c.negative.toSet() }, "the two vectors must be distinct columns")
    }

    @Test
    fun `every digit domain times the coefficient stays inside Long`() {
        val coeff = big("1000003")
        val c = wideIntColumns(big("340282366920938463463374607431768211456"), coeff, counter())!!
        val product = coeff * BigInteger.fromLong(c.digitMax())
        assertTrue(product <= BigInteger.fromLong(Long.MAX_VALUE), "coeff * digitMax overflowed: $product")
    }

    @Test
    fun `a coefficient with no usable width is refused`() {
        assertNull(wideIntColumns(big("18446744073709551616"), big("4611686018427387904"), counter()))
    }

    @Test
    fun `a larger magnitude needs more digits at the same width`() {
        val small = wideIntColumns(big("18446744073709551616"), BigInteger.ONE, counter())!!
        val large = wideIntColumns(big("340282366920938463463374607431768211456"), BigInteger.ONE, counter())!!
        assertTrue(large.columns.size > small.columns.size)
    }
}
