package com.eignex.klause.lp.bounding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LpWorkBudgetTest {

    private fun budget(initial: Long = 1_000L) = LpWorkBudget(minOps = 100L, maxOps = 100_000L, initialOps = initial)

    @Test
    fun `a solve that ran out of budget while making progress gets more`() {
        val budget = budget()

        budget.observe(reachedOptimum = false, degenerateColumns = 0, columns = 100, sizeBudget = 500L)

        assertEquals(2_000L, budget.ops(), "the budget was the binding constraint, so raise it")
    }

    @Test
    fun `a solve that ran out of budget while stalling gets less`() {
        val budget = budget()

        budget.observe(reachedOptimum = false, degenerateColumns = 90, columns = 100, sizeBudget = 500L)

        assertTrue(budget.ops() < 1_000L, "a degenerate stall does not deserve more, saw ${budget.ops()}")
    }

    @Test
    fun `the budget never falls to nothing`() {
        val budget = budget(initial = 200L)

        repeat(20) { budget.observe(reachedOptimum = true, degenerateColumns = 100, columns = 100, sizeBudget = 1L) }

        assertTrue(budget.ops() >= 100L, "the floor keeps a weak bound coming instead of switching the LP off")
    }

    @Test
    fun `the budget is capped however often a solve asks for more`() {
        val budget = budget()

        repeat(20) { budget.observe(reachedOptimum = false, degenerateColumns = 0, columns = 100, sizeBudget = 500L) }

        assertEquals(100_000L, budget.ops(), "doubling must stop at the ceiling")
    }

}
