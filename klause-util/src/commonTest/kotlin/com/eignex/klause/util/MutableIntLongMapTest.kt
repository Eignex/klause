package com.eignex.klause.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Coverage for [MutableIntLongMap]: put/get/addTo/remove/clear, growth, backward-shift deletion,
 *  extreme keys, and a randomized differential check against `HashMap`. */
class MutableIntLongMapTest {

    @Test
    fun `empty map returns default and reports absent`() {
        val m = MutableIntLongMap()
        assertEquals(0, m.size)
        assertTrue(m.isEmpty())
        assertEquals(-1L, m.getOrDefault(42, -1L))
        assertFalse(m.containsKey(42))
    }

    @Test
    fun `put then get round-trips and tracks size`() {
        val m = MutableIntLongMap()
        m.put(10, 100L)
        m.put(20, 1L shl 40)
        assertEquals(100L, m.getOrDefault(10, -1L))
        assertEquals(1L shl 40, m.getOrDefault(20, -1L))
        assertEquals(2, m.size)
    }

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

    @Test
    fun `matches a HashMap reference under random put-addTo-remove churn`() {
        val rng = Random(2024)
        repeat(12) {
            val m = MutableIntLongMap(rng.nextInt(1, 16))
            val ref = HashMap<Int, Long>()
            repeat(600) {
                val key = rng.nextInt(-60, 60)
                when (rng.nextInt(4)) {
                    0 -> {
                        val v = rng.nextInt(-100, 100).toLong()
                        m.put(key, v)
                        ref[key] = v
                    }

                    1 -> {
                        val d = rng.nextInt(-5, 6).toLong()
                        val got = m.addTo(key, d)
                        val exp = (ref[key] ?: 0L) + d
                        ref[key] = exp
                        assertEquals(exp, got)
                    }

                    2 -> assertEquals(ref.remove(key) != null, m.remove(key))

                    3 -> assertEquals(ref[key] ?: Long.MIN_VALUE, m.getOrDefault(key, Long.MIN_VALUE))
                }
                assertEquals(ref.size, m.size)
            }
            val seen = HashMap<Int, Long>()
            m.forEach { k, v -> seen[k] = v }
            assertEquals(ref, seen)
        }
    }
}
