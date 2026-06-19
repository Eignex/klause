package com.eignex.klause.solver

import com.eignex.klause.solver.factor.bool.Clause
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssumptionsTest {

    @Test
    fun `sample should fix bool assumption to requested value`() {
        // Two bools, no constraints. Without assumptions both can be either value.
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val solver = LocalSearchSolver(problem)
        repeat(20) { seed ->
            val sample = solver.sample(
                LocalSearchParams(randomSeed = seed.toLong(), assumptions = Assumptions(bools = mapOf(0 to true))),
            ).assignment
            assertNotNull(sample)
            assertEquals(true, sample.bools[0], "bool 0 must be fixed to true (seed=$seed)")
        }
    }

    @Test
    fun `sample should fix int assumption to requested value`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(min = 0, max = 100)),
            factors = emptyArray(),
        )
        val solver = LocalSearchSolver(problem)
        repeat(20) { seed ->
            val sample = solver.sample(
                LocalSearchParams(randomSeed = seed.toLong(), assumptions = Assumptions(ints = mapOf(0 to 42))),
            ).assignment
            assertNotNull(sample)
            assertEquals(42, sample.ints[0])
        }
    }

    @Test
    fun `minimize should respect bool assumption`() {
        // 4 bools, objective rewards every true; without assumptions optimal is "all true".
        val problem = Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val solver = LocalSearchSolver(problem)
        val obj = LinearObjective(boolWeights = longArrayOf(-1L, -1L, -1L, -1L))
        val sample = solver.minimize(
            obj,
            LocalSearchParams(
                randomSeed = 3L,
                maxFlips = 50_000L,
                assumptions = Assumptions(bools = mapOf(2 to false)),
            ),
        ).assignment
        assertNotNull(sample)
        assertEquals(false, sample.bools[2], "bool 2 must stay false despite negative weight")
        assertEquals(true, sample.bools[0])
        assertEquals(true, sample.bools[1])
        assertEquals(true, sample.bools[3])
    }

    @Test
    fun `samples should honour assumptions across the stream`() {
        val problem = Problem(numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val solver = LocalSearchSolver(problem)
        val draws = solver.samples(
            LocalSearchParams(
                randomSeed = 5L,
                assumptions = Assumptions(bools = mapOf(1 to true)),
            ),
        ).take(10).toList()
        assertTrue(draws.isNotEmpty())
        for (s in draws) {
            assertEquals(true, s.bools[1])
        }
    }

    @Test
    fun `assumptions should compose with hard constraints`() {
        // Clause: bool0 OR bool1. Assume bool0 = false → bool1 must be true.
        val clauses = listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))))
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = clauses)
        val solver = LocalSearchSolver(problem)
        val sample = solver.sample(
            LocalSearchParams(
                randomSeed = 9L,
                maxFlips = 10_000,
                assumptions = Assumptions(bools = mapOf(0 to false)),
            ),
        ).assignment
        assertNotNull(sample)
        assertEquals(false, sample.bools[0])
        assertEquals(true, sample.bools[1])
    }
}
