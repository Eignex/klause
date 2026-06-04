package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveStats
import com.eignex.klause.solver.factor.Cardinality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalSearchImprovementsTest {

    @Test
    fun `improvements yields strictly decreasing intermediate bests then a terminal verdict`() {
        // 4 bools with exactly-one true; weights `10, 5, 8, 3` so the optimum picks bool 3
        // (weight 3). Pre-feasibility wandering means LS may discover non-optimal feasibles
        // first; each strict improvement is yielded as a BestFound; the terminal yield
        // carries the same `BestFound(reason = BudgetExhausted)` (LS is incomplete and
        // never proves Optimal).
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val obj = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val seq = LocalSearchSolver(problem).improvements(
            obj,
            LocalSearchParams(maxFlips = 50_000L, randomSeed = 1L),
        ).toList()
        assertTrue(seq.isNotEmpty(), "improvements must yield at least the terminal verdict")
        // Last entry is the terminal verdict — a BestFound (LS can't prove Optimal).
        val terminal = seq.last()
        val termBest = assertIs<MinimizeResult.BestFound>(terminal)
        // Earlier yields (if any) are strictly-decreasing BestFound events ending at the
        // same objective as the terminal.
        var prev = Double.POSITIVE_INFINITY
        for (m in seq.dropLast(1)) {
            val bf = assertIs<MinimizeResult.BestFound>(m)
            assertTrue(
                bf.objective < prev,
                "improvements must strictly decrease; ${bf.objective} after $prev",
            )
            prev = bf.objective
        }
        assertTrue(
            termBest.objective <= prev,
            "terminal yield's objective must match the last intermediate or be no worse",
        )
        // On this small instance the LS engine should reach the optimum (3.0) inside the
        // budget.
        assertEquals(3.0, termBest.objective, "expected LS to reach the global optimum 3.0")
    }

    @Test
    fun `minimize equals improvements last`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val obj = LinearObjective(boolWeights = doubleArrayOf(1.0, 1.0, 1.0, 1.0))
        val solver = LocalSearchSolver(problem)
        val params = LocalSearchParams(maxFlips = 10_000L, randomSeed = 0L)
        val viaMinimize = solver.minimize(obj, params)
        val viaImprovementsLast = solver.improvements(obj, params).last()
        // Two separate runs report their own wall-clock stats; the verdicts must agree.
        assertEquals(viaMinimize.withoutStats(), viaImprovementsLast.withoutStats())
    }

    @Test
    fun `improvements is lazy — consumer can take just the first event`() {
        // Build a problem where finding feasibility requires some non-trivial LS work, so
        // the first BestFound only arrives after enough flips. Taking just `first()` should
        // short-circuit and not exhaust the maxFlips budget.
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val obj = LinearObjective(boolWeights = doubleArrayOf(1.0, 1.0, 1.0, 1.0))
        // With Long.MAX_VALUE budget, a non-lazy implementation would never return.
        // The lazy Sequence path must yield the first improvement and stop.
        val first = LocalSearchSolver(problem).improvements(
            obj,
            LocalSearchParams(maxFlips = Long.MAX_VALUE, randomSeed = 2L),
        ).first()
        assertIs<MinimizeResult.BestFound>(first)
    }
}

/** Strip the per-run stats sidecar so verdicts from separate runs compare structurally. */
private fun MinimizeResult.withoutStats(): MinimizeResult = when (this) {
    is MinimizeResult.Optimal -> copy(stats = SolveStats.EMPTY)
    is MinimizeResult.BestFound -> copy(stats = SolveStats.EMPTY)
    is MinimizeResult.Infeasible -> copy(stats = SolveStats.EMPTY)
    is MinimizeResult.Unknown -> copy(stats = SolveStats.EMPTY)
}
