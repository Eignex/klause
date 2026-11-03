package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.util.Cancellation

internal data class LinearAuthorityReplayResult(
    val query: LinearAuthorityCapturedQuery?,
    val operations: Int,
    val work: Long,
    val allocation: Long,
    val stopped: Boolean,
    val strongestBound: SourceEvidence? = query?.bound,
    val queries: List<LinearAuthorityCapturedQuery> = listOfNotNull(query),
    val observations: Map<String, Long> = emptyMap(),
)

internal object LinearAuthorityReplay {
    fun activeRows(
        query: LinearAuthorityCapturedQuery,
        maximumWork: Long,
        maximumAllocation: Long,
        stop: Cancellation,
        composeBranches: Boolean = false,
    ): LinearAuthorityReplayResult {
        val funding = SourceLpFunding(maximumWork, maximumAllocation).apply { grant(maximumWork) }
        val budget = SourceLpBudget(maxOperations = 2, funding = funding)
        val capture = LinearAuthorityCapture(1, 1_000_000L, 16_777_216L, stop)
        LinearActiveRows(query.context, budget, composeBranches = composeBranches).use { checker ->
            var result: LinearActiveResult
            do {
                result = checker.check(query.context.scope.condition, stop, capture = capture)
            } while (result.paused && !stop() && !funding.exhausted)
        }
        return LinearAuthorityReplayResult(capture.records.singleOrNull(), budget.operations,
            funding.spent, funding.allocated, stop() || funding.exhausted)
    }

    fun runSelected(
        query: LinearAuthorityCapturedQuery,
        maximumWork: Long,
        maximumAllocation: Long,
        stop: Cancellation,
    ): LinearAuthorityReplayResult {
        val funding = SourceLpFunding(maximumWork, maximumAllocation).apply { grant(maximumWork) }
        val budget = SourceLpBudget(maxOperations = 2, funding = funding)
        val capture = LinearAuthorityCapture(2, 1_000_000L, 16_777_216L, stop)
        val result = LinearAuthority(budget, retainLp = true).use { authority ->
            authority.capture = capture
            var completed: LinearAuthorityResult
            do {
                completed = authority.advanceWithPointCuts(query.context, stop)
            } while (completed.paused && !stop() && !funding.exhausted)
            completed
        }
        val records = capture.records
        return LinearAuthorityReplayResult(records.lastOrNull(), budget.operations, funding.spent, funding.allocated,
            stop() || funding.exhausted, result.bound, records)
    }

    fun run(
        query: LinearAuthorityCapturedQuery,
        mask: Set<Int>,
        maximumWork: Long,
        maximumAllocation: Long,
        stop: Cancellation,
    ): LinearAuthorityReplayResult = runWithCandidates(query, mask, emptyList(), true,
        maximumWork, maximumAllocation, stop)

    fun runWithCandidates(
        query: LinearAuthorityCapturedQuery,
        mask: Set<Int>?,
        additional: List<LinearAuthorityCut>,
        cuts: Boolean,
        maximumWork: Long,
        maximumAllocation: Long,
        stop: Cancellation,
    ): LinearAuthorityReplayResult = solve(query, mask, additional, cuts, false,
        maximumWork, maximumAllocation, stop)

    fun generatedAggregates(
        query: LinearAuthorityCapturedQuery, maximumWork: Long, maximumAllocation: Long, stop: Cancellation,
    ): LinearAuthorityReplayResult = solve(query, null, emptyList(), true, true,
        maximumWork, maximumAllocation, stop)

    private fun solve(
        query: LinearAuthorityCapturedQuery, mask: Set<Int>?, additional: List<LinearAuthorityCut>,
        cuts: Boolean, aggregate: Boolean, maximumWork: Long, maximumAllocation: Long, stop: Cancellation,
    ): LinearAuthorityReplayResult {
        val funding = SourceLpFunding(maximumWork, maximumAllocation).apply { grant(maximumWork) }
        val budget = SourceLpBudget(maxOperations = 1, funding = funding)
        val capture = LinearAuthorityCapture(1, 1_000_000L, 16_777_216L, stop)
        val observations = LinearAuthority(budget, retainLp = true, additionalCuts = additional,
            aggregateCuts = aggregate).use { authority ->
            authority.capture = capture
            var result: LinearAuthorityResult
            do {
                result = authority.advance(query.context, stop, cuts = cuts, cutMask = mask)
            } while (result.paused && !stop() && !funding.exhausted)
            authority.cutObservations()
        }
        return LinearAuthorityReplayResult(capture.records.singleOrNull(), budget.operations,
            funding.spent, funding.allocated, stop() || funding.exhausted, observations = observations)
    }
}
