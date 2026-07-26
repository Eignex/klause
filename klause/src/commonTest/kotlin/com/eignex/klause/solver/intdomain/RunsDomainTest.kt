package com.eignex.klause.solver.intdomain

import com.eignex.klause.config.DEFAULT_BITSET_THRESHOLD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunsDomainTest {

    @Test
    fun `wide span interior exclude uses interval-run rep`() {
        val d = ContiguousDomain(0, 10_000).excludeValue(5_000)
        assertTrue(d is RunsDomain)
        assertEquals(0, d.min)
        assertEquals(10_000, d.max)
        assertEquals(10_000, d.size)
        assertTrue(4_999 in d)
        assertTrue(5_001 in d)
    }

    @Test
    fun `holeCount stays exact when size saturates on a wide span`() {
        val d = ContiguousDomain(0, 5_000_000_000L).excludeValue(2_500_000_000L)
        assertTrue(d is RunsDomain)
        assertEquals(Int.MAX_VALUE, d.size)
        assertEquals(1L, d.holeCount)
    }

    @Test
    fun `enumerable follows the exact count not the saturated size`() {
        val wide = ContiguousDomain(0, 5_000_000_000L).excludeValue(2_500_000_000L)
        assertFalse(wide.enumerable)
        val narrow = ContiguousDomain(0, 100_000).excludeValue(50_000)
        assertTrue(narrow is RunsDomain)
        assertTrue(narrow.enumerable)
    }

    @Test
    fun `sizeLong counts wide runs exactly`() {
        val wide = ContiguousDomain(0, 5_000_000_000L).excludeValue(2_500_000_000L)
        assertEquals(5_000_000_000L, wide.sizeLong)
    }

    @Test
    fun `lower and higher cross a hole correctly when size saturates`() {
        val d = ContiguousDomain(0, 5_000_000_000L).excludeValue(2_500_000_000L)
        assertEquals(2_499_999_999L, d.lower(2_500_000_001L))
        assertEquals(2_500_000_001L, d.higher(2_499_999_999L))
        assertEquals(2_499_999_999L, d.clamp(2_500_000_000L))
    }

    @Test
    fun `interval-run rep stacks excludes and includes`() {
        var d = ContiguousDomain(0, 100_000).excludeValue(50_000).excludeValue(25_000).excludeValue(75_000)
        assertTrue(d is RunsDomain)
        assertEquals(99_998, d.size)
        assertTrue(50_000 !in d)
        assertTrue(25_000 !in d)
        d = d.includeInteriorValue(50_000)
        assertTrue(50_000 in d)
        assertTrue(25_000 !in d)
        assertEquals(99_999, d.size)
        d = d.excludeValue(25_001)
        assertTrue(25_001 !in d)
        d = d.includeInteriorValue(25_001)
        assertTrue(25_001 in d)
        assertTrue(25_000 !in d)
    }

    @Test
    fun `includeInteriorValue should bridge adjacent runs into a contiguous domain`() {
        val wide = ContiguousDomain(0, (DEFAULT_BITSET_THRESHOLD + 20).toLong()).excludeValue(100)
        val restored = wide.includeInteriorValue(100)
        assertTrue(restored is ContiguousDomain)
        assertTrue(100 in restored)
    }

    @Test
    fun `withMinAtLeast should skip holes at the lower bound`() {
        val d = ContiguousDomain(0, (DEFAULT_BITSET_THRESHOLD + 20).toLong()).excludeValue(10).excludeValue(11)
        val tightened = d.withMinAtLeast(10)
        assertEquals(12, tightened.min)
    }

    @Test
    fun `forEachHoleInRange should report only holes inside the given slice`() {
        val d = ContiguousDomain(0, (DEFAULT_BITSET_THRESHOLD + 20).toLong()).excludeValue(100).excludeValue(150)
        val holes = mutableListOf<Long>()
        d.forEachHoleInRange(90, 120) { holes.add(it) }
        assertEquals(listOf(100L), holes)
    }
}
