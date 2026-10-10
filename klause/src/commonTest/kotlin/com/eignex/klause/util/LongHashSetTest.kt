package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LongHashSetTest {

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

}
