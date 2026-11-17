package com.eignex.klause.solver.result

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchStatsTest {
    @Test
    fun `relearned clause in any literal order should count as relearned`() {
        val sink = SearchStatsSink()
        sink.observeLearned(intArrayOf(4, 9, 12), lbd = 2)

        sink.observeLearned(intArrayOf(12, 4, 9), lbd = 2)

        assertEquals(1.0, sink.snapshot().relearned.sum)
    }
}
