package com.eignex.klause.brute

import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BruteForceSolverTest {
    @Test
    fun `solve should report unknown when the assignment budget prevents search`() {
        val solver = BruteForceSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())
        val params = listOf(BruteForceParams(maxSteps = 0), BruteForceParams(maxInstructions = 0))

        for (param in params) {
            val result = solver.solve(param)

            assertEquals(TerminationReason.BudgetExhausted, assertIs<SolveResult.Unknown>(result).reason)
        }
    }

    @Test
    fun `minimize should prove optimality when the budget covers the whole assignment space`() {
        val solver = BruteForceSolver(Problem(0, 0, emptyArray(), emptyArray()).bake())

        val result = solver.minimize(LinearObjective(), BruteForceParams(maxSteps = 1))

        assertIs<MinimizeResult.Optimal>(result)
    }

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
