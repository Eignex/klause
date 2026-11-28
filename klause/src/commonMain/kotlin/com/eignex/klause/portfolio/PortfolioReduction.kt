package com.eignex.klause.portfolio

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.VerifiedIncumbent
import com.eignex.klause.solver.objective.OptimalityGap
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason

internal object PortfolioReduction {

    fun unsettledReason(reasons: List<TerminationReason>): TerminationReason = when {
        TerminationReason.Unsupported in reasons -> TerminationReason.Unsupported
        TerminationReason.RepresentationDeclined in reasons -> TerminationReason.RepresentationDeclined
        else -> reasons.firstOrNull() ?: TerminationReason.Cancelled
    }

    // The pool's total cost, not the winner's.
    fun <T> foldStats(results: List<T>, stats: (T) -> SolveStats): SolveStats =
        results.fold(SolveStats.EMPTY) { acc, r -> acc.mergedWith(stats(r)) }

    fun verdict(results: List<SolveResult>): SolveResult {
        val stats = foldStats(results) { it.stats }
        return when (
            val winner = results.firstOrNull { it is SolveResult.Sat }
                ?: results.firstOrNull { it is SolveResult.Unsat }
        ) {
            is SolveResult.Sat -> winner.copy(stats = stats)
            is SolveResult.Unsat -> winner.copy(stats = stats)
            else -> SolveResult.Unknown(unsettledReason(results.filterIsInstance<SolveResult.Unknown>()
                .map { it.reason }), stats)
        }
    }

    // Whether a terminal proves its whole space was covered. A slice-truncated or cancelled run reports
    // Cancelled or BudgetExhausted, so `SearchExhausted` reliably means genuine exhaustion.
    fun isExhausted(result: MinimizeResult?): Boolean = when (result) {
        is MinimizeResult.Optimal -> true

        is MinimizeResult.Infeasible -> true

        // Unboundedness ends a run without covering the space, and no incumbent it leaves is optimal.
        is MinimizeResult.Unbounded -> false

        is MinimizeResult.BestFound -> result.reason == TerminationReason.SearchExhausted

        is MinimizeResult.Unknown -> result.reason == TerminationReason.SearchExhausted

        null -> false
    }

    // fallback is the pool's gap cutoff once it holds the arm's incumbents, never that of an earlier pool read,
    // which a pending local incumbent may undercut. gapFloor is the least gap cutoff any arm's shared facts may
    // carry.
    fun armExhaustionBound(searchBound: Double?, fallback: Double?, gapFloor: Double): Double? {
        val own = searchBound?.takeUnless { it.isNaN() } ?: fallback ?: return null
        return if (own < gapFloor) own else gapFloor
    }

    // Null when the proved bound reaches the incumbent, which is then exactly optimal.
    fun gapBound(gap: OptimalityGap?, incumbent: Double, incumbentBound: Double, proved: Double): Double? =
        if (gap == null || proved >= incumbent) null else gap.exhaustedBound(incumbentBound, proved)

    // dirty means the run stopped without proving the space covered. Taking one incumbent snapshot rather than a
    // (sample, bound) pair keeps a reported bound from belonging to a different assignment than its sample.
    fun terminal(
        incumbent: VerifiedIncumbent<Sample, Double>?,
        dirty: Boolean,
        reason: TerminationReason,
        stats: SolveStats,
        gapBound: Double? = null,
    ): MinimizeResult =
        when {
            incumbent != null && dirty -> MinimizeResult.BestFound(
                incumbent.assignment,
                incumbent.objective,
                reason,
                stats,
            )

            incumbent != null -> MinimizeResult.Optimal(incumbent.assignment, incumbent.objective, stats,
                gapBound = gapBound)

            dirty -> MinimizeResult.Unknown(reason, stats)

            else -> MinimizeResult.Infeasible(stats = stats)
        }
}
