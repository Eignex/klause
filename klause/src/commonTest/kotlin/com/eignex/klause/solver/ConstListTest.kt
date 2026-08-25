package com.eignex.klause.solver

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Constants are stored at their true width, and a consumer that reasons in 64-bit integers gets the
 * narrowing or nothing.
 */
class ConstListTest {

    @Test
    fun `all-one constants keep no per-term storage`() {
        val consts = constsOf(longArrayOf(1, 1, 1, 1))

        assertIs<UnitConsts>(consts)
        assertEquals(4, consts.size)
        assertEquals(1L, consts.at(3))
    }

    @Test
    fun `constants within the 32-bit range are stored narrow`() {
        val consts = constsOf(longArrayOf(3, -7, 1))

        assertIs<IntConsts>(consts)
        assertEquals(listOf(3L, -7L, 1L), consts.toLongArray().toList())
        assertEquals(7L, consts.maxAbs)
    }

    @Test
    fun `a constant past the 32-bit range keeps the full width`() {
        val consts = constsOf(longArrayOf(1, Int.MAX_VALUE.toLong() + 1))

        assertIs<LongConsts>(consts)
        assertEquals(Int.MAX_VALUE.toLong() + 1, consts.at(1))
    }

    @Test
    fun `negating widens rather than wrapping at the narrow bound`() {
        val negated = constsOf(longArrayOf(Int.MIN_VALUE.toLong())).negated()

        assertEquals(-Int.MIN_VALUE.toLong(), negated.at(0))
    }

    @Test
    fun `constants beyond 64 bits do not read as integers`() {
        val wide = WideConsts(arrayOf(BigInteger.fromLong(Long.MAX_VALUE) * 4))

        assertNull(wide.longsOrNull(), "an over-64-bit constant has no Long reading")
        assertEquals(BigInteger.fromLong(Long.MAX_VALUE) * 4, wide.at(0))
    }

    @Test
    fun `continuous constants do not read as integers`() {
        val real = RealConsts(doubleArrayOf(0.5))

        assertNull(real.longsOrNull(), "a fractional constant has no Long reading")
        assertEquals(0.5, real.at(0))
    }

    @Test
    fun `maxAbs saturates rather than wrapping at the widest negative constant`() {
        val consts = constsOf(longArrayOf(Long.MIN_VALUE))

        assertEquals(Long.MAX_VALUE, consts.maxAbs)
    }
}
