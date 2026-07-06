package com.eignex.klause.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Coverage for [MutableIntObjectMap]: get/put/getOrPut/remove/clear, growth, backward-shift
 *  deletion, extreme keys, and a randomized differential check against `HashMap`. */
class MutableIntObjectMapTest {

    @Test
    fun `empty map returns null and reports absent`() {
        val m = MutableIntObjectMap<String>()
        assertEquals(0, m.size)
        assertTrue(m.isEmpty())
        assertNull(m[42])
        assertFalse(m.containsKey(42))
    }

    @Test
    fun `put then get round-trips and tracks size`() {
        val m = MutableIntObjectMap<String>()
        m.put(10, "ten")
        m.put(20, "twenty")
        assertEquals("ten", m[10])
        assertEquals("twenty", m[20])
        assertEquals(2, m.size)
        assertNull(m[30])
    }

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
            if (k % 3 == 0) assertNull(m[k], "removed key $k")
            else assertEquals(k * k, m[k], "survivor key $k")
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

    @Test
    fun `matches a HashMap reference under random put-remove churn`() {
        val rng = Random(2024)
        repeat(12) {
            val m = MutableIntObjectMap<Int>(rng.nextInt(1, 16))
            val ref = HashMap<Int, Int>()
            repeat(600) {
                val key = rng.nextInt(-60, 60)
                when (rng.nextInt(3)) {
                    0 -> {
                        val v = rng.nextInt(-100, 100)
                        m.put(key, v)
                        ref[key] = v
                    }

                    1 -> assertEquals(ref.remove(key) != null, m.remove(key))

                    2 -> assertEquals(ref[key], m[key])
                }
                assertEquals(ref.size, m.size)
            }
            val seen = HashMap<Int, Int>()
            m.forEach { k, v -> seen[k] = v }
            assertEquals(ref, seen)
        }
    }
}
