package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.util.Cancellation

internal data class LinearAuthorityReplayResult(
    val query: LinearAuthorityCapturedQuery?,
    val operations: Int,
    val work: Long,
    val allocation: Long,
    val stopped: Boolean,
)

internal object LinearAuthorityReplay {
    fun activeRows(
        query: LinearAuthorityCapturedQuery,
        maximumWork: Long,
        maximumAllocation: Long,
        stop: Cancellation,
    ): LinearAuthorityReplayResult {
        val funding = SourceLpFunding(maximumWork, maximumAllocation).apply { grant(maximumWork) }
        val budget = SourceLpBudget(maxOperations = 2, funding = funding)
        val capture = LinearAuthorityCapture(1, 1_000_000L, 16_777_216L, stop)
        LinearActiveRows(query.context, budget).use { checker ->
            var result: LinearActiveResult
            do {
                result = checker.check(query.context.scope.condition, stop, capture = capture)
            } while (result.paused && !stop() && !funding.exhausted)
        }
        return LinearAuthorityReplayResult(capture.records.singleOrNull(), budget.operations,
            funding.spent, funding.allocated, stop() || funding.exhausted)
    }

    fun run(
        query: LinearAuthorityCapturedQuery,
        mask: Set<Int>,
        maximumWork: Long,
        maximumAllocation: Long,
        stop: Cancellation,
    ): LinearAuthorityReplayResult {
        val funding = SourceLpFunding(maximumWork, maximumAllocation).apply { grant(maximumWork) }
        val budget = SourceLpBudget(maxOperations = 1, funding = funding)
        val capture = LinearAuthorityCapture(1, 1_000_000L, 16_777_216L, stop)
        LinearAuthority(budget, retainLp = true).use { authority ->
            authority.capture = capture
            var result: LinearAuthorityResult
            do {
                result = authority.advance(query.context, stop, cuts = true, cutMask = mask)
            } while (result.paused && !stop() && !funding.exhausted)
        }
        return LinearAuthorityReplayResult(capture.records.singleOrNull(), budget.operations,
            funding.spent, funding.allocated, stop() || funding.exhausted)
    }
}
