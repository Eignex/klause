package com.eignex.klause.solver.result

import com.eignex.kumulant.stat.summary.CountStat
import com.eignex.kumulant.stat.summary.SumResult

/**
 * Conflict-analysis gate breakdown: conflicts whose 1UIP analysis produced no
 * usable learned clause. Their sum with [SearchStats.learnedClauses] approximates the
 * conflicts that reached analysis. See [SolveStats].
 */
data class ConflictAnalysisStats(
    /** Analysis produced no usable clause (a NotApplicable seed). */
    val notApplicable: SumResult = ZERO_COUNT,
    /** 1UIP clause was non-asserting (>1 literal at the conflict level) → chronological backtrack. */
    val nonAsserting: SumResult = ZERO_COUNT,
) {
    /** Combine two workers' conflict-analysis counts (additive). */
    fun mergedWith(o: ConflictAnalysisStats): ConflictAnalysisStats = ConflictAnalysisStats(
        notApplicable = SumResult(notApplicable.sum + o.notApplicable.sum),
        nonAsserting = SumResult(nonAsserting.sum + o.nonAsserting.sum),
    )
}

/** Mutable [ConflictAnalysisStats] accumulator. See [SolveStatsSink]. */
internal class ConflictAnalysisStatsSink {
    val notApplicable: CountStat = CountStat()
    val nonAsserting: CountStat = CountStat()

    fun observeNotApplicable() = notApplicable.update(1.0)
    fun observeNonAsserting() = nonAsserting.update(1.0)

    fun snapshot(): ConflictAnalysisStats = ConflictAnalysisStats(
        notApplicable = notApplicable.read(),
        nonAsserting = nonAsserting.read(),
    )
}
