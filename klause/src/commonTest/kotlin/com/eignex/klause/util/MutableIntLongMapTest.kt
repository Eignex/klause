package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutableIntLongMapTest {

    @Test
    fun `addTo increments from zero default and returns new value`() {
        val m = MutableIntLongMap()
        assertEquals(1L, m.addTo(7, 1L))
        assertEquals(3L, m.addTo(7, 2L))
        assertEquals(1, m.size)
        assertEquals(3L, m.getOrDefault(7, 0L))
    }

    @Test
    fun `extreme keys and zero coexist`() {
        val m = MutableIntLongMap()
        m.put(Int.MIN_VALUE, 1L)
        m.put(Int.MAX_VALUE, 2L)
        m.put(0, 3L)
        assertEquals(1L, m.getOrDefault(Int.MIN_VALUE, -1L))
        assertEquals(2L, m.getOrDefault(Int.MAX_VALUE, -1L))
        assertEquals(3L, m.getOrDefault(0, -1L))
        assertEquals(3, m.size)
    }

    @Test
    fun `remove deletes and leaves other entries findable across collisions`() {
        val m = MutableIntLongMap(4)
        for (k in 0 until 50) m.put(k, k.toLong())
        for (k in 0 until 50 step 3) assertTrue(m.remove(k))
        for (k in 0 until 50) {
            if (k % 3 == 0) {
                assertFalse(m.containsKey(k), "removed key $k")
            } else {
                assertEquals(k.toLong(), m.getOrDefault(k, -1L), "survivor key $k")
            }
        }
        assertFalse(m.remove(0))
    }

    @Test
    fun `clear empties and the map is reusable`() {
        val m = MutableIntLongMap()
        for (k in 0 until 30) m.put(k, k.toLong())
        m.clear()
        assertEquals(0, m.size)
        m.put(99, 7L)
        assertEquals(7L, m.getOrDefault(99, -1L))
        assertEquals(1, m.size)
    }

    @Test
    fun `growth preserves all entries`() {
        val m = MutableIntLongMap(8)
        for (k in 0 until 1000) m.put(k * 7, k.toLong())
        assertEquals(1000, m.size)
        for (k in 0 until 1000) assertEquals(k.toLong(), m.getOrDefault(k * 7, -1L))
    }

}
