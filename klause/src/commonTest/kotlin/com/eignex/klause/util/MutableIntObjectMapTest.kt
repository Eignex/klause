package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutableIntObjectMapTest {

    @Test
    fun `put overwrites existing key without growing size`() {
        val m = MutableIntObjectMap<String>()
        m.put(5, "a")
        m.put(5, "b")
        assertEquals("b", m[5])
        assertEquals(1, m.size)
    }

    @Test
    fun `getOrPut inserts once and reuses thereafter`() {
        val m = MutableIntObjectMap<MutableList<Int>>()
        m.getOrPut(3) { mutableListOf() }.add(1)
        m.getOrPut(3) { mutableListOf() }.add(2)
        assertEquals(listOf(1, 2), m[3]!!.toList())
        assertEquals(1, m.size)
    }

    @Test
    fun `extreme keys and zero coexist`() {
        val m = MutableIntObjectMap<Int>()
        m.put(Int.MIN_VALUE, 1)
        m.put(Int.MAX_VALUE, 2)
        m.put(0, 3)
        assertEquals(1, m[Int.MIN_VALUE])
        assertEquals(2, m[Int.MAX_VALUE])
        assertEquals(3, m[0])
        assertEquals(3, m.size)
    }

    @Test
    fun `remove deletes and leaves other entries findable across collisions`() {
        val m = MutableIntObjectMap<Int>(4)
        for (k in 0 until 50) m.put(k, k * k)
        for (k in 0 until 50 step 3) assertTrue(m.remove(k))
        for (k in 0 until 50) {
            if (k % 3 == 0) {
                assertNull(m[k], "removed key $k")
            } else {
                assertEquals(k * k, m[k], "survivor key $k")
            }
        }
        assertFalse(m.remove(0))
    }

    @Test
    fun `clear empties and the map is reusable`() {
        val m = MutableIntObjectMap<Int>()
        for (k in 0 until 30) m.put(k, k)
        m.clear()
        assertEquals(0, m.size)
        assertNull(m[5])
        m.put(99, 7)
        assertEquals(7, m[99])
        assertEquals(1, m.size)
    }

    @Test
    fun `growth preserves all entries`() {
        val m = MutableIntObjectMap<Int>(8)
        for (k in 0 until 1000) m.put(k * 7, k)
        assertEquals(1000, m.size)
        for (k in 0 until 1000) assertEquals(k, m[k * 7])
    }

}
