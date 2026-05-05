package com.eignex.klause.solver

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
        val solver = Solver(problem, randomSeed = 7)
        val sample = solver.sample(maxFlips = 10_000).first()
        for (clause in clauses) {
            val sat = clause.literals.any { lit ->
                Lit.evaluate(lit, sample.bools[Lit.variable(lit)])
            }
            assertTrue(sat, "Clause ${clause.literals.toList()} unsatisfied by ${sample.bools.toList()}")
        }
    }

    @Test
    fun samplesMultipleAssignments() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
        )
        val problem = Problem(2, 0, emptyArray(), clauses)
        val solver = Solver(problem, randomSeed = 1, maxFlipsBeforeRestart = 10)
        val samples = solver.sample(maxFlips = 5_000).take(20).toList()
        assertEquals(20, samples.size)
        for (s in samples) assertTrue(s.bools[0])
    }

    @Test
    fun exactlyOneNominalLikeProblem() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val solver = Solver(problem, randomSeed = 13)
        val samples = solver.sample(maxFlips = 5_000).take(30).toList()
        assertEquals(30, samples.size)
        for (s in samples) {
            val n = s.bools.count { it }
            assertEquals(1, n, "Expected exactly one true, got ${s.bools.toList()}")
        }
    }
}
