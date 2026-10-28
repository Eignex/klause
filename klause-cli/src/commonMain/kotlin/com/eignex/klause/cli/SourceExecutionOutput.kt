package com.eignex.klause.cli

import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PolynomialTheoryExecution
import com.eignex.klause.solver.pipeline.PolynomialTheoryStats
import com.eignex.klause.solver.pipeline.SourceExecutionCallbacks
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.result.SolveStats
import kotlin.time.Duration

internal fun solveCapturedSource(
    solvable: Solvable,
    rendering: SourceRendering.ExactSource,
    common: CommonOptions,
    output: OutputProtocol,
    publication: ArithmeticPublication,
    controls: SourceExecutionRequest,
    budgetSpent: (Boolean) -> Boolean,
) {
    if (controls.prepareOnly) {
        val result = SourceSetup.execute(controls) as SourceExecutionResult.Prepared
        val prepared = result.result
        SolveCore.printPresolved(
            result.originalProblem, prepared.model, prepared.stats, result.elapsed,
            loadElapsedMs = null, constructionBakeElapsed = Duration.ZERO,
        )
        errPrintln("  open sides closed: ${prepared.closedSides}")
        controls.presolveBudget?.let { errPrintln("  work: ${it.spent()} of ${it.allowance}") }
        if (prepared.infeasible) errPrintln("  preparation refuted the model")
        return
    }
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
    val reporting = object : OutputProtocol by output {
        override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) {
            val observed = stats.copy(presolve = result.preparationStats ?: stats.presolve)
            output.onStatistics(observed, solveTimeMs, solutions)
        }
    }
    val complete = result.completeSearchAdmitted
    when (result) {
        is SourceExecutionResult.Linear -> {
            when (val execution = result.result) {
                is OpenTheoryExecution.Optimize -> reportOpenTheoryOptimum(
                    execution, solvable.maximize, rendering.render, common.statistics, reporting,
                    solvable.routingLpStats, solvable.routingElapsedMs,
                    completePool = complete,
                    publication = publication, budgetExhausted = budgetSpent,
                )

                is OpenTheoryExecution.Satisfy -> {
                    val outcome = execution.result
                    val stats = outcome.stats.copy(lp = outcome.stats.lp.mergedWith(solvable.routingLpStats))
                    reporting.onVerdictContext(VerdictContext(
                        budgetExhausted = budgetSpent(stats.run.timedOut),
                        terminationReason = (outcome as? OpenTheoryResult.Unknown)?.reason,
                    ))
                    when (outcome) {
                        is OpenTheoryResult.Sat -> {
                            reporting.onSolution(rendering.render(outcome.assignment), null)
                            reporting.onComplete(Verdict.SATISFIABLE)
                        }
                        is OpenTheoryResult.Unsat -> reporting.onComplete(Verdict.UNSATISFIABLE)
                        is OpenTheoryResult.Unknown -> reporting.onComplete(Verdict.UNKNOWN)
                    }
                    if (common.statistics) reporting.onStatistics(
                        stats, stats.run.wallMs + solvable.routingElapsedMs,
                        if (outcome is OpenTheoryResult.Sat) 1L else 0L,
                    )
                }
            }
            controls.presolveBudget?.let { budget ->
                cliLogger(common.verbose).v { "presolve [open]: work ${budget.spent()} of ${budget.allowance}" }
            }
        }

        is SourceExecutionResult.Refuted -> {
            reporting.onComplete(Verdict.UNSATISFIABLE)
            if (common.statistics) reporting.onStatistics(
                SolveStats(lp = solvable.routingLpStats, presolve = result.preparationStats),
                solvable.routingElapsedMs + (result.preparationStats?.effort?.elapsed?.inWholeMilliseconds ?: 0L), 0L,
            )
        }

        is SourceExecutionResult.Numerical -> {
            val outcome = result.result
            if (solvable.optimize) {
                reportLinearOptimization(
                    outcome, { rendering.render(OpenTheoryAssignment.Sampled(it)) }, reporting, publication,
                    common.statistics, solvable.routingElapsedMs, complete, solvable.routingLpStats,
                )
            } else {
                reporting.onVerdictContext(VerdictContext(terminationReason = outcome.reason))
                val point = outcome.incumbent
                if (point != null) reporting.onSolution(rendering.render(OpenTheoryAssignment.Sampled(point)), null)
                reporting.onComplete(when {
                    point != null -> Verdict.SATISFIABLE
                    outcome.terminal?.claim == com.eignex.klause.solver.evidence.SourceClaim.Refutation ->
                        Verdict.UNSATISFIABLE
                    else -> Verdict.UNKNOWN
                })
                if (common.statistics) reporting.onStatistics(
                    outcome.stats, outcome.stats.run.wallMs + solvable.routingElapsedMs,
                    if (point == null) 0L else 1L,
                )
            }
        }

        is SourceExecutionResult.Quadratic -> reportQuadraticOptimization(
            result.result,
            { rendering.render(polynomialAssignment(it)) },
            reporting,
            common.statistics,
            solvable.routingElapsedMs,
            complete,
            publication,
        )

        is SourceExecutionResult.Polynomial -> reportNativeSatisfaction(
            result.result,
            rendering,
            reporting,
            common.statistics,
            solvable.routingElapsedMs,
            complete,
        )

        is SourceExecutionResult.Transcendental -> reportNativeSatisfaction(
            PolynomialTheoryExecution(
                result.result.result,
                PolynomialTheoryStats(workSpent = result.result.workSpent),
                nativeWorkScope = "transcendental",
                nativeResource = if (result.result.representationDeclined) "RepresentationLimit" else null,
            ),
            rendering,
            reporting,
            common.statistics,
            solvable.routingElapsedMs,
            complete,
        )

        is SourceExecutionResult.Declined -> {
            reporting.onVerdictContext(VerdictContext(terminationReason = result.reason, completePool = complete))
            reporting.onComplete(Verdict.UNKNOWN)
            if (common.statistics) reporting.onStatistics(
                SolveStats(presolve = result.preparationStats), solvable.routingElapsedMs, 0L,
            )
        }

        is SourceExecutionResult.Finite -> error("finite source execution requires its finite renderer")
        is SourceExecutionResult.Prepared -> error("preparation diagnostics require their diagnostic renderer")
    }
}

