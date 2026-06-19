package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.*
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolverTest {

    @Test
    fun `solves simple 3 sat instance`() {
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
    fun `samples cover all solutions on tiny problem`() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
        )
        val problem = Problem(2, 0, emptyArray(), clauses)
        val solver = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 10))
        val samples = solver.samples(LocalSearchParams(maxFlips = 5_000, randomSeed = 1)).take(20).toList()
        assertEquals(2, samples.toSet().size, "Both distinct solutions should be sampled")
        for (s in samples) assertTrue(s.bools[0])
    }

    @Test
    fun `exactly one factor yields all three solutions`() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val solver = LocalSearchSolver(problem)
        val samples = solver.samples(LocalSearchParams(maxFlips = 5_000, randomSeed = 13)).take(30).toList()
        assertEquals(3, samples.toSet().size, "ExactlyOne over 3 vars has exactly 3 distinct solutions")
        for (s in samples) assertEquals(1, s.bools.count { it })
    }
}
