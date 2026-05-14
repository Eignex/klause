package com.eignex.klause.solver

import com.eignex.klause.solver.factor.Cardinality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinimizeAllTest {

    @Test
    fun `top-k returns k samples in ascending objective order`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val solver = LocalSearchSolver(problem)
        val results = solver.minimizeAll(
            objective,
            LocalSearchParams(maxFlips = 50_000L, randomSeed = 1L),
            k = 3,
        ).toList()
        assertEquals(3, results.size)
        for (i in 0 until results.size - 1) {
            val a = objective.evaluate(results[i])
            val b = objective.evaluate(results[i + 1])
            assertTrue(a <= b, "results[$i]=$a > results[${i + 1}]=$b")
        }
        // The global optimum (3.0) must be first.
        assertEquals(3.0, objective.evaluate(results[0]))
    }

    @Test
    fun `top-k respects k=0`() {
        val problem = Problem(1, 0, emptyArray(), emptyList())
        val results = LocalSearchSolver(problem)
            .minimizeAll(LinearObjective(boolWeights = doubleArrayOf(1.0)), LocalSearchParams(), 0)
            .toList()
        assertEquals(0, results.size)
    }

    @Test
    fun `top-k yields distinct samples`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(1.0, 1.0, 1.0, 1.0))
        val results = LocalSearchSolver(problem).minimizeAll(
            objective,
            LocalSearchParams(maxFlips = 50_000L, randomSeed = 11L),
            k = 4,
        ).toList()
        assertEquals(results.size, results.toSet().size, "k-results must be distinct")
    }
}
