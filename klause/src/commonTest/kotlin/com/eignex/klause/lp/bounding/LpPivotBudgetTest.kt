package com.eignex.klause.lp.bounding

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who the node LP pivot budget is allowed to bite.
 *
 * Capping a relaxation that prunes costs bound quality outright; capping one that never prunes costs
 * nothing. Everything here is that distinction.
 */
class LpPivotBudgetTest {

    @Test
    fun `an LP still inside its warmup keeps the size-derived budget`() {
        val budget = LpPivotBudget(cap = 500, warmupSolves = 4)

        repeat(3) { budget.observe(pruned = false, couldPrune = true) }

        assertEquals(0, budget.pivots(), "an LP that has not had its window yet must not be capped")
    }

    @Test
    fun `an LP that reaches its warmup without pruning is capped`() {
        val budget = LpPivotBudget(cap = 500, warmupSolves = 4)

        repeat(4) { budget.observe(pruned = false, couldPrune = true) }

        assertEquals(500, budget.pivots(), "an LP that prunes nothing over its window is the one to cap")
    }

    @Test
    fun `a prune lifts the budget`() {
        val budget = LpPivotBudget(cap = 500, warmupSolves = 4)
        repeat(4) { budget.observe(pruned = false, couldPrune = true) }
        assertEquals(500, budget.pivots())

        budget.observe(pruned = true, couldPrune = true)

        assertEquals(0, budget.pivots(), "a prune says the cap was the wrong judgement")
    }

    @Test
    fun `a prune restarts the window rather than ending it`() {
        val budget = LpPivotBudget(cap = 500, warmupSolves = 4)
        budget.observe(pruned = true, couldPrune = true)

        repeat(4) { budget.observe(pruned = false, couldPrune = true) }

        assertEquals(500, budget.pivots(), "the cap is earned again on the solves since the prune")
    }

    @Test
    fun `a prune is not held against the solves that preceded it`() {
        val budget = LpPivotBudget(cap = 500, warmupSolves = 4)
        repeat(3) { budget.observe(pruned = false, couldPrune = true) }
        budget.observe(pruned = true, couldPrune = true)

        repeat(3) { budget.observe(pruned = false, couldPrune = true) }

        assertEquals(0, budget.pivots(), "three solves before the prune do not count toward the next window")
    }

    @Test
    fun `a prune after the budget already bit still lifts it`() {
        val budget = LpPivotBudget(cap = 500, warmupSolves = 2)
        repeat(2) { budget.observe(pruned = false, couldPrune = true) }
        assertEquals(500, budget.pivots())

        budget.observe(pruned = true, couldPrune = true)

        assertEquals(0, budget.pivots(), "a capped solve that prunes proves the LP worth its pivots")
    }

    @Test
    fun `solves with no incumbent to prune against do not spend the warmup`() {
        val budget = LpPivotBudget(cap = 500, warmupSolves = 4)

        repeat(50) { budget.observe(pruned = false, couldPrune = false) }

        assertEquals(0, budget.pivots(), "a search that has not found its first solution is not a bad LP")
    }

    @Test
    fun `the warmup resumes once an incumbent makes pruning possible`() {
        val budget = LpPivotBudget(cap = 500, warmupSolves = 4)
        repeat(50) { budget.observe(pruned = false, couldPrune = false) }

        repeat(4) { budget.observe(pruned = false, couldPrune = true) }

        assertEquals(500, budget.pivots(), "four real chances to prune, none taken")
    }

    @Test
    fun `a cap of zero never bites`() {
        val budget = LpPivotBudget(cap = 0, warmupSolves = 1)

        repeat(20) { budget.observe(pruned = false, couldPrune = true) }

        assertEquals(0, budget.pivots())
    }
}
