package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntArrayListTest {

    @Test
    fun `add should grow past initial capacity and preserve order`() {
        val list = IntArrayList(initialCapacity = 2)
        for (i in 0 until 100) list.add(i * 3)
        assertEquals(100, list.size)
        for (i in 0 until 100) assertEquals(i * 3, list[i])
        assertEquals(IntArray(100) { it * 3 }.toList(), list.toIntArray().toList())
    }

    @Test
    fun `bulk append should preserve the live prefix after truncation and growth`() {
        val list = IntArrayList(initialCapacity = 2)
        list.addAll(intArrayOf(1, 2, 3))
        list.truncateTo(1)
        list.addAll(intArrayOf())
        list.addAll(intArrayOf(4, 5, 6, 7))
        assertEquals(listOf(1, 4, 5, 6, 7), list.toIntArray().toList())
    }

    @Test
    fun `removeAt should swap the tail into the gap`() {
        val list = IntArrayList()
        for (v in intArrayOf(10, 20, 30, 40)) list.add(v)
        list.removeAt(1)
        assertEquals(3, list.size)
        assertEquals(listOf(10, 40, 30), list.toIntArray().toList())
    }

    @Test
    fun `removeValue should swap-remove the first occurrence and report presence`() {
        val list = IntArrayList()
        for (v in intArrayOf(5, 7, 5, 9)) list.add(v)
        assertTrue(list.removeValue(5))
        assertEquals(3, list.size)
        assertEquals(listOf(9, 7, 5), list.toIntArray().toList())
        assertFalse(list.removeValue(42))
        assertEquals(3, list.size)
    }

    @Test
    fun `truncateTo should drop the suffix and be a no-op when not shrinking`() {
        val list = IntArrayList()
        for (v in 0 until 6) list.add(v)
        list.truncateTo(3)
        assertEquals(listOf(0, 1, 2), list.toIntArray().toList())
        list.truncateTo(10)
        assertEquals(3, list.size)
    }

    @Test
    fun `indexOf contains last and toSet should reflect the live elements`() {
        val list = IntArrayList()
        for (v in intArrayOf(4, 4, 8)) list.add(v)
        assertEquals(0, list.indexOf(4))
        assertEquals(-1, list.indexOf(99))
        assertTrue(list.contains(8))
        assertFalse(list.contains(99))
        assertEquals(8, list.last())
        assertEquals(setOf(4, 8), list.toSet())
    }

    @Test
    fun `sortByIntKey should order by key ascending and descending with negative values`() {
        val list = IntArrayList()
        for (v in intArrayOf(-3, 7, 0, -10, 4)) list.add(v)
        list.sortByIntKey { it }
        assertEquals(listOf(-10, -3, 0, 4, 7), list.toIntArray().toList())
        list.sortByIntKey(descending = true) { it }
        assertEquals(listOf(7, 4, 0, -3, -10), list.toIntArray().toList())
    }

    @Test
    fun `sortByIntKey should sort by an external key and break ties by element order`() {
        val weights = intArrayOf(10, 5, 10, 5, 7)
        val list = IntArrayList()
        for (id in 0 until 5) list.add(id)
        list.sortByIntKey { weights[it] }
        assertEquals(listOf(1, 3, 4, 0, 2), list.toIntArray().toList())
        list.sortByIntKey(descending = true) { weights[it] }
        assertEquals(listOf(2, 0, 4, 3, 1), list.toIntArray().toList())
    }

    @Test
    fun `lowerBound should return the first index not below the element`() {
        val ascending = listOfInts(2, 4, 6, 9, 13, 15)
        for (k in -2..18) {
            val expected = (0 until ascending.size).firstOrNull { ascending[it] >= k } ?: ascending.size
            assertEquals(expected, ascending.lowerBound(k), "lowerBound($k)")
        }
        assertEquals(0, IntArrayList().lowerBound(5))
    }

    @Test
    fun `lowerBoundDescending should return the first index not above the element`() {
        val descending = listOfInts(15, 9, 9, 9, 6, 4, 4, 2)
        for (k in -2..18) {
            val expected = (0 until descending.size).firstOrNull { descending[it] <= k } ?: descending.size
            assertEquals(expected, descending.lowerBoundDescending(k), "lowerBoundDescending($k)")
        }
        assertEquals(0, IntArrayList().lowerBoundDescending(5))
    }

    @Test
    fun `lowerBound should respect the live size not the backing capacity`() {
        val list = listOfInts(1, 3, 5, 7, 9)
        list.truncateTo(3)
        assertEquals(3, list.lowerBound(6))
        assertEquals(2, list.lowerBound(5))
    }

    private fun listOfInts(vararg v: Int): IntArrayList = IntArrayList().apply { addAll(v) }
}
