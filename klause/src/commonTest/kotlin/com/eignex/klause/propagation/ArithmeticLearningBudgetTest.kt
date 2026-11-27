package com.eignex.klause.propagation

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArithmeticLearningBudgetTest : RuntimeInitializedTest() {
    @Test
    fun `a funded replay should pay the work and allocation charge`() {
        val state = PropagationState(Problem(0, 0, emptyArray(), emptyArray()), Assumptions.None)
        val budget = ArithmeticLearningBudget(state, ArithmeticLearningLimits(work = 8, allocationBytes = 64))
        val work = state.work

        assertTrue(budget.replayIfFunded(work = 8, bytes = 64))

        assertEquals(8L, state.work - work)
        assertEquals(64L, budget.allocatedBytes)
    }

    @Test
    fun `an unfunded replay should preserve the remaining allowance for the original scan`() {
        val state = PropagationState(Problem(0, 0, emptyArray(), emptyArray()), Assumptions.None)
        val budget = ArithmeticLearningBudget(state, ArithmeticLearningLimits(work = 8, allocationBytes = 64))
        val work = state.work

        assertFalse(budget.replayIfFunded(work = 9, bytes = 64))
        assertFalse(budget.replayIfFunded(work = 8, bytes = 65))

        assertEquals(work, state.work)
        assertEquals(0L, budget.allocatedBytes)
    }
}
