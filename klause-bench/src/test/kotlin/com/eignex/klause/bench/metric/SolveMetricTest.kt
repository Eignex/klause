package com.eignex.klause.bench.metric

import com.eignex.klause.bench.runner.Budget
import kotlin.test.Test
import kotlin.test.assertNotEquals

class SolveMetricTest {
    @Test
    fun `exact and default runs use different result and cache names`() {
        val settings = SolverInvocation.Settings()
        val budget = Budget(1000)

        val default = SolveMetric.configTag(SolverInvocation.KLAUSE, settings, budget)
        val exact = SolveMetric.configTag(SolverInvocation.KLAUSE, settings.copy(exact = true), budget)

        assertNotEquals(default, exact)
    }
}
