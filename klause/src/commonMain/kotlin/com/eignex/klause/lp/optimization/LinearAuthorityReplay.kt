package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.util.ExecutionControl

internal data class LinearAuthorityReplayResult(
    val query: LinearAuthorityCapturedQuery?,
    val operations: Int,
    val work: Long,
    val allocation: Long,
    val stopped: Boolean,
    val strongestBound: SourceEvidence? = query?.bound,
    val queries: List<LinearAuthorityCapturedQuery> = listOfNotNull(query),
    val observations: Map<String, Long> = emptyMap(),
    val decline: String? = null,
)

internal object LinearAuthorityReplay {
    fun run(
        query: LinearAuthorityCapturedQuery,
        mask: Set<Int>,
        maximumWork: Long,
        maximumAllocation: Long,
        stop: ExecutionControl,
    ): LinearAuthorityReplayResult {
        val funding = SourceLpFunding(maximumWork, maximumAllocation).apply { grant(maximumWork) }
        val budget = SourceLpBudget(maxOperations = 1, funding = funding)
        val supplied = capturedCuts(query, mask, budget, stop) ?: return LinearAuthorityReplayResult(
            null, 0, funding.spent, funding.allocated, stop() || funding.exhausted,
            decline = "captured cut pool unavailable")
        val capture = LinearAuthorityCapture(1, 1_000_000L, 16_777_216L, stop)
        val observations = LinearAuthority(budget, retainLp = true).use { authority ->
            authority.useReplayCuts(supplied)
            authority.capture = capture
            var result: LinearAuthorityResult
            do {
                result = authority.advance(query.context, stop, cuts = true, cutMask = mask)
            } while (result.paused && !stop() && !funding.exhausted)
            authority.cutObservations()
        }
        return LinearAuthorityReplayResult(capture.records.singleOrNull(), budget.operations,
            funding.spent, funding.allocated, stop() || funding.exhausted, observations = observations)
    }

    private fun capturedCuts(
        query: LinearAuthorityCapturedQuery, mask: Set<Int>, budget: SourceLpBudget, stop: ExecutionControl,
    ): List<LinearAuthorityCut>? {
        val funding = checkNotNull(budget.funding)
        fun charge(work: Long, allocation: Long): Boolean {
            if (stop() || work > funding.availableWork || allocation > funding.remainingAllocation) return false
            budget.observe(preparation = work)
            funding.charge(0L, allocation)
            return true
        }
        if (stop() || funding.exhausted || query.projection.cuts != LinearAuthorityFeedbackRequest.Cuts.All ||
            query.cuts.size > 64 || mask.size > query.cuts.size) return null
        if (!charge(1L + query.cuts.size * 2L + mask.size, 128L + query.cuts.size * 16L)) return null
        if (stop() || funding.exhausted || mask.any { it !in query.cuts.indices }) return null
        val result = ArrayList<LinearAuthorityCut>(query.cuts.size)
        for ((id, cut) in query.cuts.withIndex()) {
            if (cut.id != id || cut.family == null) return null
            if (!charge(1L + cut.owners.size, 128L + cut.owners.size * 32L)) return null
            if (stop() || funding.exhausted) return null
            result += LinearAuthorityCut(cut.owner, cut.inequality, cut.derivation,
                cut.mixed, cut.owners, cut.family)
        }
        return result
    }
}
