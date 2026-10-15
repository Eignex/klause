package com.eignex.klause.lp.bounding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LpWorkBudgetTest {

    private fun budget(initial: Long = 1_000L) = LpWorkBudget(minOps = 100L, maxOps = 100_000L, initialOps = initial)

    @Test
    fun `budget should grow after a solve ran out while making progress`() {
        val budget = budget()

        budget.observe(reachedOptimum = false, degenerateColumns = 0, columns = 100, sizeBudget = 500L)

        assertEquals(2_000L, budget.ops())
    }

    @Test
    fun `budget should shrink after a solve ran out while stalling`() {
        val budget = budget()

        budget.observe(reachedOptimum = false, degenerateColumns = 90, columns = 100, sizeBudget = 500L)

        assertTrue(budget.ops() < 1_000L, "saw ${budget.ops()}")
    }

    @Test
    fun `budget should never fall below its floor`() {
        val budget = budget(initial = 200L)

        repeat(20) { budget.observe(reachedOptimum = true, degenerateColumns = 100, columns = 100, sizeBudget = 1L) }

        assertTrue(budget.ops() >= 100L)
    }

    @Test
    fun `budget should be capped however often a solve asks for more`() {
        val budget = budget()

        repeat(20) { budget.observe(reachedOptimum = false, degenerateColumns = 0, columns = 100, sizeBudget = 500L) }

        assertEquals(100_000L, budget.ops())
    }
}
