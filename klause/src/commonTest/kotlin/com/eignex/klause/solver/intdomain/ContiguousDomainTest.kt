package com.eignex.klause.solver.intdomain

import com.eignex.klause.config.DEFAULT_BITSET_THRESHOLD
import com.eignex.klause.solver.values
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContiguousDomainTest {

    @Test
    fun `clamp saturates a value to the domain bounds`() {
        val d = ContiguousDomain(1, 10)
        assertEquals(5, d.clamp(5))
        assertEquals(1, d.clamp(-5))
        assertEquals(10, d.clamp(100))
    }

    @Test
    fun `holeCount is zero for a span wider than Int and tracks holes carved into it`() {
        assertEquals(0L, ContiguousDomain(1, 10).holeCount)
        assertEquals(0L, ContiguousDomain(0, 5_000_000_000L).holeCount)
        assertEquals(1L, ContiguousDomain(0, 5_000_000_000L).excludeValue(7L).holeCount)
    }

    @Test
    fun `a domain has values exactly when they can be indexed`() {
        assertNotNull(ContiguousDomain(1, 10).spanOrNull())
        assertNull(ContiguousDomain(0, 5_000_000_000L).spanOrNull())
        assertNull(ContiguousDomain(Long.MIN_VALUE, Long.MAX_VALUE).spanOrNull())
    }

    @Test
    fun `a caller that cannot afford the values is refused them`() {
        assertNotNull(ContiguousDomain(1, 10).spanOrNull(maxValues = 10))
        assertNull(ContiguousDomain(1, 10).spanOrNull(maxValues = 9))
    }

    @Test
    fun `no cap hands back values an index cannot address`() {
        assertNull(ContiguousDomain(0, 5_000_000_000L).spanOrNull(maxValues = Long.MAX_VALUE))
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
        assertEquals(4, e.values.size)
        assertFalse(1 in e)
        assertTrue(2 in e)
    }

    @Test
    fun `excludeValue at max retreats and trims`() {
        val d = ContiguousDomain(1, 5)
        val e = d.excludeValue(5)
        assertEquals(1, e.min)
        assertEquals(4, e.max)
        assertEquals(4, e.values.size)
        assertFalse(5 in e)
        assertTrue(4 in e)
    }

    @Test
    fun `excludeValue interior creates sparse domain`() {
        val d = ContiguousDomain(1, 5)
        val e = d.excludeValue(3)
        assertEquals(1, e.min)
        assertEquals(5, e.max)
        assertEquals(4, e.values.size)
        assertTrue(2 in e)
        assertFalse(3 in e)
        assertTrue(4 in e)
        val seen = mutableListOf<Long>()
        e.values.forEach { seen.add(it) }
        assertEquals(listOf(1L, 2L, 4L, 5L), seen)
    }

    @Test
    fun `excludeValue stacking accumulates holes`() {
        val d = ContiguousDomain(1, 10).excludeValue(4).excludeValue(7).excludeValue(5)
        assertEquals(1, d.min)
        assertEquals(10, d.max)
        assertEquals(7, d.values.size)
        assertFalse(4 in d)
        assertFalse(5 in d)
        assertFalse(7 in d)
        assertTrue(6 in d)
        val seen = mutableListOf<Long>()
        d.values.forEach { seen.add(it) }
        assertEquals(listOf(1L, 2L, 3L, 6L, 8L, 9L, 10L), seen)
    }

    @Test
    fun `excludeValue at min jumps past pre-existing adjacent holes`() {
        val d = ContiguousDomain(1, 10).excludeValue(2).excludeValue(3)
        val e = d.excludeValue(1)
        assertEquals(4, e.min)
        assertEquals(10, e.max)
        assertEquals(7, e.values.size)
        val seen = mutableListOf<Long>()
        e.values.forEach { seen.add(it) }
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L, 9L, 10L), seen)
    }

    @Test
    fun `excludeValue collapsing to single value`() {
        val d = ContiguousDomain(1, 3).excludeValue(2).excludeValue(3)
        assertEquals(1, d.min)
        assertEquals(1, d.max)
        assertEquals(1, d.values.size)
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
        assertEquals(5, e.values.size)
    }

    @Test
    fun `withMaxAtMost skips holes`() {
        val d = ContiguousDomain(1, 10).excludeValue(6).excludeValue(7)
        val e = d.withMaxAtMost(7)
        assertEquals(1, e.min)
        assertEquals(5, e.max)
        assertEquals(5, e.values.size)
    }

    @Test
    fun `excludeValue should use bitset domain for narrow interior carve`() {
        val d = ContiguousDomain(0, 50).excludeValue(25)
        assertTrue(d is BitsetDomain)
    }

    @Test
    fun `excludeValue should use runs domain for wide interior carve`() {
        val d = ContiguousDomain(0, (DEFAULT_BITSET_THRESHOLD + 10).toLong()).excludeValue(100)
        assertTrue(d is RunsDomain)
        assertEquals(0, d.min)
        assertEquals((DEFAULT_BITSET_THRESHOLD + 10).toLong(), d.max)
        assertFalse(100 in d)
        assertTrue(99 in d)
        assertTrue(101 in d)
    }

    @Test
    fun `includeInteriorValue should fail for contiguous domain`() {
        assertFails { ContiguousDomain(1, 5).includeInteriorValue(3) }
    }
}
