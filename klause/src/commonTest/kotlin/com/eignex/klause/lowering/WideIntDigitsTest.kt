package com.eignex.klause.lowering

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.toLongExact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The positional encoding behind wide-integer support. Round-tripping is the obvious property; the one
 * that actually decides whether refutation survives is the width rule, since a coefficient times a digit
 * bound must never leave `Long` — that overflow is what silently costs the solver its bound reasoning.
 */
class WideIntDigitsTest {

    private fun big(s: String) = parseBigInt(s)

    @Test
    fun `digits round-trip a value past Long and the digit count covers its magnitude`() {
        val v = big("18446744073709551616") // 2^64
        val w = 32
        val n = WideIntDigits.digitCount(v, w)
        assertTrue(n >= 3, "2^64 needs at least three 32-bit digits, got $n")
        assertEquals(v, WideIntDigits.recompose(WideIntDigits.digitsOf(v, w, n), w))
    }

    @Test
    fun `every digit stays inside its width`() {
        val v = big("123456789012345678901234567890")
        val w = 20
        val n = WideIntDigits.digitCount(v, w)
        val radix = WideIntDigits.pow2(w).toLongExact()
        for (d in WideIntDigits.digitsOf(v, w, n)) {
            assertTrue(d in 0 until radix, "digit $d escaped [0, $radix)")
        }
    }

    @Test
    fun `the width keeps a coefficient times a digit bound inside Long`() {
        // The rule that preserves refutation: max|coeff| * 2^width must not overflow.
        val limit = bigIntOf(Long.MAX_VALUE)
        for (c in listOf("1", "4096", "1000003", "1073741824")) {
            val coeff = big(c)
            val w = WideIntDigits.widthFor(coeff)
            assertTrue(coeff * WideIntDigits.pow2(w) <= limit, "coeff $c at width $w overflows Long")
        }
    }

    @Test
    fun `a coefficient with no room at all is reported rather than truncated`() {
        // 2^62 leaves no digit width whose product fits; decomposing the variable cannot fix a
        // coefficient that is itself out of range, so the caller must be told instead of handed a
        // width that still wraps.
        assertEquals(WideIntDigits.NO_ROOM, WideIntDigits.widthFor(big("4611686018427387904")))
    }

    @Test
    fun `a unit coefficient gets the widest digits`() {
        assertEquals(62, WideIntDigits.widthFor(BIG_ONE), "nothing to multiply against")
    }

    @Test
    fun `a larger coefficient forces narrower digits`() {
        val small = WideIntDigits.widthFor(big("2"))
        val large = WideIntDigits.widthFor(big("1000003"))
        assertTrue(large < small, "a bigger coefficient must leave less room, got $large vs $small")
    }

    @Test
    fun `zero encodes as a single zero digit`() {
        assertEquals(BIG_ZERO, WideIntDigits.recompose(WideIntDigits.digitsOf(BIG_ZERO, 16, 1), 16))
    }
}
