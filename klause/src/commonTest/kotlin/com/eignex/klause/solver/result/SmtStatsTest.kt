package com.eignex.klause.solver.result

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SmtStatsTest {
    @Test
    fun `counted stages should retain count one behavior and saturate`() {
        val sink = SmtStatsSink()
        sink.observeCompleteSearchBooleans(1, 1)

        sink.observeCompleteSearchStage("BRANCH")
        sink.observeCompleteSearchStage("BRANCH", 4L)
        sink.observeCompleteSearchStage("WORK", Long.MAX_VALUE)
        sink.observeCompleteSearchStage("WORK", 1L)

        assertEquals(mapOf("BRANCH" to 5L, "WORK" to Long.MAX_VALUE), sink.snapshot().completeSearchStages)
    }

    @Test
    fun `zero stage counts should leave unobserved progress absent`() {
        val sink = SmtStatsSink()

        sink.observeCompleteSearchStage("WORK", 0L)

        assertNull(sink.snapshot().completeSearchStages)
        assertFailsWith<IllegalArgumentException> { sink.observeCompleteSearchStage("WORK", -1L) }
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
