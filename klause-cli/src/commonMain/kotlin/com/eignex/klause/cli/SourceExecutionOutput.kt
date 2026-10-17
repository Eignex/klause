package com.eignex.klause.cli

import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.EngineParams
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PolynomialTheoryExecution
import com.eignex.klause.solver.pipeline.PolynomialTheoryStats
import com.eignex.klause.solver.pipeline.SourceExecutionCallbacks
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.executionKind
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration
import kotlin.time.TimeSource

internal fun solveCapturedSource(
    solvable: Solvable,
    rendering: SourceRendering.ExactSource,
    common: CommonOptions,
    output: OutputProtocol,
    publication: ArithmeticPublication,
    controls: SourceExecutionRequest,
    presolveConfig: PresolveConfig,
    presolveCancellation: Cancellation,
    presolveBudget: PresolveBudget?,
    budgetSpent: (Boolean) -> Boolean,
) {
    val invocation = rendering.invocation
    val kind = SourceSetup.executionKind(invocation)
    if (invocation.operation == ExecutionOperation.ENUMERATE && kind != SourceExecutionKind.REFUTED) {
        usageError("all-solution enumeration is unavailable for this exact source")
    }
    if (kind == SourceExecutionKind.LINEAR && EngineParams(common.engineParams).bool("dry-run-presolve") == true) {
        val request = (invocation.route as SourceProblemRoute.OpenTheory).request.withPresolve(
            presolveConfig, cancellation = presolveCancellation, budget = presolveBudget,
        )
        val mark = TimeSource.Monotonic.markNow()
        val prepared = OpenTheoryPipeline.prepare(request)
        SolveCore.printPresolved(
            request.model, prepared.model, prepared.stats, mark.elapsedNow(),
            loadElapsedMs = null, constructionBakeElapsed = Duration.ZERO,
        )
        errPrintln("  open sides closed: ${prepared.closedSides}")
        presolveBudget?.let { errPrintln("  work: ${it.spent()} of ${it.allowance}") }
        if (prepared.infeasible) errPrintln("  preparation refuted the model")
        return
    }
    val complete = common.resolvedEngine() !in setOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)
    output.begin(solvable.optimize, solvable.maximize)
    val result = SourceSetup.execute(
        controls,
        SourceExecutionCallbacks(onLinearIncumbent = { accepted ->
            if (solvable.optimize) {
                publication.publish(
                    rendering.render(OpenTheoryAssignment.Sampled(accepted.sample)),
                    accepted.value,
                    accepted.version,
                )
            }
        }, onQuadraticIncumbent = { accepted ->
            publication.publish(
                rendering.render(polynomialAssignment(accepted.incumbent.sample)),
                SourceValue.Algebraic(accepted.incumbent.value),
                accepted.version,
            )
        }),
    )
    when (result) {
        is SourceExecutionResult.Linear -> {
            when (val execution = result.result) {
                is OpenTheoryExecution.Optimize -> reportOpenTheoryOptimum(
                    execution, solvable.maximize, rendering.render, common.statistics, output,
                    solvable.routingLpStats, solvable.routingElapsedMs,
                    completePool = complete && kind != SourceExecutionKind.HEURISTIC,
                    publication = publication, budgetExhausted = budgetSpent,
                )

                is OpenTheoryExecution.Satisfy -> {
                    val outcome = execution.result
                    val stats = outcome.stats.copy(lp = outcome.stats.lp.mergedWith(solvable.routingLpStats))
                    output.onVerdictContext(VerdictContext(
                        budgetExhausted = budgetSpent(stats.run.timedOut),
                        terminationReason = (outcome as? OpenTheoryResult.Unknown)?.reason,
                    ))
                    when (outcome) {
                        is OpenTheoryResult.Sat -> {
                            output.onSolution(rendering.render(outcome.assignment), null)
                            output.onComplete(Verdict.SATISFIABLE)
                        }
                        is OpenTheoryResult.Unsat -> output.onComplete(Verdict.UNSATISFIABLE)
                        is OpenTheoryResult.Unknown -> output.onComplete(Verdict.UNKNOWN)
                    }
                    if (common.statistics) output.onStatistics(
                        stats, stats.run.wallMs + solvable.routingElapsedMs,
                        if (outcome is OpenTheoryResult.Sat) 1L else 0L,
                    )
                }
            }
            presolveBudget?.let { budget ->
                cliLogger(common.verbose).v { "presolve [open]: work ${budget.spent()} of ${budget.allowance}" }
            }
        }

        is SourceExecutionResult.Refuted -> {
            output.onComplete(Verdict.UNSATISFIABLE)
            if (common.statistics) output.onStatistics(
                SolveStats(lp = solvable.routingLpStats), solvable.routingElapsedMs, 0L,
            )
        }

        is SourceExecutionResult.Numerical -> {
            val outcome = result.result
            if (solvable.optimize) {
                reportLinearOptimization(
                    outcome, { rendering.render(OpenTheoryAssignment.Sampled(it)) }, output, publication,
                    common.statistics, solvable.routingElapsedMs, complete, solvable.routingLpStats,
                )
            } else {
                output.onVerdictContext(VerdictContext(terminationReason = outcome.reason))
                val point = outcome.incumbent
                if (point != null) output.onSolution(rendering.render(OpenTheoryAssignment.Sampled(point)), null)
                output.onComplete(when {
                    point != null -> Verdict.SATISFIABLE
                    outcome.terminal?.claim == com.eignex.klause.solver.evidence.SourceClaim.Refutation ->
                        Verdict.UNSATISFIABLE
                    else -> Verdict.UNKNOWN
                })
                if (common.statistics) output.onStatistics(
                    outcome.stats, outcome.stats.run.wallMs + solvable.routingElapsedMs,
                    if (point == null) 0L else 1L,
                )
            }
        }

        is SourceExecutionResult.Quadratic -> reportQuadraticOptimization(
            result.result,
            { rendering.render(polynomialAssignment(it)) },
            output,
            common.statistics,
            solvable.routingElapsedMs,
            complete,
            publication,
        )

        is SourceExecutionResult.Polynomial -> reportNativeSatisfaction(
            result.result,
            rendering,
            output,
            common.statistics,
            solvable.routingElapsedMs,
            complete,
            retainHeuristicRefutation = kind == SourceExecutionKind.NONLINEAR_INTEGER,
        )

        is SourceExecutionResult.Transcendental -> reportNativeSatisfaction(
            PolynomialTheoryExecution(
                result.result.result,
                PolynomialTheoryStats(workSpent = result.result.workSpent),
                nativeWorkScope = "transcendental",
                nativeResource = if (result.result.representationDeclined) "RepresentationLimit" else null,
            ),
            rendering,
            output,
            common.statistics,
            solvable.routingElapsedMs,
            complete,
            retainHeuristicRefutation = false,
        )

        is SourceExecutionResult.Declined -> {
            output.onVerdictContext(VerdictContext(terminationReason = result.reason, completePool = complete))
            output.onComplete(Verdict.UNKNOWN)
            if (common.statistics) output.onStatistics(SolveStats.EMPTY, solvable.routingElapsedMs, 0L)
        }

        is SourceExecutionResult.Finite -> error("finite source execution requires its finite renderer")
    }
}

