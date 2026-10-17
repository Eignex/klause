package com.eignex.klause.cli

import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.LinearSourceConclusion
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryOptimum
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.SourceExecutionCallbacks
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.LinearSourceWitness

internal fun solveLinearSource(
    solvable: Solvable,
    rendering: SourceRendering.LinearSource,
    controls: SourceExecutionRequest,
    common: CommonOptions,
    output: OutputProtocol,
    publication: ArithmeticPublication,
) {
    output.begin(solvable.optimize, solvable.maximize)
    var points = 0L
    var lastPoint: LinearSourceWitness? = null
    var lastAccepted = false
    fun publish(point: LinearSourceWitness): Boolean {
        if (lastPoint === point) return lastAccepted
        lastPoint = point
        val value = point.objective
        lastAccepted = if (value != null) {
            publication.publish(rendering.render(point), SourceValue.Rational(value))
        } else {
            output.onSolution(rendering.render(point), null)
            points++
            true
        }
        return lastAccepted
    }
    val result = SourceSetup.execute(
        controls,
        SourceExecutionCallbacks(onLinearSourceIncumbent = { publish(it) }),
    )
    if (result is SourceExecutionResult.Finite) {
        when (val outcome = result.result.outcome) {
            FiniteSolveOutcome.PreparedOnly -> {
                val prepared = result.result.preparation.problem
                errPrintln(
                    "source preparation: factors ${prepared.numFactors}, ints ${prepared.numIntVars}, " +
                        "reals ${prepared.numRealVars}",
                )
                return
            }

            is FiniteSolveOutcome.DryRun -> {
                errPrintln(outcome.heading)
                outcome.lines.forEach(::errPrintln)
                return
            }

            is FiniteSolveOutcome.Completed -> Unit
        }
    }
    val qualified = result.linearSource
    if (qualified == null) {
        val declined = result as SourceExecutionResult.Declined
        output.onLinearSourceResult(result)
        output.onVerdictContext(VerdictContext(terminationReason = declined.reason))
        output.onComplete(Verdict.UNKNOWN)
        if (common.statistics) output.onStatistics(SolveStats.EMPTY, solvable.routingElapsedMs, 0L)
        return
    }
    val incumbentAccepted = qualified.incumbent?.let(::publish) == true
    output.onLinearSourceResult(result)
    val stats = result.linearSourceStats()
    output.onVerdictContext(
        VerdictContext(
        budgetExhausted = stats.run.timedOut,
        terminationReason = result.linearSourceReason(),
        completePool = result.completeSearchAdmitted,
    )
    )
    val solutions = if (solvable.optimize) publication.solutions else points
    val hasPoint = solutions > 0L
    output.onComplete(
        when (qualified.conclusion) {
            LinearSourceConclusion.OPTIMAL -> if (incumbentAccepted) {
                Verdict.OPTIMAL
            } else if (hasPoint) {
                Verdict.BEST_FOUND
            } else {
                Verdict.UNKNOWN
            }

            LinearSourceConclusion.UNSATISFIABLE -> Verdict.UNSATISFIABLE

            LinearSourceConclusion.UNBOUNDED -> if (hasPoint) Verdict.UNBOUNDED else Verdict.UNKNOWN

            LinearSourceConclusion.SATISFIABLE, LinearSourceConclusion.LIMIT, LinearSourceConclusion.UNKNOWN -> when {
                !hasPoint -> Verdict.UNKNOWN
                solvable.optimize -> Verdict.BEST_FOUND
                else -> Verdict.SATISFIABLE
            }
        },
    )
    if (!common.statistics) return
    output.onAllowanceStatistics(
        listOf(
        "sourceConclusion" to qualified.conclusion.name.lowercase(),
        "nativeConclusion" to qualified.nativeConclusion.name.lowercase(),
        "nativeClaimsUnrestricted" to qualified.nativeClaimsUnrestricted.toString(),
        "sourceObjectiveScale" to qualified.objectiveScale.toString(),
    )
    )
    val sourceStats = stats.copy(
        ls = stats.ls.copy(incumbentObjective = qualified.incumbent?.objective?.toDouble() ?: Double.NaN),
        lp = stats.lp.mergedWith(solvable.routingLpStats),
    )
    output.onStatistics(sourceStats, stats.run.wallMs + solvable.routingElapsedMs, solutions)
}

private fun SourceExecutionResult.linearSourceStats(): SolveStats = when (this) {
    is SourceExecutionResult.Numerical -> result.stats

    is SourceExecutionResult.Finite -> (result.outcome as? FiniteSolveOutcome.Completed)?.stats ?: SolveStats.EMPTY

    is SourceExecutionResult.Linear -> when (val execution = result) {
        is OpenTheoryExecution.Satisfy -> execution.result.stats
        is OpenTheoryExecution.Optimize -> execution.result.stats
    }

    is SourceExecutionResult.Polynomial -> result.result.stats

    is SourceExecutionResult.Quadratic -> result.result.stats

    is SourceExecutionResult.Transcendental -> result.result.stats

    is SourceExecutionResult.Prepared, is SourceExecutionResult.Declined, is SourceExecutionResult.Refuted ->
        SolveStats.EMPTY
}

private fun SourceExecutionResult.linearSourceReason(): TerminationReason? = when (this) {
    is SourceExecutionResult.Numerical -> result.reason

    is SourceExecutionResult.Declined -> reason

    is SourceExecutionResult.Linear -> when (val execution = result) {
        is OpenTheoryExecution.Satisfy -> (execution.result as? OpenTheoryResult.Unknown)?.reason
        is OpenTheoryExecution.Optimize -> (execution.result as? OpenTheoryOptimum.Bounded)?.reason
    }

    is SourceExecutionResult.Polynomial -> (result.result as? OpenTheoryResult.Unknown)?.reason

    is SourceExecutionResult.Quadratic -> when (val optimum = result.result) {
        is QuadraticOptimizationResult.Unknown -> optimum.reason
        is QuadraticOptimizationResult.BestFound -> optimum.reason
        else -> null
    }

    is SourceExecutionResult.Transcendental -> (result.result as? OpenTheoryResult.Unknown)?.reason

    is SourceExecutionResult.Finite -> if (linearSourceStats().run.timedOut) TerminationReason.Timeout else null

    is SourceExecutionResult.Prepared, is SourceExecutionResult.Refuted -> null
}
