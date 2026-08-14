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

    @Test
    fun `a rewritten term reproduces the original value on any digit assignment`() {
        val v = big("18446744073709551616") // 2^64
        val coeff = big("3")
        val c = wideIntColumns(v, coeff, counter())!!
        val terms = c.rewriteTerm(coeff)!!
        val digits = WideIntDigits.digitsOf(v, c.width, c.columns.size)
        // Evaluate the rewritten terms at the digits of v, with the negative vector at zero.
        val byCol = terms.toMap()
        var acc = BigInteger.ZERO
        c.columns.forEachIndexed { i, col ->
            acc += BigInteger.fromLong(byCol.getValue(col)) * BigInteger.fromLong(digits[i])
        }
        assertEquals(coeff * v, acc, "the rewritten row must evaluate to coeff * v")
    }

    @Test
    fun `the negative vector mirrors the positive one`() {
        val c = wideIntColumns(big("18446744073709551616"), BigInteger.ONE, counter())!!
        val terms = c.rewriteTerm(BigInteger.ONE)!!.toMap()
        c.columns.forEachIndexed { i, col ->
            assertEquals(-terms.getValue(col), terms.getValue(c.negative[i]), "signs must mirror")
        }
    }

    @Test
    fun `a coefficient whose product escapes Long is refused rather than wrapped`() {
        val c = wideIntColumns(big("340282366920938463463374607431768211456"), BigInteger.ONE, counter())!!
        assertNull(c.rewriteTerm(big("4611686018427387904")), "a wrapping coefficient must be refused")
    }

    @Test
    fun `materialising a wide term yields digit columns of equal value`() {
        val v = big("18446744073709551616") // 2^64
        var posted: WideLinComb? = null
        val term = WideLinComb(mapOf(7 to BigInteger.ONE), BigInteger.ZERO)
        val digits = materializeWide(term, v, counter()) { posted = it }!!
        assertTrue(digits.coeffs.isNotEmpty(), "the result ranges over the digit columns")
        assertEquals(BigInteger.ZERO, digits.constant)
        // The channeling row is `term - digits`, so variable 7 keeps coefficient 1 and every digit
        // column appears negated.
        val row = posted!!
        assertEquals(BigInteger.ONE, row.coeffs.getValue(7))
        for ((col, c) in digits.coeffs) assertEquals(-c, row.coeffs.getValue(col), "digit $col must negate")
    }

    @Test
    fun `materialising refuses a term whose coefficient leaves no digit width`() {
        val term = WideLinComb(mapOf(1 to big("4611686018427387904")), BigInteger.ZERO)
        assertNull(materializeWide(term, big("18446744073709551616"), counter()) { })
    }
}
