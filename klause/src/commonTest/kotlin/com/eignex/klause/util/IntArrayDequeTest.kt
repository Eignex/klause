package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntArrayDequeTest {

    @Test
    fun `addLast removeFirst preserves FIFO order across grow`() {
        val q = IntArrayDeque(initialCapacity = 4)
        for (i in 0 until 17) q.addLast(i) // exercises at least two grow events
        val out = IntArray(17) { q.removeFirst() }
        assertTrue(q.isEmpty())
        for (i in 0 until 17) assertEquals(i, out[i])
    }

    @Test
    fun `addFirst yields LIFO order`() {
        val q = IntArrayDeque()
        for (i in 0 until 5) q.addFirst(i)
        val out = IntArray(5) { q.removeFirst() }
        assertEquals(listOf(4, 3, 2, 1, 0), out.toList())
    }

    @Test
    fun `removeFirstOr sentinel returns sentinel when empty`() {
        val q = IntArrayDeque()
        assertEquals(-1, q.removeFirstOr(-1))
        q.addLast(42)
        assertEquals(42, q.removeFirstOr(-1))
        assertEquals(-1, q.removeFirstOr(-1))
    }

    @Test
    fun `removeFirst on empty throws`() {
        val q = IntArrayDeque()
        assertFailsWith<NoSuchElementException> { q.removeFirst() }
    }

    @Test
    fun `clear empties the buffer`() {
        val q = IntArrayDeque()
        for (i in 0 until 10) q.addLast(i)
        q.clear()
        assertEquals(0, q.size)
        assertTrue(q.isEmpty())
        assertFalse(q.isNotEmpty())
    }
}
