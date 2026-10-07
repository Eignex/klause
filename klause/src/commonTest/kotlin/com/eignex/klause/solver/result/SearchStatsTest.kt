package com.eignex.klause.solver.result

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchStatsTest {

    @Test
    fun `a clause learned again in any literal order counts as relearned`() {
        val sink = SearchStatsSink()
        sink.observeLearned(intArrayOf(4, 9, 12), lbd = 2)

        sink.observeLearned(intArrayOf(12, 4, 9), lbd = 2)

        assertEquals(1.0, sink.snapshot().relearned.sum)
    }

    @Test
    fun `learned clause sizes and literal block distances add up`() {
        val sink = SearchStatsSink()

        sink.observeLearned(intArrayOf(1, 2, 3), lbd = 2)
        sink.observeLearned(intArrayOf(5), lbd = 1)

        val stats = sink.snapshot()
        assertEquals(
            listOf(2.0, 4.0, 3.0),
            listOf(stats.learnedClauses.sum, stats.learnedLiterals.sum, stats.learnedLbd.sum),
        )
    }
}
