package com.eignex.klause.solver.intdomain

import com.eignex.klause.config.DEFAULT_BITSET_THRESHOLD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContiguousDomainTest {

    @Test
    fun `contiguous domain basics`() {
        val d = ContiguousDomain(1, 10)
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
        val d = ContiguousDomain(1, 5)
        val e = d.excludeValue(99)
        assertTrue(e === d)
    }

    @Test
    fun `excludeValue at min advances and trims`() {
        val d = ContiguousDomain(1, 5)
        val e = d.excludeValue(1)
        assertEquals(2, e.min)
        assertEquals(5, e.max)
        assertEquals(4, e.size)
        assertFalse(1 in e)
        assertTrue(2 in e)
    }

    @Test
    fun `excludeValue at max retreats and trims`() {
        val d = ContiguousDomain(1, 5)
        val e = d.excludeValue(5)
        assertEquals(1, e.min)
        assertEquals(4, e.max)
        assertEquals(4, e.size)
        assertFalse(5 in e)
        assertTrue(4 in e)
    }

    @Test
    fun `excludeValue interior creates sparse domain`() {
        val d = ContiguousDomain(1, 5)
        val e = d.excludeValue(3)
        assertEquals(1, e.min)
        assertEquals(5, e.max)
        assertEquals(4, e.size)
        assertTrue(2 in e)
        assertFalse(3 in e)
        assertTrue(4 in e)
        val seen = mutableListOf<Int>()
        e.forEach { seen.add(it) }
        assertEquals(listOf(1, 2, 4, 5), seen)
    }

    @Test
    fun `excludeValue stacking accumulates holes`() {
        val d = ContiguousDomain(1, 10).excludeValue(4).excludeValue(7).excludeValue(5)
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
        val d = ContiguousDomain(1, 10).excludeValue(2).excludeValue(3)
        val e = d.excludeValue(1)
        assertEquals(4, e.min)
        assertEquals(10, e.max)
        assertEquals(7, e.size)
        val seen = mutableListOf<Int>()
        e.forEach { seen.add(it) }
        assertEquals(listOf(4, 5, 6, 7, 8, 9, 10), seen)
    }

    @Test
    fun `excludeValue collapsing to single value`() {
        val d = ContiguousDomain(1, 3).excludeValue(2).excludeValue(3)
        assertEquals(1, d.min)
        assertEquals(1, d.max)
        assertEquals(1, d.size)
        assertTrue(1 in d)
    }

    @Test
    fun `excludeValue that would empty domain throws`() {
        val d = ContiguousDomain(5, 5)
        assertFails { d.excludeValue(5) }
    }

    @Test
    fun `withMinAtLeast skips holes`() {
        val d = ContiguousDomain(1, 10).excludeValue(4).excludeValue(5)
        val e = d.withMinAtLeast(4)
        assertEquals(6, e.min)
        assertEquals(10, e.max)
        assertEquals(5, e.size)
    }

    @Test
    fun `withMaxAtMost skips holes`() {
        val d = ContiguousDomain(1, 10).excludeValue(6).excludeValue(7)
        val e = d.withMaxAtMost(7)
        assertEquals(1, e.min)
        assertEquals(5, e.max)
        assertEquals(5, e.size)
    }

    @Test
    fun `forEach skips holes and preserves order`() {
        val d = ContiguousDomain(1, 7).excludeValue(3).excludeValue(5)
        val seen = mutableListOf<Int>()
        d.forEach { seen.add(it) }
        assertEquals(listOf(1, 2, 4, 6, 7), seen)
    }

    @Test
    fun `excludeValue should use bitset domain for narrow interior carve`() {
        val d = ContiguousDomain(0, 50).excludeValue(25)
        assertTrue(d is BitsetDomain)
    }

    @Test
    fun `excludeValue should use runs domain for wide interior carve`() {
        val d = ContiguousDomain(0, DEFAULT_BITSET_THRESHOLD + 10).excludeValue(100)
        assertTrue(d is RunsDomain)
        assertEquals(0, d.min)
        assertEquals(DEFAULT_BITSET_THRESHOLD + 10, d.max)
        assertFalse(100 in d)
        assertTrue(99 in d)
        assertTrue(101 in d)
    }

    @Test
    fun `includeInteriorValue should fail for contiguous domain`() {
        assertFails { ContiguousDomain(1, 5).includeInteriorValue(3) }
    }
}
