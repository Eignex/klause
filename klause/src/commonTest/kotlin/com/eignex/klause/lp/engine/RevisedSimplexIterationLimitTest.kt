package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stopping a node LP at a pivot budget.
 *
 * The dual simplex is dual-feasible at every basis it passes through, so an iterate it stops on still
 * bounds the optimum from below — that is what makes a budget a throughput knob rather than a refusal.
 * What a stopped solve must not do is pass itself off as an optimum.
 */
class RevisedSimplexIterationLimitTest {

    /** A covering LP whose all-slack start is several dual pivots away from the optimum. */
    private fun cover(): LpModel {
        val b = LpBuilder()
        val x1 = b.addVar(0L, 10L, cost = 1L)
        val x2 = b.addVar(0L, 10L, cost = 1L)
        val x3 = b.addVar(0L, 10L, cost = 1L)
        val x4 = b.addVar(0L, 10L, cost = 1L)
        b.addRow(intArrayOf(x1, x2), longArrayOf(1L, 1L), Relation.GE, 3L)
        b.addRow(intArrayOf(x2, x3), longArrayOf(1L, 1L), Relation.GE, 4L)
        b.addRow(intArrayOf(x3, x4), longArrayOf(1L, 1L), Relation.GE, 5L)
        b.addRow(intArrayOf(x1, x4), longArrayOf(1L, 1L), Relation.GE, 2L)
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `a solve stopped at its pivot budget still bounds the optimum from below`() {
        val full = assertNotNull(RevisedSimplex(cover()).solve(null))
        assertTrue(full.pivots >= 2, "fixture must cost more than one pivot to cap meaningfully")

        val capped = assertNotNull(RevisedSimplex(cover(), iterationLimit = 1).solve(null))

        assertTrue(capped.pivots < full.pivots, "the budget must actually stop the solve short")
        assertTrue(
            capped.objective <= full.objective + 1e-9,
            "a dual-feasible iterate bounds below: ${capped.objective} must not exceed ${full.objective}",
        )
    }

    @Test
    fun `a solve stopped at its pivot budget does not claim an optimum`() {
        val full = assertNotNull(RevisedSimplex(cover()).solve(null))

        val capped = assertNotNull(RevisedSimplex(cover(), iterationLimit = 1).solve(null))

        assertTrue(full.optimal, "the uncapped solve reaches the optimum")
        assertFalse(capped.optimal, "a stopped iterate must not gate reduced-cost fixing or cuts")
    }

    @Test
    fun `a budget above the pivots the model needs leaves the optimum unchanged`() {
        val full = assertNotNull(RevisedSimplex(cover()).solve(null))

        val generous = assertNotNull(RevisedSimplex(cover(), iterationLimit = full.pivots + 10).solve(null))

        assertEquals(full.objective, generous.objective, 1e-9, "a budget that never binds changes nothing")
        assertTrue(generous.optimal)
    }
}
