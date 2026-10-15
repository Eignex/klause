package com.eignex.klause.lowering

import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.toLongExact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WideIntDigitsTest {
    private fun big(s: String) = parseBigInt(s)

    @Test
    fun `digits should round-trip a value past Long and cover its magnitude`() {
        val v = big("18446744073709551616") // 2^64
        val w = 32
        val n = WideIntDigits.digitCount(v, w)
        assertTrue(n >= 3, "2^64 needs at least three 32-bit digits, got $n")
        assertEquals(v, WideIntDigits.recompose(WideIntDigits.digitsOf(v, w, n), w))
    }

    @Test
    fun `every digit should stay inside its width`() {
        val v = big("123456789012345678901234567890")
        val w = 20
        val n = WideIntDigits.digitCount(v, w)
        val radix = WideIntDigits.pow2(w).toLongExact()
        for (d in WideIntDigits.digitsOf(v, w, n)) {
            assertTrue(d in 0 until radix, "digit $d escaped [0, $radix)")
        }
    }

    @Test
    fun `the width should keep a coefficient times a digit bound inside Long`() {
        // The rule that preserves refutation: max|coeff| * 2^width must not overflow.
        val limit = bigIntOf(Long.MAX_VALUE)
        for (c in listOf("1", "4096", "1000003", "1073741824")) {
            val coeff = big(c)
            val w = WideIntDigits.widthFor(coeff)
            assertTrue(coeff * WideIntDigits.pow2(w) <= limit, "coeff $c at width $w overflows Long")
        }
    }

    @Test
    fun `a coefficient with no room at all should be reported rather than truncated`() {
        assertEquals(WideIntDigits.NO_ROOM, WideIntDigits.widthFor(big("4611686018427387904")))
    }

    @Test
    fun `a larger coefficient should force narrower digits`() {
        val small = WideIntDigits.widthFor(big("2"))
        val large = WideIntDigits.widthFor(big("1000003"))
        assertTrue(large < small, "a bigger coefficient must leave less room, got $large vs $small")
    }

    @Test
    fun `zero should encode as a single zero digit`() {
        assertEquals(BIG_ZERO, WideIntDigits.recompose(WideIntDigits.digitsOf(BIG_ZERO, 16, 1), 16))
    }
}
