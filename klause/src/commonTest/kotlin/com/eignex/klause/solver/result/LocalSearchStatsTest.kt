package com.eignex.klause.solver.result

import com.eignex.kumulant.stat.summary.SumResult
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSearchStatsTest {
    @Test
    fun `merging workers should add changed and declined board restart counts`() {
        val first = LocalSearchStats(boardRestartChanges = SumResult(2.0), boardRestartDeclines = SumResult(3.0))
        val second = LocalSearchStats(boardRestartChanges = SumResult(5.0), boardRestartDeclines = SumResult(7.0))

        val merged = first.mergedWith(second)

        assertEquals(7.0, merged.boardRestartChanges.sum)
        assertEquals(10.0, merged.boardRestartDeclines.sum)
    }
}
