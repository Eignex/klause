package com.eignex.klause.solver.result

import kotlin.test.Test
import kotlin.test.assertEquals

class PortfolioStatsTest {
    @Test
    fun `copying heuristic samples should retain their completion time`() {
        val sample = HeuristicStartStats(5L, mapOf("first" to 6L), true, 7L)

        val copy = sample.copy(workByArm = mapOf("first" to 8L))

        assertEquals(7L, copy.completionNanos)
    }

    @Test
    fun `merging heuristic starts should retain invocation boundaries`() {
        val incomplete = HeuristicStartStats(5L, mapOf("first" to 2L), false)
        val complete = HeuristicStartStats(5L, mapOf("first" to 6L), true, 7L)

        val combined = PortfolioStats(heuristicStarts = listOf(incomplete))
            .mergedWith(PortfolioStats(heuristicStarts = listOf(complete)))

        assertEquals(listOf(incomplete, complete), combined.heuristicStarts)
    }

    @Test
    fun `merging schedules should preserve distinct Boolean proof attempts`() {
        val partitioned = BooleanProofStats(2, 1, 1, 5000L, 8000L)
        val declined = BooleanProofStats(0, 0, 0, 0L, 0L, "model-ineligible")
        val first = PortfolioStats(booleanProof = listOf(partitioned))
        val second = PortfolioStats(booleanProof = listOf(declined))

        val combined = first.mergedWith(second)

        assertEquals(listOf(partitioned, declined), combined.booleanProof)
    }
}
