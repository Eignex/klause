package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinarySearchTest {

    @Test
    fun `binarySearchInt should return insertion point 0 on an empty array`() {
        assertEquals(-1, IntArray(0).binarySearchInt(5))
    }

    @Test
    fun `binarySearchInt should return the index of a found element`() {
        val a = intArrayOf(1, 3, 5, 7, 9)
        for (i in a.indices) assertEquals(i, a.binarySearchInt(a[i]))
    }

    @Test
    fun `binarySearchInt should return a negative insertion point for an absent element`() {
        val a = intArrayOf(1, 3, 5, 7, 9)
        assertEquals(-1, a.binarySearchInt(0))
        assertEquals(-2, a.binarySearchInt(2))
        assertEquals(-(5 + 1), a.binarySearchInt(10))
        val idx = a.binarySearchInt(4)
        assertTrue(idx < 0)
        assertEquals(2, -(idx + 1))
    }

    @Test
    fun `binarySearchInt should respect the fromIndex and toIndex sub-range`() {
        val a = intArrayOf(1, 3, 5, 7, 9)
        assertEquals(2, a.binarySearchInt(5, fromIndex = 1, toIndex = 4))
        assertEquals(-(4 + 1), a.binarySearchInt(9, fromIndex = 1, toIndex = 4))
        assertEquals(-(1 + 1), a.binarySearchInt(1, fromIndex = 1, toIndex = 4))
    }

    @Test
    fun `binarySearchInt should return some matching index among duplicates`() {
        val a = intArrayOf(2, 2, 2, 2)
        val idx = a.binarySearchInt(2)
        assertTrue(idx in 0..3 && a[idx] == 2)
    }
}
