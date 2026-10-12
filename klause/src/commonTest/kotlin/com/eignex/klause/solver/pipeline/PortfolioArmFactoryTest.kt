package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PortfolioArmFactoryTest {
    @Test
    fun `arm construction rejects a foreign prepared source before allocating private state`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val plan = source.prepareExecutionPlan()
        val foreign = Problem(0, 0, emptyArray(), emptyArray()).prepareExecutionPlan()
        val factory = PortfolioArmFactory(plan, 0, ArmFamily.Backtrack) {
            error("foreign source cannot allocate this worker")
        }
        val resources = PortfolioInvocationResources(1, 1, 17, Cancellation.Never, null)

        assertFailsWith<IllegalArgumentException> { factory.materialize(foreign, resources.slots[0]) }
    }

    @Test
    fun `replica slots retain composed attribution while receiving distinct seeds`() {
        val plan = Problem(0, 0, emptyArray(), emptyArray()).prepareExecutionPlan()
        val observedSeeds = mutableListOf<Long>()
        val factory = PortfolioArmFactory(plan, 0, ArmFamily.Backtrack) { slot ->
            observedSeeds += slot.seed
            PortfolioWorker.ofSolve("source", 0) { _, _ -> SolveResult.Unknown(TerminationReason.BudgetExhausted) }
        }
        val resources = PortfolioInvocationResources(2, 2, 17, Cancellation.Never, null)

        val first = factory.materialize(plan, resources.slots[0])
        val second = factory.materialize(plan, resources.slots[1])

        assertEquals(0, first.armId)
        assertEquals(0, second.armId)
        assertEquals(listOf(17L, 18L), observedSeeds)
        assertEquals(ArmFamily.Backtrack, first.family)
        first.close()
        second.close()
    }
}
