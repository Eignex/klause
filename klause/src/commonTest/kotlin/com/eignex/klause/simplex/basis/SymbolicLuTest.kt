package com.eignex.klause.simplex.basis

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SymbolicLuTest {
    @Test
    fun `inverse permutations should map source coordinates to pivot coordinates`() {
        val symbolic = SymbolicLu(intArrayOf(2, 0, 3, 1), intArrayOf(1, 3, 0, 2))

        assertContentEquals(intArrayOf(1, 3, 0, 2), symbolic.rowPosition)
        assertContentEquals(intArrayOf(2, 0, 3, 1), symbolic.columnPosition)
    }

    @Test
    fun `moving and removing members should preserve bucket membership`() {
        val buckets = LuCountBuckets(4)
        buckets.move(0, 2)
        buckets.move(1, 2)
        buckets.move(2, 2)
        buckets.remove(1)
        buckets.move(0, 1)
        buckets.move(0, 1)

        assertEquals(2, buckets.first(2))
        assertEquals(-1, buckets.next(2))
        assertEquals(0, buckets.first(1))
        assertEquals(-1, buckets.next(0))

        buckets.remove(2)
        buckets.remove(2)
        buckets.move(2, 0)
        assertEquals(-1, buckets.first(2))
        assertEquals(2, buckets.first(0))
    }
}
