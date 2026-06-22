package com.eignex.klause.solver.lp

import com.eignex.klause.util.BigInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The portable 128-bit accumulator must agree exactly with the [BigInt] oracle across add / multiply /
 *  ceil-div, including the signed edge cases, and must latch [Int128.overflow] rather than wrap. */
class Int128Test {

    /** 2⁶⁴ as a [BigInt], for reconstructing the true value of an [Int128] from its (hi, lo) words. */
    private val two64: BigInt = BigInt.of(1L shl 32) * BigInt.of(1L shl 32)

    /** The exact value an [Int128] represents: `hi · 2⁶⁴ + (lo as unsigned)`. */
    private fun Int128.toBigInt(): BigInt {
        val loUnsigned = if (lo >= 0L) BigInt.of(lo) else BigInt.of(lo) + two64
        return BigInt.of(hi) * two64 + loUnsigned
    }

    private val edge = longArrayOf(
        0L, 1L, -1L, 2L, -2L, 100L, -100L,
        Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1,
        1L shl 31, -(1L shl 31), 1L shl 62, -(1L shl 62), (1L shl 32) - 1,
    )

    @Test
    fun `addProduct matches BigInt over edge and random operands`() {
        val rng = Random(20260622)
        repeat(4000) {
            val a = if (it < edge.size * edge.size) edge[it / edge.size] else rng.nextLong()
            val b = if (it < edge.size * edge.size) edge[it % edge.size] else rng.nextLong()
            val acc = Int128()
            acc.addProduct(a, b)
            assertFalse(acc.overflow, "single product $a*$b should not overflow 128 bits")
            assertEquals(BigInt.of(a) * BigInt.of(b), acc.toBigInt(), "addProduct($a, $b)")
        }
    }

    @Test
    fun `addLong and addProduct accumulate exactly`() {
        val rng = Random(7)
        repeat(500) {
            val acc = Int128()
            var oracle = BigInt.ZERO
            repeat(rng.nextInt(1, 40)) {
                if (rng.nextBoolean()) {
                    val v = rng.nextLong()
                    acc.addLong(v)
                    oracle += BigInt.of(v)
                } else {
                    val a = rng.nextLong(-(1L shl 40), 1L shl 40)
                    val b = rng.nextLong(-(1L shl 40), 1L shl 40)
                    acc.addProduct(a, b)
                    oracle += BigInt.of(a) * BigInt.of(b)
                }
            }
            assertFalse(acc.overflow)
            assertEquals(oracle, acc.toBigInt(), "accumulated value")
            assertEquals(oracle.toLongOrNull() != null, acc.fitsLong(), "fitsLong agreement")
            if (acc.fitsLong()) assertEquals(oracle.toLongOrNull(), acc.toLong())
        }
    }

    @Test
    fun `ceilDivPow2 matches BigInt ceil division`() {
        val rng = Random(99)
        for (k in intArrayOf(0, 1, 5, 20, 40, 62)) {
            val d = BigInt.of(1L shl k)
            repeat(800) {
                val acc = Int128()
                var oracle = BigInt.ZERO
                repeat(rng.nextInt(1, 12)) {
                    val a = rng.nextLong(-(1L shl 50), 1L shl 50)
                    acc.addProduct(a, rng.nextLong(-1024, 1024))
                    oracle += BigInt.of(a) * BigInt.of(rng.nextLong(-1024, 1024))
                }
                // Recompute the oracle from the SAME running value the accumulator holds.
                oracle = acc.toBigInt()
                val (q, r) = oracle.divideAndRemainder(d)
                val expected = if (r.signum() > 0) q + BigInt.ONE else q // ceil for positive divisor
                assertEquals(expected.toLongOrNull(), acc.ceilDivPow2(k), "ceil($oracle / 2^$k)")
            }
        }
    }

    @Test
    fun `floorDivPositive matches BigInt floor division`() {
        val rng = Random(2024)
        val divisors = longArrayOf(1L, 2L, 3L, 7L, 1024L, 1L shl 20, 1L shl 40, (1L shl 52) - 1, Long.MAX_VALUE)
        for (d in divisors) {
            val dBig = BigInt.of(d)
            repeat(700) {
                val acc = Int128()
                repeat(rng.nextInt(1, 10)) {
                    acc.addProduct(rng.nextLong(-(1L shl 50), 1L shl 50), rng.nextLong(-4096, 4096))
                }
                val value = acc.toBigInt()
                val (q, r) = value.divideAndRemainder(dBig)
                val floor = if (r.signum() < 0) q - BigInt.ONE else q // truncation → floor for d > 0
                assertEquals(floor.toLongOrNull(), acc.floorDivPositive(d), "floor($value / $d)")
            }
        }
    }

    @Test
    fun `shiftLeft matches BigInt times power of two`() {
        // Bounded inputs (< 2⁵³) shifted by ≤ 62 stay < 2¹¹⁵, so no shift overflows here; this exercises
        // the value path exactly. (Overflow simply latches → ceilDivPow2 yields null → sound fallback.)
        val rng = Random(7)
        for (bits in intArrayOf(0, 1, 5, 20, 40, 62)) {
            val mul = BigInt.of(1L shl bits)
            repeat(700) {
                val acc = Int128()
                repeat(rng.nextInt(1, 8)) {
                    acc.addProduct(rng.nextLong(-(1L shl 40), 1L shl 40), rng.nextLong(-1024, 1024))
                }
                val expected = acc.toBigInt() * mul
                acc.shiftLeft(bits)
                assertFalse(acc.overflow, "bounded value must not overflow on << $bits")
                assertEquals(expected, acc.toBigInt(), "value << $bits")
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
    }
}
