package com.eignex.klause.solver.result

import kotlin.test.Test
import kotlin.test.assertEquals

class PortfolioStatsTest {
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
