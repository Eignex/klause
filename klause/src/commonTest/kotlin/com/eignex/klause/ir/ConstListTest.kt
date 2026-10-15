package com.eignex.klause.ir

import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class ConstListTest {

    @Test
    fun `constsOf should use unit storage for all-one constants`() {
        val consts = constsOf(longArrayOf(1, 1, 1, 1))

        assertIs<UnitConsts>(consts)
        assertEquals(4, consts.size)
        assertEquals(1L, consts.at(3))
    }

    @Test
    fun `constsOf should store 32-bit constants narrow`() {
        val consts = constsOf(longArrayOf(3, -7, 1))

        assertIs<IntConsts>(consts)
        assertEquals(listOf(3L, -7L, 1L), consts.toLongArray().toList())
        assertEquals(7L, consts.maxAbs)
    }

    @Test
    fun `constsOf should keep Long width past the 32-bit range`() {
        val consts = constsOf(longArrayOf(1, Int.MAX_VALUE.toLong() + 1))

        assertIs<LongConsts>(consts)
        assertEquals(Int.MAX_VALUE.toLong() + 1, consts.at(1))
    }

    @Test
    fun `negated should widen instead of wrapping at Int MIN_VALUE`() {
        val negated = constsOf(longArrayOf(Int.MIN_VALUE.toLong())).negated()

        assertEquals(-Int.MIN_VALUE.toLong(), negated.at(0))
    }

    @Test
    fun `wide constants should have no Long reading`() {
        val wide = WideConsts(arrayOf(bigIntOf(Long.MAX_VALUE) * bigIntOf(4)))

        assertNull(wide.longsOrNull())
        assertEquals(bigIntOf(Long.MAX_VALUE) * bigIntOf(4), wide.at(0))
    }

    @Test
    fun `real constants should have no Long reading`() {
        val real = RealConsts(doubleArrayOf(0.5))

        assertNull(real.longsOrNull())
        assertEquals(0.5, real.at(0))
    }

    @Test
    fun `maxAbs should saturate at Long MIN_VALUE`() {
        val consts = constsOf(longArrayOf(Long.MIN_VALUE))

        assertEquals(Long.MAX_VALUE, consts.maxAbs)
    }

    @Test
    fun `at should reject out of range indices for every width`() {
        assertFailsWith<IndexOutOfBoundsException> { UnitConsts(3).at(3) }
        assertFailsWith<IndexOutOfBoundsException> { UnitConsts(3).at(-1) }
        assertFailsWith<IndexOutOfBoundsException> { constsOf(intArrayOf(2, 3)).at(2) }
        assertFailsWith<IndexOutOfBoundsException> { constsOf(longArrayOf(9_000_000_000L, 2L)).at(2) }
    }
}
