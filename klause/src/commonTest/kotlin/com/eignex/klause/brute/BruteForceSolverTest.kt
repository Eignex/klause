package com.eignex.klause.brute

import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BruteForceSolverTest {
    @Test
    fun `admission should use survivor cardinalities across signed word spans`() {
        val domain = intDomainFromSurvivors(longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE))
        val problem = Problem(0, 2, arrayOf(domain, domain), emptyArray()).bake()

        assertTrue(BruteForceSolver.fits(problem, cap = 9))
        assertFalse(BruteForceSolver.fits(problem, cap = 8))
    }

    @Test
    fun `enumeration should visit every sparse signed word assignment once`() {
        val values = longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE)
        val domain = intDomainFromSurvivors(values)
        val problem = Problem(0, 2, arrayOf(domain, domain), emptyArray()).bake()

        val actual = BruteForceSolver(problem).enumerate(BruteForceParams(randomSeed = 0L))
            .map { it.ints.toList() }.toList()

        val expected = values.flatMap { x -> values.map { y -> listOf(x, y) } }.toSet()
        assertEquals(9, actual.size)
        assertEquals(expected, actual.toSet())
    }
}
