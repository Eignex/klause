package com.eignex.klause.solver.intdomain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AbstractIntDomainTest {

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
}
