package com.eignex.klause.portfolio

import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.relaxation.leafRealFeasibility
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Completes a local-search candidate over LP-only continuous columns the way a backtrack leaf is completed: pin every
 * Boolean and integer column to the candidate's value and solve the residual LP over the continuous columns exactly.
 *
 * The candidate's own continuous values are floating-point guesses, so they are only where the search stood; the
 * exact LP decides whether a completion exists and supplies its rational point. [objective], when given, is what the
 * residual LP minimizes, so the completion is the best one for the candidate's discrete values. A solve cut short by
 * [budget] decides nothing.
 */
internal class LeafRealCompletion(
    private val problem: Problem,
    private val objective: LinearObjective?,
    private val budget: Duration = DEFAULT_BUDGET,
) : CandidateCompletion {
    override fun complete(candidate: Sample): Completion {
        val real = leafRealFeasibility(problem, objective, candidate, Cancellation.after(budget))
        return when (real.verdict) {
            LpVerdict.FEASIBLE, LpVerdict.ATTAINED_OPTIMUM, LpVerdict.UNBOUNDED ->
                Completion.Witness(candidate.copy(reals = real.reals, exactReals = real.exactReals))

            LpVerdict.INFEASIBLE -> Completion.Refuted()

            else -> Completion.Undecided
        }
    }

    private companion object {
        val DEFAULT_BUDGET: Duration = 1.seconds
    }
}