private fun reportNativeSatisfaction(
    execution: PolynomialTheoryExecution,
    rendering: SourceRendering.ExactSource,
    output: OutputProtocol,
    statistics: Boolean,
    routingElapsedMs: Long,
    complete: Boolean,
    retainHeuristicRefutation: Boolean,
) {
    val result = if (!complete && !retainHeuristicRefutation && execution.result is OpenTheoryResult.Unsat) {
        OpenTheoryResult.Unknown(TerminationReason.BudgetExhausted, execution.result.stats)
    } else {
        execution.result
    }
    output.onVerdictContext(
        VerdictContext(
            terminationReason = (result as? OpenTheoryResult.Unknown)?.reason,
            completePool = complete,
        ),
    )
    when (result) {
        is OpenTheoryResult.Sat -> {
            output.onSolution(rendering.render(result.assignment), null)
            output.onComplete(Verdict.SATISFIABLE)
        }

        is OpenTheoryResult.Unsat -> output.onComplete(Verdict.UNSATISFIABLE)

        is OpenTheoryResult.Unknown -> output.onComplete(Verdict.UNKNOWN)
    }
    if (!statistics) return
    output.onStatistics(
        result.stats,
        result.stats.run.wallMs + routingElapsedMs,
        if (result is OpenTheoryResult.Sat) 1L else 0L,
    )
    val native = execution.stats
    val pairs = if (execution.nativeWorkScope == "transcendental") {
        listOf("transcendentalWork" to native.workSpent.toString()) +
            execution.nativeResource?.let { listOf("transcendentalResource" to it) }.orEmpty()
    } else {
        listOf(
            "nraWork" to native.workSpent.toString(),
            "nraProjectedPolynomials" to native.projectedPolynomials.toString(),
            "nraLiftedCells" to native.liftedCells.toString(),
        ) + listOfNotNull(
            native.dyadicAttempts?.let { "nraDyadicAttempts" to it.toString() },
            native.dyadicBases?.let { "nraDyadicBases" to it.toString() },
            native.coupledWorkDeclines?.let { "nraCoupledWorkDeclines" to it.toString() },
            native.dyadicPreExtractionDeclines?.let { "nraDyadicPreExtractionDeclines" to it.toString() },
        ) + execution.resource?.let { listOf("nraResource" to it.name) }.orEmpty()
    }
    output.onAllowanceStatistics(pairs)
}
