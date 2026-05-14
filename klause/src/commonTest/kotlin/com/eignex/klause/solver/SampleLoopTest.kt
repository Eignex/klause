package com.eignex.klause.solver

import com.eignex.klause.solver.localsearch.LocalSearchSolver

import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.factor.Cardinality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleLoopTest {

    @Test
    fun `same seed yields identical sequence`() {
        val problem = exactlyOneOver4()
        val solver = LocalSearchSolver(problem)
        val a = solver.samples(LocalSearchParams(maxFlips = 10_000, randomSeed = 42)).take(10).toList()
        val b = solver.samples(LocalSearchParams(maxFlips = 10_000, randomSeed = 42)).take(10).toList()
        assertEquals(a, b, "Same Solver, same seed, same params → identical sequence")
    }

    @Test
    fun `different seeds explore different sequences`() {
        val problem = exactlyOneOver4()
        val solver = LocalSearchSolver(problem)
        val a = solver.samples(LocalSearchParams(maxFlips = 10_000, randomSeed = 1)).take(8).toList()
        val b = solver.samples(LocalSearchParams(maxFlips = 10_000, randomSeed = 9)).take(8).toList()
        assertTrue(a != b, "Different seeds should produce different sample sequences")
    }

    private fun exactlyOneOver4(): Problem {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        return Problem(4, 0, emptyArray(), listOf(factor))
    }
}
