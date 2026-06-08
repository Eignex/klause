package com.eignex.klause.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Coverage for [MutableIntIntMap]: put/get/addTo/remove/clear semantics, growth, backward-shift
 *  deletion correctness, extreme keys, and a randomized differential check against `HashMap`. */
class MutableIntIntMapTest {

    @Test
    fun `empty map returns default and reports absent`() {
        val m = MutableIntIntMap()
        assertEquals(0, m.size)
        assertTrue(m.isEmpty())
        assertEquals(-1, m.getOrDefault(42, -1))
        assertFalse(m.containsKey(42))
    }

    @Test
    fun `put then get round-trips and tracks size`() {
        val m = MutableIntIntMap()
        m.put(10, 100)
        m.put(20, 200)
        assertEquals(100, m.getOrDefault(10, -1))
        assertEquals(200, m.getOrDefault(20, -1))
        assertEquals(2, m.size)
        assertTrue(m.containsKey(10))
        assertFalse(m.containsKey(30))
    }

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
        // Force collisions: many keys, small map; backward-shift must keep chains intact.
        val m = MutableIntIntMap(4)
        for (k in 0 until 50) m.put(k, k * k)
        // Remove every third key.
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

    @Test
    fun `matches a HashMap reference under random put-addTo-remove churn`() {
        val rng = Random(2024)
        repeat(40) {
            val m = MutableIntIntMap(rng.nextInt(1, 16))
            val ref = HashMap<Int, Int>()
            repeat(2000) {
                val key = rng.nextInt(-60, 60)
                when (rng.nextInt(4)) {
                    0 -> {
                        val v = rng.nextInt(-100, 100)
                        m.put(key, v)
                        ref[key] = v
                    }

                    1 -> {
                        val d = rng.nextInt(-5, 6)
                        val got = m.addTo(key, d)
                        val exp = (ref[key] ?: 0) + d
                        ref[key] = exp
                        assertEquals(exp, got, "addTo return")
                    }

                    2 -> {
                        assertEquals(ref.remove(key) != null, m.remove(key), "remove($key)")
                    }

                    3 -> {
                        assertEquals(ref.containsKey(key), m.containsKey(key), "containsKey($key)")
                        assertEquals(ref[key] ?: Int.MIN_VALUE, m.getOrDefault(key, Int.MIN_VALUE), "get($key)")
                    }
                }
                assertEquals(ref.size, m.size)
            }
            // Final full reconciliation, including forEach contents.
            val seen = HashMap<Int, Int>()
            m.forEach { k, v -> seen[k] = v }
            assertEquals(ref, seen)
        }
    }
}
