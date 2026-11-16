package com.eignex.klause.bench.catalog

import kotlin.test.Test
import kotlin.test.assertTrue

class NonlinearIntegerCellSuiteTest {
    @Test
    fun `cell holdouts should exclude every core family`() {
        val core = ProblemSets.load(listOf("nonlinear-integer-cells-core-v1"))
            .map { it.problem.substringBefore('/') }.toSet()

        val holdouts = ProblemSets.load(listOf("nonlinear-integer-cells-holdout-v1"))
            .map { it.problem.substringBefore('/') }.toSet()

        assertTrue(core.intersect(holdouts).isEmpty())
    }
}
