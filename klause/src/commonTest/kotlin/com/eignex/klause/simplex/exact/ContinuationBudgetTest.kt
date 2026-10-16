package com.eignex.klause.simplex.exact

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContinuationBudgetTest {
    @Test
    fun `phase reports retain zero charges and first visit order`() {
        val budget = ContinuationBudget(ExactContinuationLimits(), Cancellation.Never)
        budget.phase = ContinuationPhase.VERIFY
        budget.step(units = 0L)
        budget.phase = ContinuationPhase.INPUT
        budget.step(units = 7L, bytes = 11L)
        budget.phase = ContinuationPhase.REPAIR
        budget.step(units = 0L)
        budget.phase = ContinuationPhase.VERIFY

        budget.step(units = 3L, bytes = 5L)

        assertEquals(mapOf(ContinuationPhase.VERIFY to 3L, ContinuationPhase.INPUT to 7L,
            ContinuationPhase.REPAIR to 0L), budget.workByPhase)
        assertEquals(mapOf(ContinuationPhase.VERIFY to 5L, ContinuationPhase.INPUT to 11L,
            ContinuationPhase.REPAIR to 0L), budget.allocationByPhase)
        assertEquals(listOf(ContinuationPhase.VERIFY, ContinuationPhase.INPUT, ContinuationPhase.REPAIR),
            budget.workByPhase.keys.toList())
        assertEquals(10L, budget.work)
        assertEquals(16L, budget.allocation)
    }

    @Test
    fun `phase report snapshots retain their completed prefix`() {
        val budget = ContinuationBudget(ExactContinuationLimits(), Cancellation.Never)
        budget.step(units = 2L, bytes = 3L)
        val work = budget.workByPhase
        val allocation = budget.allocationByPhase

        budget.step(units = 5L, bytes = 7L)
        budget.phase = ContinuationPhase.VERIFY
        budget.step(units = 11L, bytes = 13L)

        assertEquals(mapOf(ContinuationPhase.INPUT to 2L), work)
        assertEquals(mapOf(ContinuationPhase.INPUT to 3L), allocation)
        assertEquals(budget.work, budget.workByPhase.values.sum())
        assertEquals(budget.allocation, budget.allocationByPhase.values.sum())
    }

    @Test
    fun `a declined charge retains all accounting at its accepted prefix`() {
        for (reason in listOf(ContinuationDecline.CANCELLED, ContinuationDecline.WORK, ContinuationDecline.ALLOCATION)) {
            var cancelled = false
            val limits = ExactContinuationLimits(
                maxWork = if (reason == ContinuationDecline.WORK) 2L else 100L,
                maxAllocation = if (reason == ContinuationDecline.ALLOCATION) 3L else 100L,
            )
            val budget = ContinuationBudget(limits, Cancellation { cancelled })
            budget.step(units = 2L, bytes = 3L)
            budget.phase = ContinuationPhase.VERIFY
            cancelled = reason == ContinuationDecline.CANCELLED

            val stopped = assertFailsWith<ContinuationStop> { budget.step(units = 1L, bytes = 1L) }

            assertEquals(reason, stopped.reason)
            assertEquals(2L, budget.work)
            assertEquals(3L, budget.allocation)
            assertEquals(mapOf(ContinuationPhase.INPUT to 2L), budget.workByPhase)
            assertEquals(mapOf(ContinuationPhase.INPUT to 3L), budget.allocationByPhase)
        }
    }
}
