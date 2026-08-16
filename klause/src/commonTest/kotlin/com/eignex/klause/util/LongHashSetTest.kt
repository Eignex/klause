package com.eignex.klause.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Coverage for [LongHashSet]: add/contains/remove/clear semantics, growth, backward-shift deletion
 *  across collisions, extreme members, and a randomized differential check against `HashSet`. */
class LongHashSetTest {

    @Test
    fun `empty set reports absent`() {
        val s = LongHashSet()
        assertEquals(0, s.size)
        assertTrue(s.isEmpty())
        assertFalse(s.contains(0L))
    }

    @Test
    fun `every query on a never-populated set is safe and it stays usable after`() {
        val s = LongHashSet()
        assertFalse(s.contains(0L))
        assertFalse(s.contains(Long.MIN_VALUE))
        assertFalse(s.remove(7L), "remove on a never-populated set is a no-op")
        assertEquals(0, s.toLongArray().size)
        var visits = 0
        s.forEach { visits++ }
        assertEquals(0, visits)
        s.clear()
        assertTrue(s.isEmpty())
        assertTrue(s.add(7L))
        assertTrue(s.contains(7L))
        assertEquals(1, s.size)
    }

    @Test
    fun `add reports novelty and dedupes`() {
        val s = LongHashSet()
        assertTrue(s.add(5L))
        assertFalse(s.add(5L))
        assertTrue(s.add(6L))
        assertEquals(2, s.size)
        assertTrue(s.contains(5L))
        assertTrue(s.contains(6L))
        assertFalse(s.contains(7L))
    }

    @Test
    fun `extreme members and zero coexist`() {
        val s = LongHashSet()
        s.add(Long.MIN_VALUE)
        s.add(Long.MAX_VALUE)
        s.add(0L)
        assertTrue(s.contains(Long.MIN_VALUE))
        assertTrue(s.contains(Long.MAX_VALUE))
        assertTrue(s.contains(0L))
        assertEquals(3, s.size)
    }

    @Test
    fun `remove deletes and survivors remain findable across collisions`() {
        val s = LongHashSet(4)
        for (k in 0 until 50) s.add(k.toLong())
        for (k in 0 until 50 step 2) assertTrue(s.remove(k.toLong()))
        for (k in 0 until 50) {
            if (k % 2 == 0) {
                assertFalse(s.contains(k.toLong()), "removed $k")
            } else {
                assertTrue(s.contains(k.toLong()), "survivor $k")
            }
        }
        assertFalse(s.remove(0L), "already-removed returns false")
        assertEquals(25, s.size)
    }

    @Test
    fun `clear empties and the set is reusable`() {
        val s = LongHashSet()
        for (k in 0 until 30) s.add(k.toLong())
        s.clear()
        assertEquals(0, s.size)
        assertFalse(s.contains(5L))
        s.add(99L)
        assertTrue(s.contains(99L))
        assertEquals(1, s.size)
    }

    @Test
    fun `forEach visits every member exactly once`() {
        val s = LongHashSet()
        val expected = HashSet<Long>()
        for (k in -20L..20L) {
            s.add(k)
            expected.add(k)
        }
        val seen = HashSet<Long>()
        s.forEach { assertTrue(seen.add(it), "duplicate visit of $it") }
        assertEquals(expected, seen)
    }

    @Test
    fun `growth preserves all members including wide-range keys`() {
        val s = LongHashSet(8)
        for (k in 0 until 1000) s.add(k.toLong() * 0x1_0000_0007L)
        assertEquals(1000, s.size)
        for (k in 0 until 1000) assertTrue(s.contains(k.toLong() * 0x1_0000_0007L))
    }

    @Test
    fun `matches a HashSet reference under random add-remove churn`() {
        val rng = Random(777)
        repeat(12) { _ ->
            val s = LongHashSet(rng.nextInt(1, 16))
            val ref = HashSet<Long>()
            repeat(600) {
                val v = rng.nextInt(-60, 60).toLong()
                when (rng.nextInt(3)) {
                    0 -> assertEquals(ref.add(v), s.add(v), "add($v)")
                    1 -> assertEquals(ref.remove(v), s.remove(v), "remove($v)")
                    2 -> assertEquals(ref.contains(v), s.contains(v), "contains($v)")
                }
                assertEquals(ref.size, s.size)
            }
            val seen = HashSet<Long>()
            s.forEach { seen.add(it) }
            assertEquals(ref, seen)
        }
    }
}
