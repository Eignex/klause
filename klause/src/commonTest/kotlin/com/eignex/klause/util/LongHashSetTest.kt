package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LongHashSetTest {

    @Test
    fun `bounded lookup should stop a collision chain without changing retained keys`() {
        val keys = (0L..64L).groupBy { mixLongKey(it) and 15 }.values.first { it.size >= 3 }.take(3)
        val set = LongHashSet()
        for (key in keys) set.add(key)
        var charged = 0

        val found = set.containsBounded(keys.last(), 1L, { false }, { charged++ })

        assertEquals(null, found)
        assertEquals(1, charged)
        assertEquals(3, set.size)
        assertTrue(keys.all { it in set })
        assertEquals(true, set.containsBounded(keys.last(), 3L, { false }, {}))
    }

    @Test
    fun `bounded lookup should honor cancellation after its debit`() {
        val set = LongHashSet()
        set.add(7L)
        var charged = 0

        val found = set.containsBounded(7L, 1L, { charged > 0 }, { charged++ })

        assertEquals(null, found)
        assertEquals(1, charged)
        assertTrue(7L in set)
    }

    @Test
    fun `bounded insertion protects existing keys when growth exceeds its byte allowance`() {
        val set = LongHashSet(1)
        repeat(4) { assertEquals(true, set.tryAddBounded(it.toLong(), 72L, 1000L, 1000L, { false }, {})) }

        val added = set.tryAddBounded(4L, 72L, 1000L, 1000L, { false }, {})

        assertEquals(null, added)
        assertEquals(72L, set.allocatedBytes)
        assertEquals(4, set.size)
        repeat(4) { assertTrue(it.toLong() in set) }
        assertEquals(false, set.tryAddBounded(0L, 0L, 1000L, 0L, { false }, {}))
    }

    @Test
    fun `bounded insertion discards cancelled growth before publication`() {
        val set = LongHashSet(1)
        repeat(4) { set.add(it.toLong()) }
        var charged = 0

        val added = set.tryAddBounded(4L, 1000L, 1000L, 1000L, { charged >= 10 }, { charged++ })

        assertEquals(null, added)
        assertEquals(72L, set.allocatedBytes)
        assertEquals(4, set.size)
        repeat(4) { assertTrue(it.toLong() in set) }
        assertFalse(4L in set)
    }

    @Test
    fun `tiny bounded insertion allowances retain no backing arrays`() {
        for ((work, scratch) in listOf(0L to 1000L, 1000L to 0L)) {
            val set = LongHashSet()

            val added = set.tryAddBounded(1L, 1000L, work, scratch, { false }, {})

            assertEquals(null, added)
            assertEquals(0L, set.allocatedBytes)
            assertEquals(0, set.size)
        }
    }

    @Test
    fun `final insertion debit cancellation preserves the published table`() {
        val control = LongHashSet(1)
        repeat(4) { control.add(it.toLong()) }
        var required = 0
        assertEquals(true, control.tryAddBounded(4L, 1000L, 1000L, 1000L, { false }, { required++ }))
        val set = LongHashSet(1)
        repeat(4) { set.add(it.toLong()) }
        var charged = 0

        val added = set.tryAddBounded(4L, 1000L, 1000L, 1000L, { charged >= required }, { charged++ })

        assertEquals(null, added)
        assertEquals(required, charged)
        assertEquals(72L, set.allocatedBytes)
        assertEquals(4, set.size)
        assertFalse(4L in set)
    }

    @Test
    fun `never populated set should answer every query and stay usable`() {
        val s = LongHashSet()
        assertFalse(s.contains(0L))
        assertFalse(s.contains(Long.MIN_VALUE))
        assertFalse(s.remove(7L))
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
    fun `add should report novelty and deduplicate`() {
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
    fun `extreme members and zero should coexist`() {
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
    fun `remove should keep survivors findable across collisions`() {
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
        assertFalse(s.remove(0L))
        assertEquals(25, s.size)
    }

    @Test
    fun `clear should empty the set and keep it reusable`() {
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
    fun `forEach should visit every member exactly once`() {
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
    fun `growth should preserve all members including wide range keys`() {
        val s = LongHashSet(8)
        for (k in 0 until 1000) s.add(k.toLong() * 0x1_0000_0007L)
        assertEquals(1000, s.size)
        for (k in 0 until 1000) assertTrue(s.contains(k.toLong() * 0x1_0000_0007L))
    }
}
