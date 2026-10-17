package com.eignex.klause.cli

import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PolynomialTheoryExecution
import com.eignex.klause.solver.pipeline.PolynomialTheoryStats
import com.eignex.klause.solver.pipeline.SourceExecutionCallbacks
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.executionKind
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason

internal fun solveNativeSource(
    solvable: Solvable,
    pipeline: SolvablePipeline.NativeSource,
    common: CommonOptions,
    output: OutputProtocol,
    publication: ArithmeticPublication,
    controls: SourceExecutionRequest,
) {
    val invocation = pipeline.invocation
    if (invocation.operation == ExecutionOperation.ENUMERATE) {
        usageError("all-solution enumeration is unavailable for this exact source")
    }
    val kind = SourceSetup.executionKind(invocation)
    val complete = common.resolvedEngine() !in setOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)
    output.begin(solvable.optimize, solvable.maximize)
    val result = SourceSetup.execute(
        controls,
        SourceExecutionCallbacks(onQuadraticIncumbent = { accepted ->
            publication.publish(
                pipeline.render(polynomialAssignment(accepted.incumbent.sample)),
                SourceValue.Algebraic(accepted.incumbent.value),
                accepted.version,
            )
        }),
    )
    when (result) {
        is SourceExecutionResult.Quadratic -> reportQuadraticOptimization(
            result.result,
            { pipeline.render(polynomialAssignment(it)) },
            output,
            common.statistics,
            solvable.routingElapsedMs,
            complete,
            publication,
        )

        is SourceExecutionResult.Polynomial -> reportNativeSatisfaction(
            result.result,
            pipeline,
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
            pipeline,
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

        is SourceExecutionResult.Finite, is SourceExecutionResult.Linear, is SourceExecutionResult.Refuted,
        is SourceExecutionResult.Numerical,
        ->
            error("native source execution returned an unrelated execution family")
    }
}

private fun reportNativeSatisfaction(
    execution: PolynomialTheoryExecution,
    pipeline: SolvablePipeline.NativeSource,
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
            output.onSolution(pipeline.render(result.assignment), null)
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
