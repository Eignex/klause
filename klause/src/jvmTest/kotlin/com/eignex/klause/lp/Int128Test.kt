package com.eignex.klause.lp

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The portable 128-bit accumulator must agree exactly with an arbitrary-precision oracle across
 * add / multiply / shift / floor- and ceil-div, including the signed edge cases, and must latch
 * [Int128.overflow] rather than wrap. The oracle here is [java.math.BigInteger]; [Int128] is pure
 * platform-independent Kotlin (no `expect`/`actual`), so JVM coverage exercises every target's logic.
 */
class Int128Test {

    private val two64: BigInteger = BigInteger.ONE.shiftLeft(64)
    private val longMin = BigInteger.valueOf(Long.MIN_VALUE)
    private val longMax = BigInteger.valueOf(Long.MAX_VALUE)

    /** The exact value an [Int128] represents: `hi · 2⁶⁴ + (lo as unsigned)`. */
    private fun Int128.toBig(): BigInteger {
        val loUnsigned = if (lo >= 0L) BigInteger.valueOf(lo) else BigInteger.valueOf(lo) + two64
        return BigInteger.valueOf(hi) * two64 + loUnsigned
    }

    /** The value as a `Long`, or null when it does not fit (mirrors [Int128.toLong]'s domain). */
    private fun BigInteger.toLongOrNull(): Long? = if (this in longMin..longMax) toLong() else null

    private fun big(v: Long) = BigInteger.valueOf(v)

    private val edge = longArrayOf(
        0L, 1L, -1L, 2L, -2L, 100L, -100L,
        Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1,
        1L shl 31, -(1L shl 31), 1L shl 62, -(1L shl 62), (1L shl 32) - 1,
    )

    @Test
    fun `addProduct matches the oracle over edge and random operands`() {
        val rng = Random(20260622)
        repeat(4000) {
            val a = if (it < edge.size * edge.size) edge[it / edge.size] else rng.nextLong()
            val b = if (it < edge.size * edge.size) edge[it % edge.size] else rng.nextLong()
            val acc = Int128()
            acc.addProduct(a, b)
            assertFalse(acc.overflow, "single product $a*$b should not overflow 128 bits")
            assertEquals(big(a) * big(b), acc.toBig(), "addProduct($a, $b)")
        }
    }

    @Test
    fun `addLong, addProduct, subtract accumulate exactly`() {
        val rng = Random(7)
        repeat(500) {
            val acc = Int128()
            var oracle = BigInteger.ZERO
            repeat(rng.nextInt(1, 40)) {
                when (rng.nextInt(3)) {
                    0 -> {
                        val v = rng.nextLong()
                        acc.addLong(v)
                        oracle += big(v)
                    }

                    1 -> {
                        val a = rng.nextLong(-(1L shl 40), 1L shl 40)
                        val b = rng.nextLong(-(1L shl 40), 1L shl 40)
                        acc.addProduct(a, b)
                        oracle += big(a) * big(b)
                    }

                    else -> {
                        val other = Int128()
                        val v = rng.nextLong(-(1L shl 60), 1L shl 60)
                        other.addLong(v)
                        acc.subtract(other)
                        oracle -= big(v)
                    }
                }
            }
            assertFalse(acc.overflow)
            assertEquals(oracle, acc.toBig(), "accumulated value")
            assertEquals(oracle.toLongOrNull() != null, acc.fitsLong(), "fitsLong agreement")
            assertEquals(oracle.signum() >= 0, acc.isNonNegative(), "isNonNegative agreement")
            if (acc.fitsLong()) assertEquals(oracle.toLongOrNull(), acc.toLong())
        }
    }

