package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.LP_WORK_PER_NODE
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.relaxation.LeafRealResult
import com.eignex.klause.lp.relaxation.LeafRealSolver
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
import com.eignex.klause.solver.validation.permitsToleranceOptimum
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.theory.AbsRealCompletion
import com.eignex.klause.util.Cancellation
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
    private val context: LpSolveContext = if (!toleranceCheck.permitsToleranceOptimum()) {
        LpSolveContext.Production.copy(sparsePointRecovery = true)
    } else LpSolveContext.Production,
    private val retained: Boolean = false,
) : CandidateCompletion, AutoCloseable {
    constructor(problem: Problem, objective: LinearObjective?) : this(problem, objective, null)

    private val encoders = problem.selectedFactorProjections()
    private val encoding = LinearCompletionEncoding(encoders)
    private val absCompletion = if (problem.factors.any { it is RealAbs }) AbsRealCompletion(problem) else null
    private var owner: LeafRealSolver? = null
    private var closed = false
    var observeLp: ((LpVerdict, LpStats) -> Unit)? = null
    var observeFloat: ((Sample) -> Unit)? = null

    override fun complete(candidate: Sample, cancellation: Cancellation): Completion {
        check(!closed) { "real completion is closed" }
        if (cancellation()) {
            owner?.releaseSolvers()
            return Completion.Undecided(movesFor(0.0))
        }
        if (objective == null || objective.realCoefficientValues.all { it == 0.0 }) {
            absCompletion?.let { completion ->
                val checked = completion.complete(candidate, cancellation)
                val work = movesFor(checked.work.toDouble())
                return when (val result = checked.result) {
                    is AbsRealCompletion.Result.Witness -> {
                        val coordinates = DoubleArray(result.reals.size) { result.reals[it].toDouble() }
                        val sample = candidate.copy(reals = coordinates, exactReals = result.reals)
                        if (verifyRealCoordinates(problem, sample) is Verification.Accepted) {
                            Completion.Witness(sample, work)
                        } else Completion.Undecided(work)
                    }
                    AbsRealCompletion.Result.Refuted -> Completion.Refuted(
                        problem.factors.indices.filter { factor ->
                            problem.factors[factor].variables.reals.isNotEmpty()
                        }.toIntArray(),
                        work,
                    )
                    AbsRealCompletion.Result.Undecided -> Completion.Undecided(work)
                }
            }
        }
        val encoded = encoding.modelFor(candidate, cancellation) ?: run {
            owner?.releaseSolvers()
            return Completion.Undecided(movesFor(0.0))
        }
        val sink = LpStatsSink(LpRoute.STANDALONE)
        val check = if (observeFloat == null) toleranceCheck else FiniteSourceAcceptance(
            { sample ->
                observeFloat?.invoke(sample)
                toleranceCheck?.invoke(sample) == true
            },
            toleranceCheck.permitsToleranceOptimum(),
        )
        val real = if (retained) {
            retainedSolve(candidate, cancellation, sink, check, encoded)
        } else {
            leafRealFeasibility(problem, objective, candidate, cancellation, sink = sink,
                context = context, toleranceCheck = check, encoders = encoders, sourceEncoding = encoded)
        }
        val stats = sink.snapshot()
        val pointWork = stats.phases["EXACT_POINT_RECOVERY_STANDALONE"]?.work?.toDouble() ?: 0.0
        val work = movesFor(stats.standaloneWorkOps.sum + stats.componentWorkOps.sum + pointWork)
        observeLp?.invoke(real.verdict, stats)
        if (cancellation()) {
            owner?.releaseSolvers()
            return Completion.Undecided(work)
        }
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

    @Suppress("TooGenericExceptionCaught") // Discard failed numerical ownership while preserving the primary failure.
    private fun retainedSolve(
        candidate: Sample,
        cancellation: Cancellation,
        sink: LpStatsSink,
        check: ((Sample) -> Boolean)?,
        encoded: Problem,
    ): LeafRealResult {
        val active = owner ?: LeafRealSolver(problem, objective, context = context).also { owner = it }
        return try {
            active.solve(candidate, cancellation, check, sourceEncoding = encoded, stats = sink)
        } catch (primary: Throwable) {
            owner = null
            try {
                active.close()
            } catch (cleanup: Throwable) {
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            }
            throw primary
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val previous = owner
        owner = null
        previous?.close()
    }

    private companion object {
        // LP work in local-search moves, at the rates a portfolio weighs a node by: LP work per node, and moves per
        // node. Rounded up, so a completion is never free.
        fun movesFor(lpWork: Double): Long =
            ceil(lpWork / LP_WORK_PER_NODE * LS_INSTRUCTIONS_PER_WORK).toLong().coerceAtLeast(1L)
    }
}
