package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutableIntDoubleMapTest {

    @Test
    fun `addTo increments from zero default and returns new value`() {
        val m = MutableIntDoubleMap()
        assertEquals(1.5, m.addTo(7, 1.5))
        assertEquals(4.0, m.addTo(7, 2.5))
        assertEquals(1, m.size)
        assertEquals(4.0, m.getOrDefault(7, 0.0))
    }

    @Test
    fun `extreme keys and zero coexist`() {
        val m = MutableIntDoubleMap()
        m.put(Int.MIN_VALUE, 1.0)
        m.put(Int.MAX_VALUE, 2.0)
        m.put(0, 3.0)
        assertEquals(1.0, m.getOrDefault(Int.MIN_VALUE, -1.0))
        assertEquals(2.0, m.getOrDefault(Int.MAX_VALUE, -1.0))
        assertEquals(3.0, m.getOrDefault(0, -1.0))
        assertEquals(3, m.size)
    }

    @Test
    fun `remove deletes and leaves other entries findable across collisions`() {
        val m = MutableIntDoubleMap(4)
        for (k in 0 until 50) m.put(k, k.toDouble())
        for (k in 0 until 50 step 3) assertTrue(m.remove(k))
        for (k in 0 until 50) {
            if (k % 3 == 0) {
                assertFalse(m.containsKey(k), "removed key $k")
            } else {
                assertEquals(k.toDouble(), m.getOrDefault(k, -1.0), "survivor key $k")
            }
        }
        assertFalse(m.remove(0))
    }

    @Test
    fun `clear empties and the map is reusable`() {
        val m = MutableIntDoubleMap()
        for (k in 0 until 30) m.put(k, k.toDouble())
        m.clear()
        assertEquals(0, m.size)
        m.put(99, 7.0)
        assertEquals(7.0, m.getOrDefault(99, -1.0))
        assertEquals(1, m.size)
    }

    @Test
    fun `growth preserves all entries`() {
        val m = MutableIntDoubleMap(8)
        for (k in 0 until 1000) m.put(k * 7, k.toDouble())
        assertEquals(1000, m.size)
        for (k in 0 until 1000) assertEquals(k.toDouble(), m.getOrDefault(k * 7, -1.0))
    }

}
