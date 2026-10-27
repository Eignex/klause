package com.eignex.klause.solver.result

import com.eignex.kumulant.stat.summary.SumResult
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSearchStatsTest {
    @Test
    fun `merging workers preserves geometric qualification declines and offers separately`() {
        val first = LocalSearchStats(geometricProposalRuns = SumResult(1.0),
            geometricProposalCandidates = SumResult(3.0))
        val second = LocalSearchStats(geometricProposalDeclines = SumResult(1.0))

        val merged = first.mergedWith(second)

        assertEquals(1.0, merged.geometricProposalRuns.sum)
        assertEquals(1.0, merged.geometricProposalDeclines.sum)
        assertEquals(3.0, merged.geometricProposalCandidates.sum)
    }

    @Test
    fun `merging workers should add changed and declined board restart counts`() {
        val first = LocalSearchStats(boardRestartChanges = SumResult(2.0), boardRestartDeclines = SumResult(3.0))
        val second = LocalSearchStats(boardRestartChanges = SumResult(5.0), boardRestartDeclines = SumResult(7.0))

        val merged = first.mergedWith(second)

        assertEquals(7.0, merged.boardRestartChanges.sum)
        assertEquals(10.0, merged.boardRestartDeclines.sum)
    }
}
