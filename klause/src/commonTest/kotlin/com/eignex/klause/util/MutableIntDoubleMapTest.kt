package com.eignex.klause.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Coverage for [MutableIntDoubleMap]: put/get/addTo/remove/clear, growth, backward-shift deletion,
 *  extreme keys, and a randomized differential check against `HashMap`. */
class MutableIntDoubleMapTest {

    @Test
    fun `empty map returns default and reports absent`() {
        val m = MutableIntDoubleMap()
        assertEquals(0, m.size)
        assertTrue(m.isEmpty())
        assertEquals(-1.0, m.getOrDefault(42, -1.0))
        assertFalse(m.containsKey(42))
    }

    @Test
    fun `put then get round-trips and tracks size`() {
        val m = MutableIntDoubleMap()
        m.put(10, 1.5)
        m.put(20, 2.5)
        assertEquals(1.5, m.getOrDefault(10, -1.0))
        assertEquals(2.5, m.getOrDefault(20, -1.0))
        assertEquals(2, m.size)
        assertTrue(m.containsKey(10))
        assertFalse(m.containsKey(30))
    }

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
            if (k % 3 == 0) assertFalse(m.containsKey(k), "removed key $k")
            else assertEquals(k.toDouble(), m.getOrDefault(k, -1.0), "survivor key $k")
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

    @Test
    fun `matches a HashMap reference under random put-addTo-remove churn`() {
        val rng = Random(2024)
        repeat(12) {
            val m = MutableIntDoubleMap(rng.nextInt(1, 16))
            val ref = HashMap<Int, Double>()
            repeat(600) {
                val key = rng.nextInt(-60, 60)
                when (rng.nextInt(4)) {
                    0 -> {
                        val v = rng.nextInt(-100, 100).toDouble()
                        m.put(key, v)
                        ref[key] = v
                    }

                    1 -> {
                        val d = rng.nextInt(-5, 6).toDouble()
                        val got = m.addTo(key, d)
                        val exp = (ref[key] ?: 0.0) + d
                        ref[key] = exp
                        assertEquals(exp, got)
                    }

                    2 -> assertEquals(ref.remove(key) != null, m.remove(key))

                    3 -> assertEquals(ref[key] ?: Double.MIN_VALUE, m.getOrDefault(key, Double.MIN_VALUE))
                }
                assertEquals(ref.size, m.size)
            }
            val seen = HashMap<Int, Double>()
            m.forEach { k, v -> seen[k] = v }
            assertEquals(ref, seen)
        }
    }
}
