package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IntArrayDequeTest {

    @Test
    fun `indexed removal should preserve pending order across ring wrap`() {
        val q = IntArrayDeque(initialCapacity = 4)
        repeat(4) { q.addLast(it) }
        q.removeFirst()
        q.removeFirst()
        q.addLast(4)
        q.addLast(5)

        assertEquals(4, q.removeAt(2))

        assertEquals(listOf(2, 3, 5), List(3) { q.removeFirst() })
    }

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