    @Test
    fun `ceilDivPow2 matches the oracle`() {
        val rng = Random(99)
        for (k in intArrayOf(0, 1, 5, 20, 40, 62)) {
            repeat(800) {
                val acc = Int128()
                repeat(rng.nextInt(1, 12)) {
                    acc.addProduct(rng.nextLong(-(1L shl 50), 1L shl 50), rng.nextLong(-1024, 1024))
                }
                val value = acc.toBig()
                val d = BigInteger.ONE.shiftLeft(k)
                val (q, r) = value.divideAndRemainder(d)
                val expected = if (r.signum() > 0) q + BigInteger.ONE else q // ceil for positive divisor
                assertEquals(expected.toLongOrNull(), acc.ceilDivPow2(k), "ceil($value / 2^$k)")
            }
        }
    }

    @Test
    fun `floorDivPositive matches the oracle`() {
        val rng = Random(2024)
        val divisors = longArrayOf(1L, 2L, 3L, 7L, 1024L, 1L shl 20, 1L shl 40, (1L shl 52) - 1, Long.MAX_VALUE)
        for (d in divisors) {
            val dBig = big(d)
            repeat(700) {
                val acc = Int128()
                repeat(rng.nextInt(1, 10)) {
                    acc.addProduct(rng.nextLong(-(1L shl 50), 1L shl 50), rng.nextLong(-4096, 4096))
                }
                val value = acc.toBig()
                val (q, r) = value.divideAndRemainder(dBig)
                val floor = if (r.signum() < 0) q - BigInteger.ONE else q // truncation → floor for d > 0
                assertEquals(floor.toLongOrNull(), acc.floorDivPositive(d), "floor($value / $d)")
            }
        }
    }

    @Test
    fun `floorDivPositive fast path matches the oracle on Long-range dividends`() {
        // The fitsLong fast path replaces the 128-bit loop with native floored division; pin its edges
        // (Long extremes, negatives with a remainder) against the BigInteger oracle. fitsLong holds for
        // every value here, so this exercises only the fast path.
        val dividends = longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 1L, -7L, 7L, -1024L, 123_456_789L)
        val divisors = longArrayOf(1L, 2L, 3L, 7L, 1024L, Long.MAX_VALUE)
        for (value in dividends) {
            for (d in divisors) {
                val acc = Int128().apply { addProduct(value, 1L) }
                assertTrue(acc.fitsLong(), "$value must fit a Long")
                val (q, r) = big(value).divideAndRemainder(big(d))
                val floor = if (r.signum() < 0) q - BigInteger.ONE else q
                assertEquals(floor.toLongOrNull(), acc.floorDivPositive(d), "floor($value / $d)")
            }
        }
    }

    @Test
    fun `shiftLeft matches the oracle times a power of two`() {
        // Bounded inputs (< 2⁵³) shifted by ≤ 62 stay < 2¹¹⁵, so no shift overflows here; this exercises
        // the value path exactly. (Overflow simply latches → ceilDivPow2 yields null → sound fallback.)
        val rng = Random(7)
        for (bits in intArrayOf(0, 1, 5, 20, 40, 62)) {
            repeat(700) {
                val acc = Int128()
                repeat(rng.nextInt(1, 8)) {
                    acc.addProduct(rng.nextLong(-(1L shl 40), 1L shl 40), rng.nextLong(-1024, 1024))
                }
                val expected = acc.toBig().shiftLeft(bits)
                acc.shiftLeft(bits)
                assertFalse(acc.overflow, "bounded value must not overflow on << $bits")
                assertEquals(expected, acc.toBig(), "value << $bits")
            }
        }
    }

    @Test
    fun `overflow latches instead of wrapping`() {
        val acc = Int128()
        // 2^126 + 2^126 + 2^126 + 2^126 = 2^128 overflows the signed 128-bit range.
        repeat(4) { acc.addProduct(1L shl 63, 1L shl 63) }
        assertTrue(acc.overflow, "accumulating past 2^127 must latch overflow")
        assertNull(acc.ceilDivPow2(0), "an overflowed accumulator yields null")
        assertFalse(acc.fitsLong())
        assertFalse(acc.isNonNegative())
    }
}
