package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.eignex.klause.util.rem as remainderOf

class BigIntTest {

    private val twoTo64 = BIG_ONE shl 64

    @Test
    fun `division truncates toward zero`() {
        assertEquals(bigIntOf(-3), bigIntOf(-7) / bigIntOf(2))
        assertEquals(bigIntOf(-3), bigIntOf(7) / bigIntOf(-2))
        assertEquals(bigIntOf(3), bigIntOf(-7) / bigIntOf(-2))
    }

    @Test
    fun `remainder takes the sign of the dividend`() {
        // Outside its module BigInt is the platform type, whose own `rem` member would shadow `%` here.
        assertEquals(bigIntOf(-1), bigIntOf(-7).remainderOf(bigIntOf(2)))
        assertEquals(bigIntOf(1), bigIntOf(7).remainderOf(bigIntOf(-2)))
        assertEquals(bigIntOf(-1), bigIntOf(-7).remainderOf(bigIntOf(-2)))
        assertEquals(BIG_ZERO, bigIntOf(-6).remainderOf(bigIntOf(-2)))
    }

    @Test
    fun `division and remainder by zero throw`() {
        assertFailsWith<ArithmeticException> { BIG_ONE / BIG_ZERO }
        assertFailsWith<ArithmeticException> { BIG_ONE.remainderOf(BIG_ZERO) }
    }

    @Test
    fun `shr shifts the magnitude so a negative value rounds toward zero`() {
        assertEquals(bigIntOf(-2), bigIntOf(-5) shr 1)
        assertEquals(BIG_ZERO, bigIntOf(-1) shr 1)
        assertEquals(bigIntOf(-64), (-(BIG_ONE shl 70) - BIG_ONE) shr 64)
        assertEquals(bigIntOf(2), bigIntOf(5) shr 1)
    }

    @Test
    fun `shl multiplies by a power of two`() {
        assertEquals(-(twoTo64 + twoTo64 + twoTo64), bigIntOf(-3) shl 64)
    }

    @Test
    fun `magnitudeBitLength counts the bits of the absolute value`() {
        assertEquals(0, BIG_ZERO.magnitudeBitLength())
        assertEquals(1, bigIntOf(-1).magnitudeBitLength())
        assertEquals(3, bigIntOf(-4).magnitudeBitLength())
        assertEquals(3, bigIntOf(-5).magnitudeBitLength())
        assertEquals(64, bigIntOf(Long.MIN_VALUE).magnitudeBitLength())
        assertEquals(65, twoTo64.magnitudeBitLength())
    }

    @Test
    fun `toLong keeps the low 64 bits of the two's complement`() {
        assertEquals(5L, (twoTo64 + bigIntOf(5)).toLong())
        assertEquals(-5L, (-twoTo64 - bigIntOf(5)).toLong())
        assertEquals(Long.MIN_VALUE, (BIG_ONE shl 63).toLong())
    }

    @Test
    fun `toLongExact throws outside the Long range`() {
        assertEquals(Long.MIN_VALUE, bigIntOf(Long.MIN_VALUE).toLongExact())
        assertFailsWith<ArithmeticException> { (BIG_ONE shl 63).toLongExact() }
        assertFailsWith<ArithmeticException> { (bigIntOf(Long.MIN_VALUE) - BIG_ONE).toLongExact() }
    }

    @Test
    fun `fitsLong accepts exactly the Long range`() {
        assertTrue(bigIntOf(Long.MIN_VALUE).fitsLong())
        assertTrue(bigIntOf(Long.MAX_VALUE).fitsLong())
        assertFalse((bigIntOf(Long.MIN_VALUE) - BIG_ONE).fitsLong())
        assertFalse((bigIntOf(Long.MAX_VALUE) + BIG_ONE).fitsLong())
    }

    @Test
    fun `toDouble rounds half to even and overflows to infinity`() {
        val twoTo53 = BIG_ONE shl 53
        assertEquals(9007199254740992.0, (twoTo53 + BIG_ONE).toDouble())
        assertEquals(9007199254740996.0, (twoTo53 + bigIntOf(3)).toDouble())
        val tenTo400 = parseBigInt("1" + "0".repeat(400))
        assertEquals(Double.POSITIVE_INFINITY, tenTo400.toDouble())
        assertEquals(Double.NEGATIVE_INFINITY, (-tenTo400).toDouble())
    }

    @Test
    fun `toString renders plain decimal`() {
        assertEquals("0", BIG_ZERO.toString())
        assertEquals("-123", bigIntOf(-123).toString())
        assertEquals("18446744073709551616", twoTo64.toString())
    }

    @Test
    fun `parseBigInt reads an optional sign and ASCII digits`() {
        assertEquals(bigIntOf(5), parseBigInt("+5"))
        assertEquals(BIG_ZERO, parseBigInt("-0"))
        assertEquals(bigIntOf(7), parseBigInt("007"))
        assertEquals(-twoTo64, parseBigInt("-18446744073709551616"))
    }

    @Test
    fun `parseBigInt rejects every other form`() {
        for (text in listOf("", "-", "1.0", "1e3", " 1", "0x10", "\u0661")) {
            assertFailsWith<NumberFormatException>(text) { parseBigInt(text) }
        }
    }

    @Test
    fun `bigIntOf reads an unsigned value past the Long range`() {
        assertEquals(twoTo64 - BIG_ONE, bigIntOf(ULong.MAX_VALUE))
    }

    @Test
    fun `floorBigInt rounds toward negative infinity`() {
        assertEquals(bigIntOf(-3), floorBigInt(-2.5))
        assertEquals(BIG_TWO, floorBigInt(2.5))
        assertEquals(BIG_ZERO, floorBigInt(-0.0))
    }

    @Test
    fun `floorBigInt is exact past the Long range`() {
        val value = (BIG_ONE shl 70) + (BIG_ONE shl 18)
        assertEquals(value, floorBigInt(1180591620717411565568.0))
        assertEquals(-value, floorBigInt(-1180591620717411565568.0))
    }

    @Test
    fun `floorBigInt rejects a non-finite value`() {
        assertFailsWith<IllegalArgumentException> { floorBigInt(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { floorBigInt(Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun `gcd is non-negative and zero only for two zeros`() {
        assertEquals(bigIntOf(6), bigIntOf(-12).gcd(bigIntOf(18)))
        assertEquals(bigIntOf(5), BIG_ZERO.gcd(bigIntOf(-5)))
        assertEquals(BIG_ZERO, BIG_ZERO.gcd(BIG_ZERO))
    }

    @Test
    fun `equality and hashing are by value`() {
        val built = twoTo64 * BIG_TEN
        val parsed = parseBigInt("184467440737095516160")
        assertEquals(built, parsed)
        assertEquals(built.hashCode(), parsed.hashCode())
    }

    @Test
    fun `compareTo orders by value across signs and widths`() {
        assertTrue(-twoTo64 < bigIntOf(-1))
        assertTrue(bigIntOf(-1) < BIG_ZERO)
        assertTrue(bigIntOf(Long.MAX_VALUE) < twoTo64)
        assertEquals(0, twoTo64.compareTo(parseBigInt("18446744073709551616")))
    }

    @Test
    fun `sign queries follow the value`() {
        assertEquals(-1, bigIntOf(-9).signum())
        assertEquals(0, BIG_ZERO.signum())
        assertTrue(BIG_ZERO.isZero())
        assertEquals(bigIntOf(9), bigIntOf(-9).abs())
        assertEquals(bigIntOf(9), bigIntOf(-9).negate())
    }
}
