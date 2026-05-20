package com.eignex.klause.solver

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.AdaptivePerturbationRestart
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LubyRestart

import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RestartPolicyTest {

    @Test
    fun `fixed cadence triggers at boundary`() {
        val p = FixedCadenceRestart(maxFlipsBeforeRestart = 100)
        assertEquals(false, p.shouldRestart(0))
        assertEquals(false, p.shouldRestart(99))
        assertEquals(true, p.shouldRestart(100))
        assertEquals(true, p.shouldRestart(1_000_000))
    }

    @Test
    fun `adaptive perturbation falls back when no best`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true),
        ))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.restart()

        state.assignment.setBool(0, false)
        state.assignment.setBool(1, false)
        state.assignment.setBool(2, false)

        AdaptivePerturbationRestart().restart(state, bestSoFar = null)

        val countTrue = (0..2).count { state.assignment.boolValue(it) }
        val expectedHard = if (countTrue == 1) 0 else 1
        assertEquals(expectedHard, state.cost)
    }

    @Test
    fun `adaptive perturbation anchors to best then perturbs`() {

        val problem = Problem(6, 0, emptyArray(), emptyList())
        val state = LocalSearchState(problem, Random(0))
        state.restart()
        for (b in 0..5) state.assignment.setBool(b, false)

        val best = Sample(bools = booleanArrayOf(true, true, true, true, true, true), ints = intArrayOf())
        val policy = AdaptivePerturbationRestart(perturbationStrength = 2)
        policy.restart(state, bestSoFar = best)

        val differences = (0..5).count { state.assignment.boolValue(it) != best.bools[it] }
        assertTrue(differences in 0..2,
            "perturbed assignment differs from bestSoFar in $differences positions, expected 0..2")
    }

    @Test
    fun `adaptive perturbation restart integrates with local search optimizer`() {

        val problem = Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4)),
        )
        val objective = LinearObjective(intCoefficients = doubleArrayOf(1.0, 2.0, 3.0, 4.0))

        val fixed = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart())
        val adaptive = LocalSearchSolver(problem, restartPolicy = AdaptivePerturbationRestart())

        val a = fixed.minimize(objective, LocalSearchParams(maxFlips = 100_000L, randomSeed = 1L)).assignment
        val b = adaptive.minimize(objective, LocalSearchParams(maxFlips = 100_000L, randomSeed = 1L)).assignment
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(objective.evaluate(a), objective.evaluate(b))
    }

    @Test
    fun `luby triggers at cadence boundary`() {
        val p = LubyRestart(unit = 100)
        assertEquals(false, p.shouldRestart(0))
        assertEquals(false, p.shouldRestart(99))
        assertEquals(true, p.shouldRestart(100))
        assertEquals(true, p.shouldRestart(1_000_000))
    }

    @Test
    fun `luby sequence matches knuth`() {
        val p = LubyRestart(unit = 1)
        val problem = Problem(1, 0, emptyArray(), emptyList())
        val state = LocalSearchState(problem, Random(0))
        state.restart()

        val emitted = mutableListOf<Int>()
        repeat(15) {

            var n = 1
            while (!p.shouldRestart(n)) n++
            emitted += n
            p.restart(state, bestSoFar = null)
        }

        assertEquals(listOf(1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8), emitted)
    }

    @Test
    fun `luby integrates with local search solver`() {

        val clauses = listOf(
            com.eignex.klause.solver.factor.Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            com.eignex.klause.solver.factor.Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
            com.eignex.klause.solver.factor.Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
        )
        val problem = Problem(3, 0, emptyArray(), clauses)
        val solver = LocalSearchSolver(problem, restartPolicy = LubyRestart(unit = 50))
        val sample = solver.sample(LocalSearchParams(maxFlips = 20_000L, randomSeed = 9L)).assignment
        assertNotNull(sample)
    }
}
