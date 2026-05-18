package com.eignex.klause.solver.strategy


import com.eignex.klause.solver.localsearch.strategy.Ddfw

import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DdfwTest {

    @Test
    fun `ddfw solves small 3 sat`() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
        )
        val problem = Problem(3, 0, emptyArray(), clauses)
        val solver = LocalSearchSolver(problem, strategy = Ddfw())
        val sample = solver.sample(LocalSearchParams(maxFlips = 20_000L, randomSeed = 7L)).assignment
        assertNotNull(sample, "DDFW should find a satisfying assignment within budget")
        for (clause in clauses) {
            val sat = clause.literals.any { lit ->
                Lit.evaluate(lit, sample.bools[Lit.variable(lit)])
            }
            assertEquals(true, sat, "Clause unsatisfied by $sample")
        }
    }

    @Test
    fun `weights change after flips on overconstrained problem`() {

        val factors = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
            Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), min = 0, max = 0),
        )
        val problem = Problem(3, 0, emptyArray(), factors)
        val state = LocalSearchState(problem, Random(42L))
        state.restart()
        val ddfw = Ddfw()
        repeat(80) {
            val move = ddfw.pickMove(state) ?: return@repeat
            state.apply(move)
        }
        val drifted = state.factorWeights.any { it != 1.0 }
        assertTrue(drifted, "DDFW should have shifted some factor weights after 80 steps; " +
            "got ${state.factorWeights.toList()}")
    }

    @Test
    fun `weights survive restart`() {
        val factors = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
        )
        val problem = Problem(2, 0, emptyArray(), factors)
        val state = LocalSearchState(problem, Random(0L))
        state.restart()
        val ddfw = Ddfw()
        repeat(40) {
            val move = ddfw.pickMove(state) ?: return@repeat
            state.apply(move)
        }
        val before = state.factorWeights.copyOf()
        state.restart()
        for (i in before.indices) {
            assertEquals(before[i], state.factorWeights[i], 1e-12,
                "factorWeights[$i] reset by restart: was ${before[i]}, now ${state.factorWeights[i]}")
        }
    }

    @Test
    fun `total weight drift is bounded on connected problem`() {
        val factors = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
        )
        val problem = Problem(3, 0, emptyArray(), factors)
        val state = LocalSearchState(problem, Random(11L))
        state.restart()
        val initialTotal = state.factorWeights.sum()
        val ddfw = Ddfw()
        val steps = 200
        repeat(steps) {
            val move = ddfw.pickMove(state) ?: return@repeat
            state.apply(move)
        }
        val finalTotal = state.factorWeights.sum()

        val drift = finalTotal - initialTotal
        assertTrue(drift in 0.0..(steps * factors.size * 1.0),
            "total weight drifted by $drift over $steps steps")
    }
}
