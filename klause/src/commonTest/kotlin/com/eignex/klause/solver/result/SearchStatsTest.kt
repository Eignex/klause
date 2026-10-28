package com.eignex.klause.solver.result

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchStatsTest {
    @Test
    fun `learning diagnostics should survive copies and combine worker lifetimes`() {
        val first = SearchStats(learning = mapOf("learningClauseConsumed" to 2L))
        val second = SearchStats(learning = mapOf("learningClauseConsumed" to 3L))

        val combined = first.copy().mergedWith(second)

        assertEquals(mapOf("learningClauseConsumed" to 5L), combined.learning)
    }

    @Test
    fun `value guidance observations should add across worker lifetimes`() {
        val first = SearchStats(valueOrdering = ValueOrderingStats(1, 2, 3, 4, 5, 6))
        val second = SearchStats(valueOrdering = ValueOrderingStats(6, 5, 4, 3, 2, 1))

        val combined = first.mergedWith(second)

        assertEquals(ValueOrderingStats(7, 7, 7, 7, 7, 7), combined.valueOrdering)
    }

    @Test
    fun `relearned clause in any literal order should count as relearned`() {
        val sink = SearchStatsSink()
        sink.observeLearned(intArrayOf(4, 9, 12), lbd = 2)

        sink.observeLearned(intArrayOf(12, 4, 9), lbd = 2)

        assertEquals(1.0, sink.snapshot().relearned.sum)
    }

    @Test
    fun `learned clause sizes and LBDs should accumulate`() {
        val sink = SearchStatsSink()

        sink.observeLearned(intArrayOf(1, 2, 3), lbd = 2)
        sink.observeLearned(intArrayOf(5), lbd = 1)

        val stats = sink.snapshot()
        assertEquals(
            listOf(2.0, 4.0, 3.0),
            listOf(stats.learnedClauses.sum, stats.learnedLiterals.sum, stats.learnedLbd.sum),
        )
    }

    @Test
    fun `clausal probe counters should add across workers`() {
        val first = SearchStatsSink().apply {
            clausalPrimalStarts = 1L
            clausalPrimalAccepted = 1L
            clausalPrimalIncomplete = 3L
        }.snapshot()
        val second = SearchStatsSink().apply {
            clausalPrimalStarts = 2L
            clausalPrimalIncomplete = 4L
        }.snapshot()

        val combined = first.mergedWith(second)

        assertEquals(
            listOf(3.0, 1.0, 7.0),
            listOf(
                combined.clausalPrimalStarts.sum,
                combined.clausalPrimalAccepted.sum,
                combined.clausalPrimalIncomplete.sum,
            ),
        )
    }
}
