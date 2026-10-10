package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutableIntIntMapTest {

    @Test
    fun `put overwrites existing key without growing size`() {
        val m = MutableIntIntMap()
        m.put(5, 1)
        m.put(5, 2)
        m.put(5, 3)
        assertEquals(3, m.getOrDefault(5, -1))
        assertEquals(1, m.size)
    }

    @Test
    fun `addTo increments from zero default and returns new value`() {
        val m = MutableIntIntMap()
        assertEquals(1, m.addTo(7, 1))
        assertEquals(3, m.addTo(7, 2))
        assertEquals(-1, m.addTo(7, -4))
        assertEquals(-1, m.getOrDefault(7, 0))
        assertEquals(1, m.size)
    }

    @Test
    fun `value equal to default is still reported present`() {
        val m = MutableIntIntMap()
        m.put(5, 0)
        assertEquals(0, m.getOrDefault(5, 0))
        assertTrue(m.containsKey(5), "present key must be reported even if its value == default")
    }

    @Test
    fun `extreme keys and zero coexist`() {
        val m = MutableIntIntMap()
        m.put(Int.MIN_VALUE, 1)
        m.put(Int.MAX_VALUE, 2)
        m.put(0, 3)
        assertEquals(1, m.getOrDefault(Int.MIN_VALUE, -1))
        assertEquals(2, m.getOrDefault(Int.MAX_VALUE, -1))
        assertEquals(3, m.getOrDefault(0, -1))
        assertEquals(3, m.size)
    }

    @Test
    fun `remove deletes and leaves other entries findable across collisions`() {
        val m = MutableIntIntMap(4)
        for (k in 0 until 50) m.put(k, k * k)
        for (k in 0 until 50 step 3) assertTrue(m.remove(k))
        for (k in 0 until 50) {
            if (k % 3 == 0) {
                assertFalse(m.containsKey(k), "removed key $k")
                assertEquals(-1, m.getOrDefault(k, -1))
            } else {
                assertEquals(k * k, m.getOrDefault(k, -1), "survivor key $k")
            }
        }
        assertFalse(m.remove(0), "already-removed key returns false")
    }

    @Test
    fun `clear empties and the map is reusable`() {
        val m = MutableIntIntMap()
        for (k in 0 until 30) m.put(k, k)
        m.clear()
        assertEquals(0, m.size)
        assertFalse(m.containsKey(5))
        m.put(99, 7)
        assertEquals(7, m.getOrDefault(99, -1))
        assertEquals(1, m.size)
    }

    @Test
    fun `forEach visits every entry exactly once`() {
        val m = MutableIntIntMap()
        val expected = HashMap<Int, Int>()
        for (k in -10..10) {
            m.put(k, k * 3)
            expected[k] = k * 3
        }
        val seen = HashMap<Int, Int>()
        m.forEach { key, value ->
            assertFalse(seen.containsKey(key), "duplicate visit of $key")
            seen[key] = value
        }
        assertEquals(expected, seen)
    }

    @Test
    fun `growth preserves all entries`() {
        val m = MutableIntIntMap(8)
        for (k in 0 until 1000) m.put(k * 7, k)
        assertEquals(1000, m.size)
        for (k in 0 until 1000) assertEquals(k, m.getOrDefault(k * 7, -1))
    }

}
