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
    @Test
    fun `clausal probe counters add across workers`() {
        val first = SearchStatsSink().apply {
            clausalPrimalStarts = 1L
            clausalPrimalTrials = 3L
            clausalPrimalModels = 2L
            clausalPrimalProposals = 1L
            clausalPrimalAccepted = 1L
            clausalPrimalRejected = 1L
            clausalPrimalInfeasible = 2L
            clausalPrimalIncomplete = 3L
        }.snapshot()
        val second = SearchStatsSink().apply {
            clausalPrimalStarts = 2L
            clausalPrimalTrials = 4L
            clausalPrimalRejected = 2L
            clausalPrimalInfeasible = 3L
            clausalPrimalIncomplete = 4L
            clausalPrimalModels = 1L
            clausalPrimalProposals = 1L
        }.snapshot()

        val combined = first.mergedWith(second)

        assertEquals(
            listOf(3.0, 7.0, 3.0, 2.0, 1.0, 3.0, 5.0, 7.0),
            listOf(
                combined.clausalPrimalStarts.sum, combined.clausalPrimalTrials.sum, combined.clausalPrimalModels.sum,
                combined.clausalPrimalProposals.sum, combined.clausalPrimalAccepted.sum,
                combined.clausalPrimalRejected.sum, combined.clausalPrimalInfeasible.sum,
                combined.clausalPrimalIncomplete.sum,
            ),
        )
    }

}
