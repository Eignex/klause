package com.eignex.klause.solver.strategy

import com.eignex.klause.solver.localsearch.strategy.ProbSat
import com.eignex.klause.solver.localsearch.strategy.WalkSat

import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.strategy.Strategy
import com.eignex.klause.solver.factor.Clause
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the unified [com.eignex.klause.solver.localsearch.strategy.FocusedLs] family —
 * both selection policies ([WalkSat] noise-greedy, [ProbSat] break-weighted), their adaptive
 * variants, and the configuration-checking knob (backed by [LocalSearchState.boolConfChange] /
 * [LocalSearchState.intConfChange]). Plain fixed-noise WalkSat solving is also covered by
 * TabuFilterTest.
 */
class FocusedLsTest {

    // ---- Configuration-checking state mechanism ----

    @Test
    fun `conf change flips false on the flipped var, stays true for neighbors, and resets on restart`() {
        val factor = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.restart()
        assertTrue(state.boolConfChange.all { it }, "restart should set all conf-change true")

        state.apply(Move.BoolFlip(0))
        assertEquals(false, state.boolConfChange[0], "flipped var's conf-change clears")
        assertEquals(true, state.boolConfChange[1], "neighbor stays conf-changed")
        assertEquals(true, state.boolConfChange[2], "neighbor stays conf-changed")

        state.restart()
        assertTrue(state.boolConfChange.all { it }, "restart resets conf-change to true")
    }

    // ---- Selection policies × {fixed, adaptive} + configuration checking ----

    private val sat3 = listOf(
        Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
        Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
        Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
    )

    private fun assertSolvesSat3(label: String, strategy: Strategy, seed: Long) {
        val problem = Problem(3, 0, emptyArray(), sat3)
        val solver = LocalSearchSolver(problem, strategy = strategy)
        val sample = solver.sample(LocalSearchParams(maxFlips = 20_000L, randomSeed = seed)).assignment
        assertNotNull(sample, "$label should find a satisfying assignment within budget")
        for (clause in sat3) {
            val sat = clause.literals.any { lit -> Lit.evaluate(lit, sample.bools[Lit.variable(lit)]) }
            assertEquals(true, sat)
        }
    }

    @Test
    fun `probsat selection solves small 3 sat`() =
        assertSolvesSat3("ProbSat", ProbSat(), seed = 23L)

    @Test
    fun `adaptive probsat selection solves small 3 sat`() =
        assertSolvesSat3("ProbSat.adaptive", ProbSat.adaptive(baselineCb = 2.06, theta = 20), seed = 7L)

    @Test
    fun `walksat with configuration checking solves small 3 sat`() =
        assertSolvesSat3("WalkSat(cc)", WalkSat(configurationChecking = true), seed = 7L)

    @Test
    fun `adaptive walksat selection solves small 3 sat`() =
        assertSolvesSat3("WalkSat.adaptive", WalkSat.adaptive(baselineNoise = 0.1, theta = 20), seed = 42L)
}
