package com.eignex.klause.solver.result

import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreGuidedStatsTest {
    @Test
    fun `compatibility copies should retain continuation funding`() {
        val stats = CoreGuidedStats(1L, 2, bigIntOf(1L), 40L, 30_000L, 2L)

        for (copy in listOf(stats.copy(cores = 3L), stats.copy(workLimit = 50L))) {
            assertEquals(2L, copy.continuationGrants)
            assertEquals(30_000L, copy.activeMillisLimit)
        }
    }
}
