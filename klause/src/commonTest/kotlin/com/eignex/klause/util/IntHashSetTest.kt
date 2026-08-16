package com.eignex.klause.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Coverage for [IntHashSet]: add/contains/remove/clear semantics, growth, backward-shift deletion
 *  across collisions, extreme members, and a randomized differential check against `HashSet`. */
class IntHashSetTest {

    @Test
    fun `empty set reports absent`() {
        val s = IntHashSet()
        assertEquals(0, s.size)
        assertTrue(s.isEmpty())
        assertFalse(s.contains(0))
    }

    @Test
    fun `every query on a never-populated set is safe and it stays usable after`() {
        // Exercises the lazy-backing size-0 fast paths: every query must be safe before the first
        // add (the backing arrays are still zero-length).
        val s = IntHashSet()
        assertFalse(s.contains(0))
        assertFalse(s.contains(Int.MIN_VALUE))
        assertFalse(s.remove(7), "remove on a never-populated set is a no-op")
        assertEquals(0, s.toIntArray().size)
        var visits = 0
        s.forEach { visits++ }
        assertEquals(0, visits)
        s.clear()
        assertTrue(s.isEmpty())
        // After all that, the set is still fully functional.
        assertTrue(s.add(7))
        assertTrue(s.contains(7))
        assertEquals(1, s.size)
    }

    @Test
    fun `add reports novelty and dedupes`() {
        val s = IntHashSet()
        assertTrue(s.add(5))
        assertFalse(s.add(5))
        assertTrue(s.add(6))
        assertEquals(2, s.size)
        assertTrue(s.contains(5))
        assertTrue(s.contains(6))
        assertFalse(s.contains(7))
    }

    @Test
    fun `extreme members and zero coexist`() {
        val s = IntHashSet()
        s.add(Int.MIN_VALUE)
        s.add(Int.MAX_VALUE)
        s.add(0)
        assertTrue(s.contains(Int.MIN_VALUE))
        assertTrue(s.contains(Int.MAX_VALUE))
        assertTrue(s.contains(0))
        assertEquals(3, s.size)
    }

    @Test
    fun `remove deletes and survivors remain findable across collisions`() {
        val s = IntHashSet(4)
        for (k in 0 until 50) s.add(k)
        for (k in 0 until 50 step 2) assertTrue(s.remove(k))
        for (k in 0 until 50) {
            if (k % 2 == 0) assertFalse(s.contains(k), "removed $k") else assertTrue(s.contains(k), "survivor $k")
        }
        assertFalse(s.remove(0), "already-removed returns false")
        assertEquals(25, s.size)
    }

    @Test
    fun `clear empties and the set is reusable`() {
        val s = IntHashSet()
        for (k in 0 until 30) s.add(k)
        s.clear()
        assertEquals(0, s.size)
        assertFalse(s.contains(5))
        s.add(99)
        assertTrue(s.contains(99))
        assertEquals(1, s.size)
    }

    @Test
    fun `forEach visits every member exactly once`() {
        val s = IntHashSet()
        val expected = HashSet<Int>()
        for (k in -20..20) {
            s.add(k)
            expected.add(k)
        }
        val seen = HashSet<Int>()
        s.forEach { assertTrue(seen.add(it), "duplicate visit of $it") }
        assertEquals(expected, seen)
    }

    @Test
    fun `growth preserves all members`() {
        val s = IntHashSet(8)
        for (k in 0 until 1000) s.add(k * 13)
        assertEquals(1000, s.size)
        for (k in 0 until 1000) assertTrue(s.contains(k * 13))
    }

    @Test
    fun `matches a HashSet reference under random add-remove churn`() {
        val rng = Random(777)
        repeat(12) { _ ->
            val s = IntHashSet(rng.nextInt(1, 16))
            val ref = HashSet<Int>()
            repeat(600) {
                val v = rng.nextInt(-60, 60)
                when (rng.nextInt(3)) {
                    0 -> assertEquals(ref.add(v), s.add(v), "add($v)")
                    1 -> assertEquals(ref.remove(v), s.remove(v), "remove($v)")
                    2 -> assertEquals(ref.contains(v), s.contains(v), "contains($v)")
                }
                assertEquals(ref.size, s.size)
            }
            val seen = HashSet<Int>()
            s.forEach { seen.add(it) }
            assertEquals(ref, seen)
        }
    }
}
