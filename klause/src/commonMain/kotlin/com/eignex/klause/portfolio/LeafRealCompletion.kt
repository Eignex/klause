package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.LP_WORK_PER_NODE
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.relaxation.leafRealFeasibility
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.resources.LS_INSTRUCTIONS_PER_WORK
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.solver.selectedFactorProjections
import com.eignex.klause.solver.validation.FiniteSourceAcceptance
import com.eignex.klause.solver.validation.ToleranceSearchResult
import com.eignex.klause.solver.validation.permitsToleranceOptimum
import com.eignex.klause.solver.validation.toleranceSearch
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.theory.AbsRealCompletion
import com.eignex.klause.theory.requiresRealCompletionSearch
import com.eignex.klause.util.ExecutionControl
import kotlin.math.ceil

/**
 * Completes a local-search candidate over LP-only continuous columns the way a backtrack leaf is completed: pin every
 * Boolean and integer column to the candidate's value and solve the residual LP over the continuous columns.
 *
 * The candidate's own continuous values are floating-point guesses; the residual LP decides whether a completion
 * exists. [objective], when given, is what that LP minimizes. An exact point is a witness outright; a tolerance-only
 * point is one only when [toleranceCheck] accepts it. A solve that cancellation cuts short decides nothing.
 */
internal class LeafRealCompletion(
    private val problem: Problem,
    private val objective: LinearObjective?,
    private val toleranceCheck: ((Sample) -> Boolean)?,
    private val context: LpSolveContext = LpSolveContext.finite(
        problem.semantics.effective.feasibilityTolerance?.isExact == false,
    ).copy(sparsePointRecovery = !toleranceCheck.permitsToleranceOptimum()),
) : CandidateCompletion {
    constructor(problem: Problem, objective: LinearObjective?) : this(problem, objective, null)

    private val encoders = problem.selectedFactorProjections()
    private val encoding = LinearCompletionEncoding(encoders)

    // The source's search within its tolerance, present when acceptance admits tolerance points.
    private val toleranceSearch = toleranceCheck.toleranceSearch()
    private val absCompletion = if (problem.factors.any { it is RealAbs } ||
        (toleranceSearch != null && problem.requiresRealCompletionSearch())
    ) AbsRealCompletion(problem) else null
    private val realFactors = problem.factors.indices.filter { problem.factors[it].variables.reals.isNotEmpty() }
        .toIntArray()
    var observeLp: ((LpVerdict, LpStats) -> Unit)? = null
    var observeFloat: ((Sample) -> Unit)? = null

    override fun complete(candidate: Sample, cancellation: ExecutionControl): Completion {
        val realObjective = objective != null && objective.realCoefficientValues.any { it != 0.0 }
        val search = toleranceSearch?.takeIf { !realObjective }
            ?: return absCompletion?.takeIf { !realObjective }?.let { exactSearch(it, candidate, cancellation, 0L) }
                ?: linearCompletion(candidate, cancellation)
        // A source that accepts points within its tolerance offers the floating completion first, as a backtrack
        // leaf does; a refused point leaves the decision to the search within tolerance.
        val floating = linearCompletion(candidate, cancellation)
        if (floating is Completion.Witness || cancellation()) return floating
        val found = search.complete(candidate, cancellation, null, context)
        val work = floating.work + movesFor(found.work.toDouble())
        return when (found) {
            // The LP's proof names the rows the candidate's discrete part cannot satisfy, when it gave one.
            is ToleranceSearchResult.Refuted -> Completion.Refuted(
                (floating as? Completion.Refuted)?.factors?.takeIf { it.isNotEmpty() } ?: realFactors, work,
            )
            is ToleranceSearchResult.Undecided -> Completion.Undecided(work)
            // Without the search the candidate keeps the verdict of its floating and exact completions.
            ToleranceSearchResult.Unavailable ->
                absCompletion?.let { exactSearch(it, candidate, cancellation, work) } ?: floating
            is ToleranceSearchResult.Witness -> {
                val tolerant = candidate.copy(reals = DoubleArray(found.reals.size) { found.reals[it].toDouble() },
                    exactReals = found.reals)
                if (cancellation()) return Completion.Undecided(work)
                if (toleranceCheck?.invoke(tolerant) != true) {
                    return absCompletion?.let { exactSearch(it, candidate, cancellation, work) } ?: floating
                }
                // An exact completion strains no relation, so it is preferred where the search finds one.
                val exact = absCompletion?.let { exactSearch(it, candidate, cancellation, work) }
                exact as? Completion.Witness ?: Completion.Witness(tolerant, exact?.work ?: work)
            }
        }
    }

    private fun exactSearch(
        search: AbsRealCompletion,
        candidate: Sample,
        cancellation: ExecutionControl,
        spent: Long,
    ): Completion {
        val checked = search.complete(candidate, cancellation)
        val work = spent + movesFor(checked.work.toDouble())
        return when (val result = checked.result) {
            is AbsRealCompletion.Result.Witness -> {
                val coordinates = DoubleArray(result.reals.size) { result.reals[it].toDouble() }
                val sample = candidate.copy(reals = coordinates, exactReals = result.reals)
                if (verifyRealCoordinates(problem, sample) is Verification.Accepted) Completion.Witness(sample, work)
                else Completion.Undecided(work)
            }
            AbsRealCompletion.Result.Refuted -> Completion.Refuted(realFactors, work)
            AbsRealCompletion.Result.Undecided -> Completion.Undecided(work)
        }
    }

    private fun linearCompletion(candidate: Sample, cancellation: ExecutionControl): Completion {
        val encoded = encoding.modelFor(candidate, cancellation) ?: return Completion.Undecided(movesFor(0.0))
        val sink = LpStatsSink(LpRoute.STANDALONE)
        val check = if (observeFloat == null) toleranceCheck else FiniteSourceAcceptance(
            { sample ->
                observeFloat?.invoke(sample)
                toleranceCheck?.invoke(sample) == true
            },
            toleranceCheck.permitsToleranceOptimum(),
            search = toleranceSearch,
        )
        val real = leafRealFeasibility(problem, objective, candidate, cancellation, sink = sink,
            context = context, toleranceCheck = check, encoders = encoders, sourceEncoding = encoded)
        val stats = sink.snapshot()
        val pointWork = stats.phases["EXACT_POINT_RECOVERY_STANDALONE"]?.work?.toDouble() ?: 0.0
        val work = movesFor(stats.standaloneWorkOps.sum + stats.componentWorkOps.sum + pointWork)
        observeLp?.invoke(real.verdict, stats)
        if (cancellation()) return Completion.Undecided(work)
        return when (real.verdict) {
            LpVerdict.FEASIBLE, LpVerdict.ATTAINED_OPTIMUM, LpVerdict.UNBOUNDED ->
                Completion.Witness(candidate.copy(reals = real.reals, exactReals = real.exactReals), work)

            LpVerdict.TOLERANCE_OPTIMUM -> {
                val completed = candidate.copy(reals = real.reals, exactReals = null)
                if (toleranceCheck?.invoke(completed) == true && !cancellation()) {
                    Completion.Witness(completed, work)
                } else Completion.Undecided(work)
            }

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
