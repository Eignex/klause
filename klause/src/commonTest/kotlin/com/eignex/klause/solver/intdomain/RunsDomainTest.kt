package com.eignex.klause.solver.intdomain

import com.eignex.klause.config.DEFAULT_BITSET_THRESHOLD
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val wide = ContiguousDomain(0, DEFAULT_BITSET_THRESHOLD + 20).excludeValue(100)
        val restored = wide.includeInteriorValue(100)
        assertTrue(restored is ContiguousDomain)
        assertTrue(100 in restored)
    }

    @Test
    fun `withMinAtLeast should skip holes at the lower bound`() {
        val d = ContiguousDomain(0, DEFAULT_BITSET_THRESHOLD + 20).excludeValue(10).excludeValue(11)
        val tightened = d.withMinAtLeast(10)
        assertEquals(12, tightened.min)
    }

    @Test
    fun `forEachHoleInRange should report only holes inside the given slice`() {
        val d = ContiguousDomain(0, DEFAULT_BITSET_THRESHOLD + 20).excludeValue(100).excludeValue(150)
        val holes = mutableListOf<Int>()
        d.forEachHoleInRange(90, 120) { holes.add(it) }
        assertEquals(listOf(100), holes)
    }
}
