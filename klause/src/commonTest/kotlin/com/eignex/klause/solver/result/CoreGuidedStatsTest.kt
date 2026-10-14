package com.eignex.klause.solver.result

import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreGuidedStatsTest {
    @Test
    fun `copying core observations retains resource limits`() {
        val stats = CoreGuidedStats(2L, 4, bigIntOf(3), workLimit = 5_000L, activeMillisLimit = 2_000L)

        val copied = stats.copy(cores = 3L, largestCore = 5, lowerBound = bigIntOf(7))

        assertEquals(CoreGuidedStats(3L, 5, bigIntOf(7), workLimit = 5_000L, activeMillisLimit = 2_000L), copied)
    }
}
