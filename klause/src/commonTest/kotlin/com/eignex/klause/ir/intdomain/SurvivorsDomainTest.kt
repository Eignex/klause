package com.eignex.klause.ir.intdomain

import com.eignex.klause.ir.values
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurvivorsDomainTest {

    @Test
    fun `hole iteration should stop at the largest signed word`() {
        val domain = SurvivorsDomain(
            Long.MAX_VALUE - 4, Long.MAX_VALUE,
            longArrayOf(Long.MAX_VALUE - 4, Long.MAX_VALUE - 2, Long.MAX_VALUE),
        )
        val holes = mutableListOf<Long>()

        domain.forEachHoleInRange(Long.MAX_VALUE - 4, Long.MAX_VALUE) {
            assertTrue(holes.size < 2)
            holes.add(it)
        }

        assertEquals(listOf(Long.MAX_VALUE - 3, Long.MAX_VALUE - 1), holes)
    }

    @Test
    fun `hole count should subtract survivors before saturating`() {
        val cases = listOf(
            longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, 0),
            longArrayOf(-1, 0, Long.MAX_VALUE),
        )
        for (values in cases) {
            val domain = intDomainFromSurvivors(values)

            assertEquals(Long.MAX_VALUE - 1, domain.holeCount)
        }
    }

    @Test
    fun `valueCount should count signed word survivors regardless of span`() {
        val cases = listOf(
            longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE),
            longArrayOf(Long.MIN_VALUE, -1),
            longArrayOf(-1, Long.MAX_VALUE),
        )
        for (values in cases) {
            val domain = intDomainFromSurvivors(values)

            assertEquals(values.size.toLong(), domain.valueCount)
        }
    }

    @Test
    fun `excludeValue should return same instance when value is absent`() {
        val d = SurvivorsDomain(1, 10, longArrayOf(1, 3, 7, 10))
        val e = d.excludeValue(5)
        assertTrue(e === d)
    }

    @Test
    fun `includeInteriorValue should insert value in sorted order`() {
        val d = SurvivorsDomain(1, 9, longArrayOf(1, 4, 9))
        val e = d.includeInteriorValue(6)
        val seen = mutableListOf<Long>()
        e.values.forEach { seen.add(it) }
        assertEquals(listOf(1L, 4L, 6L, 9L), seen)
    }

    @Test
    fun `withMaxAtMost should keep survivors at or below the bound`() {
        val d = SurvivorsDomain(1, 20, longArrayOf(1, 5, 9, 20))
        val e = d.withMaxAtMost(9)
        assertEquals(1, e.min)
        assertEquals(9, e.max)
        assertEquals(3, e.values.size)
        assertTrue(20 !in e)
    }
}
