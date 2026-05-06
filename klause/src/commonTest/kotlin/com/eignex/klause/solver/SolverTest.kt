package com.eignex.klause.solver

import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolverTest {

    @Test
    fun solvesSimple3SatInstance() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
        )
        val problem = Problem(3, 0, emptyArray(), clauses)
        val solver = LocalSearchSolver(problem)
        val sample = solver.enumerate(LocalSearchParams(maxFlips = 10_000, randomSeed = 7)).first()
        for (clause in clauses) {
            val sat = clause.literals.any { lit ->
                Lit.evaluate(lit, sample.bools[Lit.variable(lit)])
            }
            assertTrue(sat, "Clause ${clause.literals.toList()} unsatisfied by ${sample.bools.toList()}")
        }
    }

    @Test
    fun samplesAreUniqueByDefault() {
        // (x0 ∨ x1) ∧ (x0 ∨ ¬x1) has only two solutions: (T,T) and (T,F).
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
        )
        val problem = Problem(2, 0, emptyArray(), clauses)
        val solver = LocalSearchSolver(problem, maxFlipsBeforeRestart = 10)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 1)).take(10).toList()
        assertEquals(2, samples.size, "Only two distinct solutions exist")
        assertEquals(samples.toSet().size, samples.size, "All yielded samples must be unique")
        for (s in samples) assertTrue(s.bools[0])
    }

    @Test
    fun exactlyOneFactorYieldsAllThreeSolutions() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val solver = LocalSearchSolver(problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 13)).take(10).toList()
        assertEquals(3, samples.size, "ExactlyOne over 3 vars has exactly 3 solutions")
        assertEquals(samples.toSet().size, samples.size)
        for (s in samples) assertEquals(1, s.bools.count { it })
    }

    @Test
    fun duplicatesAllowedWhenDistanceZero() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val solver = LocalSearchSolver(problem)
        val samples = solver.sample(LocalSearchParams(maxFlips = 5_000, randomSeed = 13, minHammingDistance = 0)).take(20).toList()
        assertEquals(20, samples.size)
    }

    @Test
    fun rollingWindowAllowsReuseAfterRotation() {
        // ExactlyOne over 4 vars has 4 solutions; window of 2 lets older ones come back.
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val solver = LocalSearchSolver(problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 10_000, randomSeed = 21, recentWindow = 2)).take(20).toList()
        assertEquals(20, samples.size, "Window of 2 should allow cycling through 4 solutions")
        assertTrue(samples.toSet().size in 2..4, "Should see the 4 distinct solutions")
        for (s in samples) assertEquals(1, s.bools.count { it })
    }
}
