package com.eignex.klause.solver.intdomain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurvivorsDomainTest {

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
        e.forEach { seen.add(it) }
        assertEquals(listOf(1L, 4L, 6L, 9L), seen)
    }

    @Test
    fun `withMaxAtMost should keep survivors at or below the bound`() {
        val d = SurvivorsDomain(1, 20, longArrayOf(1, 5, 9, 20))
        val e = d.withMaxAtMost(9)
        assertEquals(1, e.min)
        assertEquals(9, e.max)
        assertEquals(3, e.size)
        assertTrue(20 !in e)
    }
}
