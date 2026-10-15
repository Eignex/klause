package com.eignex.klause.lp.bounding

import kotlin.test.Test
import kotlin.test.assertEquals

class LpPivotBudgetTest {

    @Test
    fun `budget should cap an LP that reaches its warmup without pruning`() {
        val budget = LpPivotBudget(cap = 500, warmupSolves = 4)

        repeat(4) { budget.observe(pruned = false, couldPrune = true) }

        assertEquals(500, budget.pivots())
    }

    @Test
    fun `prune should lift the budget`() {
        val budget = LpPivotBudget(cap = 500, warmupSolves = 4)
        repeat(4) { budget.observe(pruned = false, couldPrune = true) }
        assertEquals(500, budget.pivots())

        budget.observe(pruned = true, couldPrune = true)

        assertEquals(0, budget.pivots())
    }

    @Test
    fun `prune should restart the window rather than end it`() {
        val budget = LpPivotBudget(cap = 500, warmupSolves = 4)
        budget.observe(pruned = true, couldPrune = true)

        repeat(4) { budget.observe(pruned = false, couldPrune = true) }

        assertEquals(500, budget.pivots())
    }

    @Test
    fun `solves without an incumbent should not spend the warmup`() {
        val budget = LpPivotBudget(cap = 500, warmupSolves = 4)

        repeat(50) { budget.observe(pruned = false, couldPrune = false) }

        assertEquals(0, budget.pivots())
    }

    @Test
    fun `cap of zero should never bite`() {
        val budget = LpPivotBudget(cap = 0, warmupSolves = 1)

        repeat(20) { budget.observe(pruned = false, couldPrune = true) }

        assertEquals(0, budget.pivots())
    }
}
