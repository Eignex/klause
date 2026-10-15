package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IntArrayDequeTest {

    @Test
    fun `addLast and removeFirst should preserve FIFO order across growth`() {
        val q = IntArrayDeque(initialCapacity = 4)
        for (i in 0 until 17) q.addLast(i)
        val out = IntArray(17) { q.removeFirst() }
        assertTrue(q.isEmpty())
        for (i in 0 until 17) assertEquals(i, out[i])
    }

    @Test
    fun `addFirst should yield LIFO order`() {
        val q = IntArrayDeque()
        for (i in 0 until 5) q.addFirst(i)
        val out = IntArray(5) { q.removeFirst() }
        assertEquals(listOf(4, 3, 2, 1, 0), out.toList())
    }

    @Test
    fun `removeFirstOr should return the sentinel when empty`() {
        val q = IntArrayDeque()
        assertEquals(-1, q.removeFirstOr(-1))
        q.addLast(42)
        assertEquals(42, q.removeFirstOr(-1))
        assertEquals(-1, q.removeFirstOr(-1))
    }

    @Test
    fun `removeFirst should throw when empty`() {
        val q = IntArrayDeque()
        assertFailsWith<NoSuchElementException> { q.removeFirst() }
    }
}
