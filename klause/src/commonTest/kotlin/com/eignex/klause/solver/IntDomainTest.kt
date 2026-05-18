package com.eignex.klause.solver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFails

class IntDomainTest {

    @Test
    fun `contiguous domain basics`() {
        val d = IntDomain(1, 10)
        assertEquals(1, d.min)
        assertEquals(10, d.max)
        assertEquals(10, d.size)
        assertTrue(5 in d)
        assertFalse(0 in d)
        assertFalse(11 in d)
        assertEquals(5, d.clamp(5))
        assertEquals(1, d.clamp(-5))
        assertEquals(10, d.clamp(100))
    }

    @Test
    fun `excludeValue absent is identity`() {
        val d = IntDomain(1, 5)
        val e = d.excludeValue(99)
        assertTrue(e === d, "absent-value exclusion should return the same instance")
    }

    @Test
    fun `excludeValue at min advances and trims`() {
        val d = IntDomain(1, 5)
        val e = d.excludeValue(1)
        assertEquals(2, e.min)
        assertEquals(5, e.max)
        assertEquals(4, e.size)
        assertFalse(1 in e)
        assertTrue(2 in e)
    }

    @Test
    fun `excludeValue at max retreats and trims`() {
        val d = IntDomain(1, 5)
        val e = d.excludeValue(5)
        assertEquals(1, e.min)
        assertEquals(4, e.max)
        assertEquals(4, e.size)
        assertFalse(5 in e)
        assertTrue(4 in e)
    }

    @Test
    fun `excludeValue interior creates sparse domain`() {
        val d = IntDomain(1, 5)
        val e = d.excludeValue(3)
        assertEquals(1, e.min)
        assertEquals(5, e.max)
        assertEquals(4, e.size, "[1,5] minus {3} should have 4 values")
        assertTrue(2 in e)
        assertFalse(3 in e)
        assertTrue(4 in e)
        // Iteration skips the hole.
        val seen = mutableListOf<Int>()
        e.forEach { seen.add(it) }
        assertEquals(listOf(1, 2, 4, 5), seen)
    }

    @Test
    fun `excludeValue stacking accumulates holes`() {
        val d = IntDomain(1, 10).excludeValue(4).excludeValue(7).excludeValue(5)
        assertEquals(1, d.min)
        assertEquals(10, d.max)
        assertEquals(7, d.size)
        assertFalse(4 in d)
        assertFalse(5 in d)
        assertFalse(7 in d)
        assertTrue(6 in d)
        val seen = mutableListOf<Int>()
        d.forEach { seen.add(it) }
        assertEquals(listOf(1, 2, 3, 6, 8, 9, 10), seen)
    }

    @Test
    fun `excludeValue at min jumps past pre-existing adjacent holes`() {
        val d = IntDomain(1, 10).excludeValue(2).excludeValue(3)
        // domain is now [1..10] - {2, 3}.
        val e = d.excludeValue(1)
        // Removing 1 advances min past holes 2, 3 → new min = 4.
        assertEquals(4, e.min)
        assertEquals(10, e.max)
        assertEquals(7, e.size)
        // No holes should remain in the sparse representation since the trimmed ones
        // are now below the new min.
        val seen = mutableListOf<Int>()
        e.forEach { seen.add(it) }
        assertEquals(listOf(4, 5, 6, 7, 8, 9, 10), seen)
    }

    @Test
    fun `excludeValue collapsing to single value`() {
        val d = IntDomain(1, 3).excludeValue(2).excludeValue(3)
        assertEquals(1, d.min)
        assertEquals(1, d.max)
        assertEquals(1, d.size)
        assertTrue(1 in d)
    }

    @Test
    fun `excludeValue that would empty domain throws`() {
        val d = IntDomain(5, 5)
        assertFails { d.excludeValue(5) }
    }

    @Test
    fun `withMinAtLeast skips holes`() {
        val d = IntDomain(1, 10).excludeValue(4).excludeValue(5)
        // domain is [1..10] - {4, 5}.
        val e = d.withMinAtLeast(4)
        // Should land on 6 (skipping holes 4 and 5).
        assertEquals(6, e.min)
        assertEquals(10, e.max)
        assertEquals(5, e.size)
    }

    @Test
    fun `withMaxAtMost skips holes`() {
        val d = IntDomain(1, 10).excludeValue(6).excludeValue(7)
        val e = d.withMaxAtMost(7)
        assertEquals(1, e.min)
        assertEquals(5, e.max)
        assertEquals(5, e.size)
    }

    @Test
    fun `equals respects holes`() {
        val a = IntDomain(1, 5).excludeValue(3)
        val b = IntDomain(1, 5).excludeValue(3)
        val c = IntDomain(1, 5).excludeValue(4)
        val d = IntDomain(1, 5)  // contiguous
        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a != d)
    }

    @Test
    fun `forEach skips holes and preserves order`() {
        val d = IntDomain(1, 7).excludeValue(3).excludeValue(5)
        val seen = mutableListOf<Int>()
        d.forEach { seen.add(it) }
        assertEquals(listOf(1, 2, 4, 6, 7), seen)
    }
}
