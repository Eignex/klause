package com.eignex.klause.ir.intdomain

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.ir.DomainStorageSettings
import com.eignex.klause.ir.IntDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AbstractIntDomainTest {

    @Test
    fun `domain narrowing uses its captured bitset threshold`() {
        val small = IntDomain(0, 100, DomainStorageSettings(8))
        val large = IntDomain(0, 100, DomainStorageSettings(128))
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = KlauseConfig.DEFAULT.copy(bitsetThreshold = 1)

            assertIs<RunsDomain>(small.excludeValue(50))
            assertIs<BitsetDomain>(large.excludeValue(50))
            assertEquals(small.excludeValue(50), large.excludeValue(50))
        } finally {
            KlauseConfig.current = saved
        }
    }

    @Test
    fun `domain restoration keeps the storage policy through representation changes`() {
        val domain = IntDomain(0, 100, DomainStorageSettings(8)).excludeValue(50)

        val restored = domain.includeInteriorValue(50)
        val narrowed = restored.withMaxAtMost(90).excludeValues(longArrayOf(20, 30))

        assertIs<RunsDomain>(narrowed)
        assertEquals(0L, narrowed.min)
        assertEquals(90L, narrowed.max)
        assertTrue(50L in narrowed)
    }


    @Test
    fun `excludeValues empty list is identity`() {
        val d = ContiguousDomain(1, 5)
        assertTrue(d.excludeValues(LongArray(0)) === d)
    }

    @Test
    fun `excludeValues with no present value is identity`() {
        val d = ContiguousDomain(1, 5).excludeValue(3)
        assertTrue(d.excludeValues(longArrayOf(-1, 0, 3, 6, 9)) === d)
    }

    @Test
    fun `excludeValues should return null when all values are removed`() {
        val d = ContiguousDomain(3, 5)
        assertEquals(null, d.excludeValues(longArrayOf(3, 4, 5)))
    }

    @Test
    fun `equals should compare by value set across representations`() {
        val a = ContiguousDomain(0, 10).excludeValue(5)
        val b = SurvivorsDomain(0, 10, longArrayOf(0, 1, 2, 3, 4, 6, 7, 8, 9, 10))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals respects holes`() {
        val a = ContiguousDomain(1, 5).excludeValue(3)
        val b = ContiguousDomain(1, 5).excludeValue(3)
        val c = ContiguousDomain(1, 5).excludeValue(4)
        val d = ContiguousDomain(1, 5)
        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a != d)
    }

    @Test
    fun `equals and hashCode stay span-independent on non-enumerable domains`() {
        val a = ContiguousDomain(0, 5_000_000_000L).excludeValue(2_500_000_000L)
        val b = ContiguousDomain(0, 5_000_000_000L).excludeValue(2_500_000_000L)
        val c = ContiguousDomain(0, 5_000_000_000L).excludeValue(2_500_000_001L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
        assertTrue(a != ContiguousDomain(0, 5_000_000_000L))
    }
}
