package com.eignex.klause.solver.intdomain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurvivorsDomainTest {

    @Test
    fun `excludeValue should return same instance when value is absent`() {
        val d = SurvivorsDomain(1, 10, intArrayOf(1, 3, 7, 10))
        val e = d.excludeValue(5)
        assertTrue(e === d)
    }

    @Test
    fun `includeInteriorValue should insert value in sorted order`() {
        val d = SurvivorsDomain(1, 9, intArrayOf(1, 4, 9))
        val e = d.includeInteriorValue(6)
        val seen = mutableListOf<Int>()
        e.forEach { seen.add(it) }
        assertEquals(listOf(1, 4, 6, 9), seen)
    }

    @Test
    fun `withMaxAtMost should keep survivors at or below the bound`() {
        val d = SurvivorsDomain(1, 20, intArrayOf(1, 5, 9, 20))
        val e = d.withMaxAtMost(9)
        assertEquals(1, e.min)
        assertEquals(9, e.max)
        assertEquals(3, e.size)
        assertTrue(20 !in e)
    }
}
