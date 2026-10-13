package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation

internal class OpenLinearPortfolioSearch(val native: LinearPortfolioSearch) : ResumableSearch by native {
    override fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? = native.runSlice(global, sliceMillis, sliceNodes, onIncumbent)?.let { result ->
        // Captured source objectives can differ from the IR telemetry; only typed terminals can finish this arm.
        when (result) {
            is MinimizeResult.WithSample -> MinimizeResult.BestFound(result.sample, result.objective,
                incompleteReason(result), result.stats, result.evidence)
            is MinimizeResult.Infeasible -> MinimizeResult.Unknown(TerminationReason.Unsupported,
                result.stats, result.evidence)
            is MinimizeResult.Unknown -> result.copy(reason = incompleteReason(result))
        }
    }

    private fun incompleteReason(result: MinimizeResult): TerminationReason {
        val reason = when (result) {
            is MinimizeResult.BestFound -> result.reason
            is MinimizeResult.Unknown -> result.reason
            else -> TerminationReason.Unsupported
        }
        return if (reason == TerminationReason.SearchExhausted) TerminationReason.Unsupported else reason
    }
}