internal fun reportNativeSatisfaction(
    execution: PolynomialTheoryExecution,
    rendering: SourceRendering.ExactSource,
    output: OutputProtocol,
    statistics: Boolean,
    routingElapsedMs: Long,
    completePool: Boolean?,
) {
    val result = execution.result
    output.onVerdictContext(
        VerdictContext(
            terminationReason = (result as? OpenTheoryResult.Unknown)?.reason,
            completePool = completePool,
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
    execution.integerCells?.let { cells ->
        output.onAllowanceStatistics(listOf(
            "niaCellProducer" to cells.producerEnabled.toString(),
            "niaCellReuse" to cells.reuseEnabled.toString(),
            "niaCellObserved" to cells.observed.toString(),
            "niaCellRetained" to cells.retained.toString(),
            "niaCellDeclined" to cells.declined.toString(),
            "niaExcludedTuples" to cells.excludedTuples.toString(),
            "niaRepeatedSupports" to cells.repeatedSupports.toString(),
            "niaCompletionChecks" to cells.completionChecks.toString(),
            "niaCellSteering" to cells.steeringEnabled.toString(),
            "niaCellSelections" to cells.selections.toString(),
            "niaSteeredTuples" to cells.steeredTuples.toString(),
            "niaAllowedTuples" to cells.allowedTuples.toString(),
            "niaDuplicateTuples" to cells.duplicateTuples.toString(),
        ))
    }
    execution.integerRoots?.let { roots ->
        output.onAllowanceStatistics(listOf(
            "niaRootEquations" to roots.admittedEquations.toString(),
            "niaRootDeclines" to roots.declinedEquations.toString(),
            "niaRootReuses" to roots.reusedAdmissions.toString(),
            "niaRootRefutedScopes" to roots.refutedScopes.toString(),
            "niaRootExcludedTuples" to roots.excludedTuples.toString(),
            "niaRootCompletionChecks" to roots.completionChecks.toString(),
        ))
    }
    execution.integerResidues?.let { residues ->
        output.onAllowanceStatistics(listOf(
            "niaResidueRings" to residues.completedRings.toString(),
            "niaResidueTuples" to residues.tuples.toString(),
            "niaResidueWork" to residues.workSpent.toString(),
            "niaResidueRefutedScopes" to residues.refutedScopes.toString(),
            "niaResidueDeclines" to residues.declinedChecks.toString(),
        ))
    }
}
