package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalSearchSolverMinimizeTest {

    @Test
    fun `local search improvements should strictly decrease to the optimum`() {
        val factor = Cardinality.exactlyOne(IntArray(4) { Lit.make(it, true) })
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val obj = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))

        val seq = LocalSearchSolver(problem.bake()).improvements(
            obj,
            LocalSearchParams(maxFlips = 400L, randomSeed = 1L),
        ).toList()

        val objectives = seq.map { assertIs<MinimizeResult.BestFound>(it).objective }
        assertTrue(objectives.dropLast(1).zipWithNext().all { (a, b) -> b < a })
        assertEquals(3.0, objectives.last())
    }

    @Test
    fun `instruction budget should bound constant objective minimization`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray())
        val result = LocalSearchSolver(problem.bake()).minimize(
            LinearObjective(),
            LocalSearchParams(maxFlips = Long.MAX_VALUE, maxInstructions = 7L, randomSeed = 0L),
        )

        val best = assertIs<MinimizeResult.BestFound>(result)
        assertEquals(7.0, best.stats.ls.moves.sum)
    }

    @Test
    fun `local search improvements should be lazy`() {
        val factor = Cardinality.exactlyOne(IntArray(4) { Lit.make(it, true) })
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val obj = LinearObjective(boolWeights = longArrayOf(1L, 1L, 1L, 1L))
        val first = LocalSearchSolver(problem.bake()).improvements(
            obj,
            LocalSearchParams(maxFlips = Long.MAX_VALUE, randomSeed = 2L),
        ).first()
        assertIs<MinimizeResult.BestFound>(first)
    }

    @Test
    fun `minimize should return the best feasible when the budget runs out`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val obj = LinearObjective(boolWeights = longArrayOf(10L, 1L, 100L, 50L))
        val sample = LocalSearchSolver(
            problem.bake(),
        ).minimize(obj, LocalSearchParams(maxFlips = 50, randomSeed = 1L)).assignment
        assertNotNull(sample)
        assertTrue(sample.bools.count { it } == 1)
    }

    @Test
    fun `minimize should return the best feasible on cancellation`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
                Lit.make(4, true),
                Lit.make(5, true),
            ),
        )
        val problem = Problem(6, 0, emptyArray(), listOf(factor))
        val obj = LinearObjective(boolWeights = longArrayOf(10L, 5L, 1L, 100L, 50L, 25L))
        var polls = 0
        val sample = LocalSearchSolver(problem.bake()).minimize(
            obj,
            LocalSearchParams(
                maxFlips = Long.MAX_VALUE,
                randomSeed = 0L,
                cancellation = { ++polls >= 2_000 },
            ),
        ).assignment
        assertNotNull(sample)
        assertTrue(sample.bools.count { it } == 1)
    }

    @Test
    fun `minimize should terminate on a degenerate objective and constraint-free problem`() {
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val solver = LocalSearchSolver(problem.bake())
        val degenerate = LinearObjective(boolWeights = LongArray(4))
        val sample = solver.minimize(
            degenerate,
            LocalSearchParams(maxFlips = 1_000L, randomSeed = 1L),
        ).assignment
        assertNotNull(sample)
    }

    @Test
    fun `minimize should honor cancellation during a descent scan`() {
        val n = 4000
        val problem = Problem(
            numBoolVars = n,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val solver = LocalSearchSolver(problem.bake(), greedyRepairOnRestart = false)
        var polls = 0
        val cancellation = Cancellation { ++polls > 100 }
        val result = solver.minimize(
            LinearObjective(boolWeights = LongArray(n) { 1L }),
            LocalSearchParams(maxFlips = Long.MAX_VALUE, randomSeed = 7L, cancellation = cancellation),
        )
        assertEquals(TerminationReason.Cancelled, (result as MinimizeResult.BestFound).reason)
        assertTrue(polls in 1 until n, "polls=$polls")
    }
}
