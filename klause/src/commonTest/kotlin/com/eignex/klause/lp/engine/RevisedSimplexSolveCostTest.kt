package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a solve reports about its own cost when it has no result to report it on.
 *
 * A dual-unbounded termination returns null, and on a model whose relaxation is often infeasible that is
 * most of the solves. Reading pivots off the result alone therefore drops exactly the solves that prune,
 * which is why the engine carries the count itself.
 */
class RevisedSimplexSolveCostTest {

    /** `x + y >= 20` over `[0, 5]²` — infeasible, so the dual pass runs to dual-unbounded. */
    private fun infeasible(): LpModel {
        val b = LpBuilder()
        val x = b.addVar(0L, 5L, cost = 1L)
        val y = b.addVar(0L, 5L, cost = 1L)
        b.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 20L)
        return b.build(Sense.MINIMIZE)
    }

    /** `x + y >= 3` over `[0, 10]²`, minimising `x + 2y`. */
    private fun feasible(): LpModel {
        val b = LpBuilder()
        val x = b.addVar(0L, 10L, cost = 1L)
        val y = b.addVar(0L, 10L, cost = 2L)
        b.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 3L)
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `a solve that returns no result still reports the pivots it spent`() {
        val simplex = RevisedSimplex(infeasible())

        assertNull(simplex.solve(null), "the fixture must terminate without a result")

        assertTrue(simplex.lastPivots > 0, "the pivots spent reaching infeasibility must survive the null")
    }

    @Test
    fun `a solve that returns no result still reports the factorizations it built`() {
        val simplex = RevisedSimplex(infeasible())

        assertNull(simplex.solve(null))

        assertTrue(simplex.lastRefactorizations > 0, "the cold start's factorization is a real cost")
    }

    @Test
    fun `the cost a solve reports matches the result when there is one`() {
        val simplex = RevisedSimplex(feasible())

        val result = assertNotNull(simplex.solve(null))

        assertTrue(simplex.lastPivots == result.pivots, "one solve, one pivot count")
        assertTrue(simplex.lastRefactorizations == result.refactorizations, "one solve, one factorization count")
    }
}
