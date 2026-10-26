package com.eignex.klause.bench.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NonlinearIntegerCellSuiteTest {
    @Test
    fun `cell set should retain all versioned catalog identities`() {
        val suite = Catalog.suite("smtlib-nonlinear-integer-cells-v1")

        val selected = ProblemSets.load(listOf("nonlinear-integer-cells-v1"))

        assertEquals(suite.problems.map { SetEntry(suite.id, it.name) }, selected)
        assertEquals(17, selected.size)
    }

    @Test
    fun `cell holdouts should exclude every core family`() {
        val core = ProblemSets.load(listOf("nonlinear-integer-cells-core-v1"))
            .map { it.problem.substringBefore('/') }.toSet()

        val holdouts = ProblemSets.load(listOf("nonlinear-integer-cells-holdout-v1"))
            .map { it.problem.substringBefore('/') }.toSet()

        assertTrue(core.intersect(holdouts).isEmpty())
    }
}
