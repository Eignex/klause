package com.eignex.klause.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BigIntTest {

    @Test
    fun `arithmetic matches long semantics inside long range`() {
        val rng = Random(3)
        repeat(500) {
            val a = rng.nextLong(-1_000_000_000L, 1_000_000_000L)
            val b = rng.nextLong(-1_000_000_000L, 1_000_000_000L)
            val ba = BigInt.fromLong(a)
            val bb = BigInt.fromLong(b)
            assertEquals(a + b, (ba + bb).toLongOrNull())
            assertEquals(a - b, (ba - bb).toLongOrNull())
            assertEquals(a * b, (ba * bb).toLongOrNull())
            if (b != 0L) {
                val (q, r) = ba.divRem(bb)
                assertEquals(a / b, q.toLongOrNull())
                assertEquals(a % b, r.toLongOrNull())
            }
        }
    }

    @Test
    fun `products past long range round-trip through decimal rendering`() {
        val a = BigInt.fromLong(Long.MAX_VALUE)
        val square = a * a
        assertNull(square.toLongOrNull())
        assertEquals("85070591730234615847396907784232501249", square.toString())
    }

    @Test
    fun `long min value converts and negates exactly`() {
        val min = BigInt.fromLong(Long.MIN_VALUE)
        assertEquals(Long.MIN_VALUE, min.toLongOrNull())
        assertNull((-min).toLongOrNull())
        assertEquals("9223372036854775808", (-min).toString())
    }

    @Test
    fun `gcd reduces shared factors`() {
        val g = BigInt.fromLong(30L).gcd(BigInt.fromLong(-42L))
        assertEquals(6L, g.toLongOrNull())
        assertEquals(7L, BigInt.fromLong(7L).gcd(BigInt.ZERO).toLongOrNull())
    }
}
