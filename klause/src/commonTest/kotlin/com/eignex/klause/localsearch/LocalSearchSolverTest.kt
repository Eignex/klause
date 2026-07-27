package com.eignex.klause.localsearch

import com.eignex.klause.compile.compile
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.allDifferent
import com.eignex.klause.solver.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalSearchSolverTest {

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

    private class ThreeDistinct : VariableSchema() {
        val a by intVar(min = 1, max = 3)
        val b by intVar(min = 1, max = 3)
        val c by intVar(min = 1, max = 3)
        val unique by constraint { allDifferent(a, b, c) }
    }

    @Test
    fun `constructor accepts a compiled problem`() {
        val compiled = ThreeDistinct().compile()
        val result = LocalSearchSolver(compiled).solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 0))
        assertTrue(result is SolveResult.Sat)
    }

    @Test
    fun `constructor accepts a schema and compiles it`() {
        val result = LocalSearchSolver(ThreeDistinct()).solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 0))
        assertTrue(result is SolveResult.Sat)
    }
}
