package com.eignex.klause.cli

import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.executionKind
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats

internal data class CliRoutingStats(val outcome: String, val elapsedMs: Long)

internal data class CliInvocationStats(
    val frontendLoadMs: Long?,
    val frontendRouting: CliRoutingStats?,
    val solvePipeline: String,
    val declaredTheoryRoute: String?,
) {
    fun pairs(stats: SolveStats): List<Pair<String, String>> = buildList {
        frontendLoadMs?.let { add("frontendLoadMs" to it.toString()) }
        frontendRouting?.let {
            add("frontendRoutingMs" to it.elapsedMs.toString())
            add("frontendRoutingOutcome" to it.outcome)
        }
        add("solvePipeline" to solvePipeline)
        declaredTheoryRoute?.let { add("declaredTheoryRoute" to it) }
        stats.run.backend.takeIf { it.isNotEmpty() }?.let { add("reportedBackend" to it) }
        addAll(nativeCpStatPairs(stats.search))
    }
}

internal fun CommonOptions.resetInvocationStatistics() {
    loadElapsedMs = null
    frontendRouting = null
}

internal fun cliInvocationStats(common: CommonOptions, pipeline: SolvablePipeline): CliInvocationStats =
    CliInvocationStats(
        common.loadElapsedMs,
        common.frontendRouting,
        when (pipeline) {
            is SolvablePipeline.LinearSource -> "linear-source"

            is SolvablePipeline.NativeSource -> pipeline.nativePipelineName()

            SolvablePipeline.FiniteCp -> "finite-cp"

            is SolvablePipeline.NumericalLinear -> "numerical-mip"

            is SolvablePipeline.OpenTheory -> "open-theory"

            is SolvablePipeline.PolynomialTheory -> "polynomial-theory"

            is SolvablePipeline.NonlinearIntegerTheory -> "nonlinear-integer-theory"

            is SolvablePipeline.QuadraticTheory -> when (pipeline.request) {
                is QuadraticRequest.Polynomial -> "quadratic-polynomial"
                is QuadraticRequest.Integer -> "quadratic-integer"
            }

            is SolvablePipeline.ExactTheorySatisfaction -> pipeline.pipelineName

            is SolvablePipeline.Interrupted -> "interrupted"

            is SolvablePipeline.OpenLocalSearch -> "open-local-search"

            SolvablePipeline.Refuted -> "refuted"
        },
        when (pipeline) {
            is SolvablePipeline.NativeSource -> when {
                SourceSetup.executionKind(pipeline.invocation) == SourceExecutionKind.FUNCTIONS -> "transcendental"

                pipeline.invocation.operation == ExecutionOperation.OPTIMIZE -> "quadratic_optimization"

                SourceSetup.executionKind(
                    pipeline.invocation,
                ) == SourceExecutionKind.NONLINEAR_INTEGER -> "incomplete_nia"

                else -> "exact_nra"
            }

            is SolvablePipeline.OpenTheory -> pipeline.request.route.name.lowercase()

            is SolvablePipeline.PolynomialTheory -> "exact_nra"

            is SolvablePipeline.NonlinearIntegerTheory -> "incomplete_nia"

            is SolvablePipeline.QuadraticTheory -> "quadratic_optimization"

            is SolvablePipeline.ExactTheorySatisfaction -> pipeline.declaredTheoryRoute

            else -> null
        },
    )

private fun nativeCpStatPairs(search: SearchStats): List<Pair<String, String>> {
    // Default search snapshots contain zeros without any native fixpoint measurement.
    val active = search.propagationWork.sum > 0 || search.rootPropagationWork.sum > 0 ||
        search.propagationNanos.sum > 0 || search.rootPropagationNanos.sum > 0
    if (!active) return emptyList()
    return listOf(
        "nativeCpPropagationWork" to search.propagationWork.sum.toLong().toString(),
        "nativeCpRootPropagationWork" to search.rootPropagationWork.sum.toLong().toString(),
        "nativeCpPropagationMs" to (search.propagationNanos.sum.toLong() / NANOS_PER_MS).toString(),
        "nativeCpRootPropagationMs" to (search.rootPropagationNanos.sum.toLong() / NANOS_PER_MS).toString(),
    )
}

private const val NANOS_PER_MS = 1_000_000L

private fun SolvablePipeline.NativeSource.nativePipelineName(): String {
    val kind = SourceSetup.executionKind(invocation)
    if (invocation.operation == ExecutionOperation.OPTIMIZE && kind != SourceExecutionKind.FUNCTIONS) {
        return if (kind == SourceExecutionKind.NONLINEAR_INTEGER) "quadratic-integer" else "quadratic-polynomial"
    }
    return when (kind) {
        SourceExecutionKind.NONLINEAR_INTEGER -> "nonlinear-integer-theory"
        SourceExecutionKind.FUNCTIONS -> "transcendental-theory"
        else -> "polynomial-theory"
    }
}
