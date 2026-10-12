package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ObjectiveOffsetTest {
    @Test
    fun `normalized workers publish full source costs with their improving samples`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(-1), constant = 1L shl 62)
        val first = Sample(booleanArrayOf(), longArrayOf(1))
        val optimum = Sample(booleanArrayOf(), longArrayOf(2))
        val complete = PortfolioWorker.ofMinimize("bt", 0) { _, _, _, _ ->
            sequenceOf(MinimizeResult.BestFound(first, -1.0, TerminationReason.BudgetExhausted),
                MinimizeResult.Optimal(optimum, -2.0))
        }
        val executor = Portfolio.thompson(listOf(complete)).sourceValues(objective)
        val improvements = ArrayList<MinimizeResult.WithSample>()

        val result = executor.use { it.minimize(onImprovement = { improvement ->
            improvements += assertIs<MinimizeResult.WithSample>(improvement.result)
        }) }

        assertEquals(listOf(1L, 2L), improvements.map { it.sample.intValue(0) })
        for (improvement in improvements) assertEquals(objective.evaluateExact(improvement.sample).toDouble(),
            improvement.objective)
        assertEquals(objective.evaluateExact(optimum).toDouble(), assertIs<MinimizeResult.Optimal>(result).objective)
    }
}
