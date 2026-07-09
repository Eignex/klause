package com.eignex.klause.portfolio

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason

/**
 * Pure result reductions shared by both [PortfolioExecutor] implementations — the parallel
 * `Portfolio` (jvm+native) and the single-core [SequentialPortfolio]. These are the parts of the
 * verdict/terminal/stats logic that are identical across the two executors regardless of how each
 * one gathers its workers' results (concurrent CAS incumbent vs bandit-scheduled locals). Keeping
 * them here means a change to, say, the four-way optimisation terminal shape lands in one place.
 */
internal object PortfolioReduction {

    /** Merge every result's counters into one [SolveStats] — the pool's total cost, not the
     *  winner's. [stats] projects the per-result counters (results carry no common supertype). */
    fun <T> foldStats(results: List<T>, stats: (T) -> SolveStats): SolveStats =
        results.fold(SolveStats.EMPTY) { acc, r -> acc.mergedWith(stats(r)) }

    /** Reduce a pool of per-worker satisfaction results to one verdict, preferring Sat, then Unsat,
     *  then Unknown/Cancelled, and folding every worker's counters into the winner. */
    fun verdict(results: List<SolveResult>): SolveResult {
        val stats = foldStats(results) { it.stats }
        return when (
            val winner = results.firstOrNull { it is SolveResult.Sat }
                ?: results.firstOrNull { it is SolveResult.Unsat }
        ) {
            is SolveResult.Sat -> winner.copy(stats = stats)
            is SolveResult.Unsat -> winner.copy(stats = stats)
            else -> SolveResult.Unknown(TerminationReason.Cancelled, stats)
        }
    }

    /** Whether a segment/worker terminal proves its whole space was covered (so any incumbent is
     *  optimal, or there is no solution). A slice-truncated or cancelled run reports
     *  Cancelled/BudgetExhausted instead, so `SearchExhausted` reliably tells a genuine exhaustion
     *  from a timed-out run. `null` (no terminal produced) is not exhausted. */
    fun isExhausted(result: MinimizeResult?): Boolean = when (result) {
        is MinimizeResult.Optimal -> true
        is MinimizeResult.Infeasible -> true
        is MinimizeResult.BestFound -> result.reason == TerminationReason.SearchExhausted
        is MinimizeResult.Unknown -> result.reason == TerminationReason.SearchExhausted
        null -> false
    }

    /**
     * The four-way optimisation terminal: given the shared incumbent ([sample] / [bound]) and
     * whether the run stopped [dirty] (timed out or cancelled before proving the space covered,
     * i.e. any worker not [isExhausted]), classify the outcome. Clean exhaustion with an incumbent
     * is Optimal; clean without one is Infeasible; a dirty stop keeps the incumbent as BestFound or,
     * with none, reports Unknown.
     */
    fun terminal(sample: Sample?, bound: Double, dirty: Boolean, stats: SolveStats): MinimizeResult = when {
        sample != null && dirty ->
            MinimizeResult.BestFound(sample, bound, TerminationReason.BudgetExhausted, stats)

        sample != null -> MinimizeResult.Optimal(sample, bound, stats)

        dirty -> MinimizeResult.Unknown(TerminationReason.BudgetExhausted, stats)

        else -> MinimizeResult.Infeasible(stats = stats)
    }
}
