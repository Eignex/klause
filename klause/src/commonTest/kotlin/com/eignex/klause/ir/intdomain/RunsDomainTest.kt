package com.eignex.klause.ir.intdomain

import com.eignex.klause.config.DEFAULT_BITSET_THRESHOLD
import com.eignex.klause.ir.values
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunsDomainTest {
    @Test
    fun `interior restoration should retain runs at signed word endpoints`() {
        val domain = intDomainFromSurvivors(
            longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE),
        )

        val restored = domain.includeInteriorValue(0)

        assertEquals(
            listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, 0, Long.MAX_VALUE - 1, Long.MAX_VALUE),
            (0 until restored.values.size).map { restored.values.valueAt(it) },
        )
    }

    @Test
    fun `holeCount should saturate across signed word gaps`() {
        val values = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val domain = intDomainFromSurvivors(values)

        assertEquals(Long.MAX_VALUE, domain.holeCount)
    }

    @Test
    fun `value iteration should stop at the maximum signed word`() {
        val values = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val domain = intDomainFromSurvivors(values)
        val seen = mutableListOf<Long>()

        domain.values.forEach { seen.add(it) }

        assertEquals(values.toList(), seen)
    }

    @Test
    fun `holeCount should stay exact when size saturates on a wide span`() {
        val d = ContiguousDomain(0, 5_000_000_000L).excludeValue(2_500_000_000L)
        assertTrue(d is RunsDomain)
        assertEquals(Int.MAX_VALUE, d.size)
        assertEquals(1L, d.holeCount)
    }

    @Test
    fun `enumerable should follow the exact count not the saturated size`() {
        val wide = ContiguousDomain(0, 5_000_000_000L).excludeValue(2_500_000_000L)
        assertFalse((wide.spanOrNull() != null))
        val narrow = ContiguousDomain(0, 100_000).excludeValue(50_000)
        assertTrue(narrow is RunsDomain)
        assertTrue((narrow.spanOrNull() != null))
    }

    @Test
    fun `lower and higher should cross a hole when size saturates`() {
        val d = ContiguousDomain(0, 5_000_000_000L).excludeValue(2_500_000_000L)
        assertEquals(2_499_999_999L, d.lower(2_500_000_001L))
        assertEquals(2_500_000_001L, d.higher(2_499_999_999L))
        assertEquals(2_499_999_999L, d.clamp(2_500_000_000L))
    }

    @Test
    fun `the interval-run rep should stack excludes and includes`() {
        var d = ContiguousDomain(0, 100_000).excludeValue(50_000).excludeValue(25_000).excludeValue(75_000)
        assertTrue(d is RunsDomain)
        assertEquals(99_998, d.size)
        assertTrue(50_000 !in d)
        assertTrue(25_000 !in d)
        d = d.includeInteriorValue(50_000)
        assertTrue(50_000 in d)
        assertTrue(25_000 !in d)
        assertEquals(99_999, d.values.size)
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
