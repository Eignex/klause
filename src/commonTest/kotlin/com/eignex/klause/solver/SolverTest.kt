package com.eignex.klause.solver

import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolverTest {

    @Test
    fun solvesSimple3SatInstance() {
        // (x0 ∨ x1) ∧ (¬x0 ∨ x2) ∧ (¬x1 ∨ ¬x2)
        // Solutions: (T,F,T), (F,T,F), (T,T,F)... let's enumerate.
        // x0=F,x1=T,x2=F: clauses 1✓ 2✓ 3✓ → SAT
        // x0=T,x1=F,x2=T: 1✓ 2✓ 3✓ → SAT
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
        )
        val problem = Problem(3, clauses)
        val solver = Solver(problem, randomSeed = 7)
        val assignment = solver.sample(maxFlips = 10_000).first()
        for (clause in clauses) {
            val sat = clause.literals.any { lit ->
                Lit.evaluate(lit, assignment[Lit.variable(lit)])
            }
            assertTrue(sat, "Clause ${clause.literals.toList()} unsatisfied by ${assignment.toList()}")
        }
    }

    @Test
    fun samplesMultipleAssignments() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
        )
        val problem = Problem(2, clauses)
        val solver = Solver(problem, randomSeed = 1, maxFlipsBeforeRestart = 10)
        val samples = solver.sample(maxFlips = 5_000).take(20).toList()
        assertEquals(20, samples.size)
        // x0 must be true in every solution.
        for (s in samples) assertTrue(s[0])
    }

    @Test
    fun exactlyOneNominalLikeProblem() {
        // ExactlyOne(x0, x1, x2). Three solutions: (T,F,F), (F,T,F), (F,F,T).
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, listOf(factor))
        val solver = Solver(problem, randomSeed = 13)
        val samples = solver.sample(maxFlips = 5_000).take(30).toList()
        assertEquals(30, samples.size)
        for (s in samples) {
            val n = s.count { it }
            assertEquals(1, n, "Expected exactly one true, got ${s.toList()}")
        }
    }
}
