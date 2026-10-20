package com.eignex.klause.solver.result

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmtStatsTest {
    @Test
    fun `bulk traversal stages should add counts and omit zero observations`() {
        val stats = SmtStatsSink()
        stats.observeCompleteSearchBooleans(2, 2)

        stats.observeCompleteSearchStage("ROWS", 12L)
        stats.observeCompleteSearchStage("ROWS", 7L)
        stats.observeCompleteSearchStage("ROWS")
        stats.observeCompleteSearchStage("EMPTY", 0L)

        assertEquals(mapOf("ROWS" to 20L), stats.snapshot().completeSearchStages)
    }

    @Test
    fun `bulk traversal stages should saturate at the largest count`() {
        val stats = SmtStatsSink()
        stats.observeCompleteSearchBooleans(2, 2)

        stats.observeCompleteSearchStage("ROWS", Long.MAX_VALUE - 1L)
        stats.observeCompleteSearchStage("ROWS", 12L)

        assertEquals(mapOf("ROWS" to Long.MAX_VALUE), stats.snapshot().completeSearchStages)
    }

    @Test
    fun `bulk traversal stages should reject negative counts`() {
        val stats = SmtStatsSink()
        stats.observeCompleteSearchBooleans(2, 2)

        assertFailsWith<IllegalArgumentException> { stats.observeCompleteSearchStage("ROWS", -1L) }

        assertEquals(emptyMap(), stats.snapshot().completeSearchStages)
    }

    @Test
    fun `merged traversal progress should retain the model for the smallest unresolved count`() {
        val first = SmtStats(
            completeSearchBooleanVariables = 12,
            completeSearchMinUnassignedBooleans = 5,
            completeSearchLinearChecks = 2,
            completeSearchLinearPoints = 1,
            completeSearchLinearOutcomes = mapOf("FEASIBLE" to 2L),
            completeSearchStages = mapOf("BRANCH" to 2L),
        )
        val second = SmtStats(
            completeSearchBooleanVariables = 8,
            completeSearchMinUnassignedBooleans = 3,
            completeSearchLinearChecks = 4,
            completeSearchLinearPoints = 2,
            completeSearchLinearOutcomes = mapOf("FEASIBLE" to 1L, "NO_RESULT" to 3L),
            completeSearchStages = mapOf("BRANCH" to 3L, "RECOVERY_ATTEMPT" to 1L),
        )
        for ((left, right) in listOf(first to second, second to first)) {
            val merged = left.mergedWith(SmtStats()).mergedWith(right)

            assertEquals(8, merged.completeSearchBooleanVariables)
            assertEquals(3, merged.completeSearchMinUnassignedBooleans)
            assertEquals(6L, merged.completeSearchLinearChecks)
            assertEquals(3L, merged.completeSearchLinearPoints)
            assertEquals(mapOf("FEASIBLE" to 3L, "NO_RESULT" to 3L), merged.completeSearchLinearOutcomes)
            assertEquals(mapOf("BRANCH" to 5L, "RECOVERY_ATTEMPT" to 1L), merged.completeSearchStages)
        }
    }
}
