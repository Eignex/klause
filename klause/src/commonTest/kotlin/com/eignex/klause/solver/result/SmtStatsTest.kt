package com.eignex.klause.solver.result

import kotlin.test.Test
import kotlin.test.assertEquals

class SmtStatsTest {
    @Test
    fun `merged traversal progress retains the model for the smallest unresolved count`() {
        val first = SmtStats(completeSearchBooleanVariables = 12, completeSearchMinUnassignedBooleans = 5,
            completeSearchLinearChecks = 2, completeSearchLinearPoints = 1)
        val second = SmtStats(completeSearchBooleanVariables = 8, completeSearchMinUnassignedBooleans = 3,
            completeSearchLinearChecks = 4, completeSearchLinearPoints = 2)
        for ((left, right) in listOf(first to second, second to first)) {
            val merged = left.mergedWith(SmtStats()).mergedWith(right)

            assertEquals(8, merged.completeSearchBooleanVariables)
            assertEquals(3, merged.completeSearchMinUnassignedBooleans)
            assertEquals(6L, merged.completeSearchLinearChecks)
            assertEquals(3L, merged.completeSearchLinearPoints)
        }
    }
}
