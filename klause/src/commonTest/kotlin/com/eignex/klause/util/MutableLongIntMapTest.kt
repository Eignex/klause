package com.eignex.klause.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Coverage for [MutableLongIntMap]: put/get/remove/clear semantics, growth, backward-shift
 *  deletion across collisions, extreme keys, and a randomized differential check vs `HashMap`. */
class MutableLongIntMapTest {

    @Test
    fun `empty map returns default and reports absent`() {
        val m = MutableLongIntMap()
        assertEquals(0, m.size)
        assertTrue(m.isEmpty())
        assertEquals(-1, m.getOrDefault(42L, -1))
        assertFalse(m.containsKey(42L))
    }

    @Test
    fun `put then get round-trips and tracks size`() {
        val m = MutableLongIntMap()
        m.put(10_000_000_000L, 100)
        m.put(-20_000_000_000L, 200)
        assertEquals(100, m.getOrDefault(10_000_000_000L, -1))
        assertEquals(200, m.getOrDefault(-20_000_000_000L, -1))
        assertEquals(2, m.size)
        assertTrue(m.containsKey(10_000_000_000L))
        assertFalse(m.containsKey(30L))
    }

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

    @Test
    fun `matches a HashMap reference under random put-remove churn`() {
        val rng = Random(31)
        repeat(12) {
            val m = MutableLongIntMap(rng.nextInt(1, 16))
            val ref = HashMap<Long, Int>()
            repeat(600) {
                // Mix of small (collision-prone) and wide keys.
                val key = if (rng.nextBoolean()) rng.nextLong(-60, 60) else rng.nextLong()
                when (rng.nextInt(4)) {
                    0 -> {
                        val v = rng.nextInt(-100, 100)
                        m.put(key, v)
                        ref[key] = v
                    }

                    1 -> assertEquals(ref.remove(key) != null, m.remove(key), "remove($key)")

                    2 -> {
                        assertEquals(ref.containsKey(key), m.containsKey(key), "containsKey($key)")
                        assertEquals(ref[key] ?: Int.MIN_VALUE, m.getOrDefault(key, Int.MIN_VALUE), "get($key)")
                    }

                    3 -> {
                        val d = rng.nextInt(-5, 6)
                        val exp = (ref[key] ?: 0) + d
                        ref[key] = exp
                        assertEquals(exp, m.addTo(key, d), "addTo($key)")
                    }
                }
                assertEquals(ref.size, m.size)
            }
            val seen = HashMap<Long, Int>()
            m.forEach { k, v -> seen[k] = v }
            assertEquals(ref, seen)
        }
    }
}
