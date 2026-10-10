package com.eignex.klause.solver.integration

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.bake
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
        val seq = LocalSearchSolver(problem.bake()).improvements(
            obj,
            LocalSearchParams(maxFlips = 400L, randomSeed = 1L),
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
    fun `instruction budget bounds constant-objective optimization work`() {
        // A constant objective has no improving incumbent after its first one. The counted allowance
        // must still end the segment.
        val problem = Problem(0, 0, emptyArray(), emptyArray())
        val result = LocalSearchSolver(problem.bake()).minimize(
            LinearObjective(),
            LocalSearchParams(maxFlips = Long.MAX_VALUE, maxInstructions = 7L, randomSeed = 0L),
        )

        val best = assertIs<MinimizeResult.BestFound>(result)
        assertEquals(7.0, best.stats.ls.moves.sum, "the instruction budget bounds the whole segment")
    }

    @Test
    fun `improvements is lazy so a consumer can take just the first event`() {
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
        val first = LocalSearchSolver(problem.bake()).improvements(
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
    is MinimizeResult.Unbounded -> copy(stats = SolveStats.EMPTY)
    is MinimizeResult.Infeasible -> copy(stats = SolveStats.EMPTY)
    is MinimizeResult.Unknown -> copy(stats = SolveStats.EMPTY)
}
