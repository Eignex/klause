package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalSearchImprovementsTest {

    @Test
    fun `improvements yields strictly decreasing intermediate bests then a terminal verdict`() {
        // Weights 10, 5, 8, 3 over exactly-one: optimum picks bool 3. Each strict improvement is a
        // BestFound; the terminal yield is also a BestFound since LS never proves Optimal.
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val obj = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val seq = LocalSearchSolver(problem).improvements(
            obj,
            LocalSearchParams(maxFlips = 4_000L, randomSeed = 1L),
        ).toList()
        assertTrue(seq.isNotEmpty(), "improvements must yield at least the terminal verdict")
        val terminal = seq.last()
        val termBest = assertIs<MinimizeResult.BestFound>(terminal)
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
        assertEquals(3.0, termBest.objective, "expected LS to reach the global optimum 3.0")
    }

    @Test
    fun `a warm-started optimum survives greedy repair as the first incumbent`() {
        // 40 bools under an at-most-one cardinality, minimising the count of true bools: the optimum is
        // all-false (objective 0). Warm-start from it. The greedy-repair pass on a ≥32-variable restart
        // is objective-blind — it accepts any flip that keeps cost 0 — so it would set one bool true
        // (objective 1) and surface that as the first incumbent unless a warm start skips the pass.
        val n = 40
        val factor = Cardinality.atMostOne(IntArray(n) { Lit.make(it, true) })
        val problem = Problem(n, 0, emptyArray(), listOf(factor))
        val obj = LinearObjective(boolWeights = LongArray(n) { 1L })
        val optimum = Sample(BooleanArray(n) { false }, intArrayOf())
        val first = LocalSearchSolver(problem).improvements(
            obj,
            LocalSearchParams(maxFlips = 4_000L, randomSeed = 1L, initialAssignment = optimum),
        ).first()
        val bf = assertIs<MinimizeResult.BestFound>(first)
        assertEquals(0.0, bf.objective, "warm start must yield the seed objective, not a scrambled one")
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
        val obj = LinearObjective(boolWeights = longArrayOf(1L, 1L, 1L, 1L))
        val solver = LocalSearchSolver(problem)
        val params = LocalSearchParams(maxFlips = 2_000L, randomSeed = 0L)
        val viaMinimize = solver.minimize(obj, params)
        val viaImprovementsLast = solver.improvements(obj, params).last()
        // Two separate runs report their own wall-clock stats; the verdicts must agree.
        assertEquals(viaMinimize.withoutStats(), viaImprovementsLast.withoutStats())
    }

    @Test
    fun `minimize populates native LS stats`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val obj = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val result = LocalSearchSolver(problem).minimize(obj, LocalSearchParams(maxFlips = 4_000L, randomSeed = 1L))
        val best = assertIs<MinimizeResult.BestFound>(result)
        val stats = best.stats
        assertEquals("ls", stats.run.backend)
        assertTrue(stats.ls.moves.sum > 0.0, "LS must report the moves it applied")
        assertEquals(best.objective, stats.ls.incumbentObjective, "incumbent objective mirrors the verdict")
        assertEquals(0.0, stats.ls.incumbentViolation, "a feasible incumbent has zero violation")
        assertTrue(stats.ls.timeToBestMs >= 0L, "time-to-best is stamped once an incumbent lands")
    }

    @Test
    fun `solve populates moves and a feasible incumbent`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val result = LocalSearchSolver(problem).solve(LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L))
        val sat = assertIs<SolveResult.Sat>(result)
        assertEquals("ls", sat.stats.run.backend)
        assertEquals(0.0, sat.stats.ls.incumbentViolation, "a satisfied instance has zero violation")
        assertTrue(sat.stats.ls.incumbentObjective.isNaN(), "no objective is tracked in satisfy mode")
    }

    @Test
    fun `improvements is lazy — consumer can take just the first event`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val obj = LinearObjective(boolWeights = longArrayOf(1L, 1L, 1L, 1L))
        // With an unbounded budget, only the lazy Sequence path can yield the first improvement and stop.
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
