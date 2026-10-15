package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexedMaxHeapTest {

    @Test
    fun `extractMax should return ids in non-increasing key order`() {
        val h = IndexedMaxHeap(5)
        val keys = doubleArrayOf(3.0, 1.0, 4.0, 1.5, 9.0)
        for (id in keys.indices) h.insert(id, keys[id])
        assertEquals(5, h.size)
        val order = buildList { while (h.size > 0) add(h.extractMax()) }
        val expected = keys.indices.sortedByDescending { keys[it] }
        assertEquals(expected, order)
        assertEquals(-1, h.extractMax())
    }

    @Test
    fun `updateKey should move an entry to and from the max position`() {
        val h = IndexedMaxHeap(3)
        h.insert(0, 1.0)
        h.insert(1, 2.0)
        h.insert(2, 3.0)
        assertEquals(2, h.peekMax())
        h.updateKey(0, 10.0)
        assertEquals(0, h.peekMax())
        h.updateKey(0, 0.5)
        assertEquals(2, h.peekMax())
        assertEquals(0.5, h.keyOf(0))
    }

    @Test
    fun `restore should reinsert a removed entry at its cached key`() {
        val h = IndexedMaxHeap(3)
        h.insert(0, 5.0)
        h.insert(1, 8.0)
        h.insert(2, 2.0)
        h.remove(1)
        assertFalse(h.contains(1))
        assertEquals(0, h.peekMax())
        h.restore(1)
        assertTrue(h.contains(1))
        assertEquals(1, h.peekMax())
    }

    @Test
    fun `insert and updateKey should be no-ops on the wrong precondition`() {
        val h = IndexedMaxHeap(2)
        h.insert(0, 1.0)
        h.insert(0, 99.0)
        assertEquals(1.0, h.keyOf(0))
        h.updateKey(1, 5.0)
        assertFalse(h.contains(1))
    }

    @Test
    fun `scaleKeys should preserve order and reject non-positive factors`() {
        val h = IndexedMaxHeap(3)
        h.insert(0, 1.0)
        h.insert(1, 2.0)
        h.insert(2, 3.0)
        h.scaleKeys(10.0)
        assertEquals(30.0, h.keyOf(2))
        assertEquals(2, h.extractMax())
        assertEquals(1, h.extractMax())
        assertFailsWith<IllegalArgumentException> { h.scaleKeys(0.0) }
        assertFailsWith<IllegalArgumentException> { h.scaleKeys(-1.0) }
    }

    @Test
    fun `resetAllKeysInIdOrder should require a full heap and root the lowest id`() {
        val h = IndexedMaxHeap(4)
        for (id in 0 until 4) h.insert(id, id.toDouble())
        h.resetAllKeysInIdOrder(0.0)
        assertEquals(4, h.size)
        for (id in 0 until 4) assertTrue(h.contains(id))
        assertEquals(0, h.peekMax())
        h.extractMax()
        assertFailsWith<IllegalArgumentException> { h.resetAllKeysInIdOrder(1.0) }
    }
}
