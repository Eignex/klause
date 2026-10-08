package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.portfolio.LeafRealCompletion
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation

/**
 * Completes a local-search candidate over an open model whose objective weighs continuous columns: the residual LP
 * minimizes [objective] over the candidate's continuous part, so the candidate is scored by its best completion.
 *
 * The LP relaxes a strict row to its closure, so its refutation stands, but its optimum can sit on a strict row's
 * boundary. That point is accepted only once the source model admits it exactly; any other case, or an LP that decides
 * nothing, falls back to [theory], whose completion is feasible but not optimized.
 */
internal class OptimizingCompletion(
    private val model: Problem,
    objective: LinearObjective,
    private val theory: CandidateCompletion,
) : CandidateCompletion {
    private val leaf = LeafRealCompletion(model, objective)

    override fun complete(candidate: Sample, cancellation: Cancellation): Completion {
        val optimized = leaf.complete(candidate, cancellation)
        if (optimized is Completion.Refuted) return optimized
        if (optimized is Completion.Witness && refuteOpenWitness(model, optimized.sample) == null) return optimized
        return when (val fallback = theory.complete(candidate, cancellation)) {
            is Completion.Witness -> Completion.Witness(fallback.sample, fallback.work + optimized.work)
            is Completion.Refuted -> Completion.Refuted(fallback.factors, fallback.work + optimized.work)
            is Completion.Undecided -> Completion.Undecided(fallback.work + optimized.work)
        }
    }
}
