package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.LP_WORK_PER_NODE
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.relaxation.leafRealFeasibility
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.math.ceil

/**
 * Completes a local-search candidate over LP-only continuous columns the way a backtrack leaf is completed: pin every
 * Boolean and integer column to the candidate's value and solve the residual LP over the continuous columns exactly.
 *
 * The candidate's own continuous values are floating-point guesses, so they are only where the search stood; the
 * exact LP decides whether a completion exists and supplies its rational point. [objective], when given, is what the
 * residual LP minimizes, so the completion is the best one for the candidate's discrete values. It runs under the
 * search's own cancellation, its work charged to the search; a solve that cancellation cuts short decides nothing.
 */
internal class LeafRealCompletion(private val problem: Problem, private val objective: LinearObjective?) :
    CandidateCompletion {
    override fun complete(candidate: Sample, cancellation: Cancellation): Completion {
        val sink = LpStatsSink(LpRoute.STANDALONE)
        val real = leafRealFeasibility(problem, objective, candidate, cancellation, sink = sink)
        val work = movesFor(sink.snapshot().standaloneWorkOps.sum)
        return when (real.verdict) {
            LpVerdict.FEASIBLE, LpVerdict.ATTAINED_OPTIMUM, LpVerdict.UNBOUNDED ->
                Completion.Witness(candidate.copy(reals = real.reals, exactReals = real.exactReals), work)

            // The rows the proof combines are what the candidate's discrete part cannot satisfy together.
            LpVerdict.INFEASIBLE -> Completion.Refuted(real.refutingFactors, work)

            else -> Completion.Undecided(work)
        }
    }

    private companion object {
        // LP work in local-search moves, at the rates a portfolio weighs a node by: LP work per node, and moves per
        // node. Rounded up, so a completion is never free.
        fun movesFor(lpWork: Double): Long =
            ceil(lpWork / LP_WORK_PER_NODE * LS_INSTRUCTIONS_PER_WORK).toLong().coerceAtLeast(1L)
    }
}
