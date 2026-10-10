package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinarySearchTest {

    @Test
    fun `empty array returns insertion point 0`() {
        assertEquals(-1, IntArray(0).binarySearchInt(5))
    }

    @Test
    fun `found element returns its index`() {
        val a = intArrayOf(1, 3, 5, 7, 9)
        for (i in a.indices) assertEquals(i, a.binarySearchInt(a[i]))
    }

    @Test
    fun `absent element returns negative insertion point`() {
        val a = intArrayOf(1, 3, 5, 7, 9)
        assertEquals(-1, a.binarySearchInt(0))
        assertEquals(-2, a.binarySearchInt(2))
        assertEquals(-(5 + 1), a.binarySearchInt(10))
        val idx = a.binarySearchInt(4)
        assertTrue(idx < 0)
        assertEquals(2, -(idx + 1), "4 inserts between 3 and 5, at index 2")
    }

    @Test
    fun `respects fromIndex and toIndex sub-range`() {
        val a = intArrayOf(1, 3, 5, 7, 9)
        // Search only [1, 4) = {3,5,7}.
        assertEquals(2, a.binarySearchInt(5, fromIndex = 1, toIndex = 4))
        // 9 is outside the sub-range → insertion point is toIndex (4).
        assertEquals(-(4 + 1), a.binarySearchInt(9, fromIndex = 1, toIndex = 4))
        // 1 is outside the sub-range on the low side → insertion point is fromIndex (1).
        assertEquals(-(1 + 1), a.binarySearchInt(1, fromIndex = 1, toIndex = 4))
    }

    @Test
    fun `duplicates return some matching index`() {
        val a = intArrayOf(2, 2, 2, 2)
        val idx = a.binarySearchInt(2)
        assertTrue(idx in 0..3 && a[idx] == 2)
    }
}
