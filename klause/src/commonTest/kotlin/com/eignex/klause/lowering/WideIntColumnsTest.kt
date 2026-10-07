package com.eignex.klause.lowering

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.toLongExact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Allocating digit columns for a wide quantity. The properties that matter are that the weights and the
 * digit domains reconstruct exactly the values the quantity could take, that each value has only one
 * digit vector (else search revisits values it has already ruled out), and that a coefficient with no
 * usable width is refused rather than silently given one whose products wrap.
 */
class WideIntColumnsTest {

    private fun big(s: String) = parseBigInt(s)

    private fun counter(): (Long, Long) -> Int {
        var next = 0
        return { _, _ -> next++ }
    }

    private fun recorder(seen: MutableList<Pair<Long, Long>>): (Long, Long) -> Int {
        var next = 0
        return { lo, hi ->
            seen.add(lo to hi)
            next++
        }
    }

    @Test
    fun `weights reconstruct a value past Long from its digits`() {
        val v = big("18446744073709551616") // 2^64
        val c = wideIntColumns(v, BIG_ONE, counter())!!
        val digits = WideIntDigits.digitsOf(v, c.width, c.columns.size)
        var acc = BIG_ZERO
        c.weights().forEachIndexed { i, w -> acc += w * bigIntOf(digits[i]) }
        assertEquals(v, acc)
    }

    @Test
    fun `only the leading digit is signed so each value has one representation`() {
        val seen = mutableListOf<Pair<Long, Long>>()
        wideIntColumns(big("1000000000000000000000"), BIG_ONE, recorder(seen))
        for (i in 0 until seen.size - 1) assertEquals(0L, seen[i].first, "digit $i must be non-negative")
        assertTrue(seen.last().first < 0, "the leading digit carries the sign")
    }

    @Test
    fun `the leading digit spans one full width in each direction`() {
        val seen = mutableListOf<Pair<Long, Long>>()
        val c = wideIntColumns(big("1000000000000000000000"), BIG_ONE, recorder(seen))!!
        val max = (WideIntDigits.pow2(c.width) - BIG_ONE).toLongExact()
        assertEquals(-max - 1, seen.last().first)
        assertEquals(max, seen.last().second)
    }

    @Test
    fun `a digit domain times the coefficient stays inside Long`() {
        // The invariant that keeps refutation working at the least significant position.
        val coeff = big("1000003")
        val c = wideIntColumns(big("340282366920938463463374607431768211456"), coeff, counter())!!
        val product = coeff * WideIntDigits.pow2(c.width)
        assertTrue(product <= bigIntOf(Long.MAX_VALUE), "coeff * 2^width overflowed: $product")
    }

    @Test
    fun `a coefficient with no usable width is refused`() {
        assertNull(wideIntColumns(big("18446744073709551616"), big("4611686018427387904"), counter()))
    }

    @Test
    fun `a larger magnitude needs more digits at the same width`() {
        val small = wideIntColumns(big("18446744073709551616"), BIG_ONE, counter())!!
        val large = wideIntColumns(big("340282366920938463463374607431768211456"), BIG_ONE, counter())!!
        assertTrue(large.columns.size > small.columns.size)
    }

    @Test
    fun `the columns cover the magnitude with a position to spare for the sign`() {
        val v = big("18446744073709551616") // 2^64
        val c = wideIntColumns(v, BIG_ONE, counter())!!
        assertEquals(WideIntDigits.digitCount(v, c.width) + 1, c.columns.size)
    }
}
