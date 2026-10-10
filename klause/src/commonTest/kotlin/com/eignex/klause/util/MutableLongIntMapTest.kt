package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutableLongIntMapTest {

    @Test
    fun `put overwrites existing key without growing size`() {
        val m = MutableLongIntMap()
        m.put(5L, 1)
        m.put(5L, 2)
        m.put(5L, 3)
        assertEquals(3, m.getOrDefault(5L, -1))
        assertEquals(1, m.size)
    }

    @Test
    fun `addTo increments from zero default and returns new value`() {
        val m = MutableLongIntMap()
        assertEquals(1, m.addTo(7_000_000_000L, 1))
        assertEquals(3, m.addTo(7_000_000_000L, 2))
        assertEquals(-1, m.addTo(7_000_000_000L, -4))
        assertEquals(-1, m.getOrDefault(7_000_000_000L, 0))
        assertEquals(1, m.size)
    }

    @Test
    fun `extreme keys and zero coexist`() {
        val m = MutableLongIntMap()
        m.put(Long.MIN_VALUE, 1)
        m.put(Long.MAX_VALUE, 2)
        m.put(0L, 3)
        assertEquals(1, m.getOrDefault(Long.MIN_VALUE, -1))
        assertEquals(2, m.getOrDefault(Long.MAX_VALUE, -1))
        assertEquals(3, m.getOrDefault(0L, -1))
        assertEquals(3, m.size)
    }

    @Test
    fun `value equal to default is still reported present`() {
        val m = MutableLongIntMap()
        m.put(5L, 0)
        assertEquals(0, m.getOrDefault(5L, 0))
        assertTrue(m.containsKey(5L))
    }

    @Test
    fun `remove deletes and leaves other entries findable across collisions`() {
        val m = MutableLongIntMap(4)
        for (k in 0L until 50L) m.put(k, (k * k).toInt())
        for (k in 0L until 50L step 3) assertTrue(m.remove(k))
        for (k in 0L until 50L) {
            if (k % 3 == 0L) {
                assertFalse(m.containsKey(k), "removed key $k")
                assertEquals(-1, m.getOrDefault(k, -1))
            } else {
                assertEquals((k * k).toInt(), m.getOrDefault(k, -1), "survivor key $k")
            }
        }
        assertFalse(m.remove(0L), "already-removed key returns false")
    }

    @Test
    fun `clear empties and the map is reusable`() {
        val m = MutableLongIntMap()
        for (k in 0L until 30L) m.put(k, k.toInt())
        m.clear()
        assertEquals(0, m.size)
        assertFalse(m.containsKey(5L))
        m.put(99L, 7)
        assertEquals(7, m.getOrDefault(99L, -1))
        assertEquals(1, m.size)
    }

    @Test
    fun `forEach visits every entry exactly once`() {
        val m = MutableLongIntMap()
        val expected = HashMap<Long, Int>()
        for (k in -10L..10L) {
            m.put(k, (k * 3).toInt())
            expected[k] = (k * 3).toInt()
        }
        val seen = HashMap<Long, Int>()
        m.forEach { key, value ->
            assertFalse(seen.containsKey(key), "duplicate visit of $key")
            seen[key] = value
        }
        assertEquals(expected, seen)
    }

    @Test
    fun `growth preserves all entries`() {
        val m = MutableLongIntMap(8)
        for (k in 0L until 1000L) m.put(k * 7L, k.toInt())
        assertEquals(1000, m.size)
        for (k in 0L until 1000L) assertEquals(k.toInt(), m.getOrDefault(k * 7L, -1))
    }

}
