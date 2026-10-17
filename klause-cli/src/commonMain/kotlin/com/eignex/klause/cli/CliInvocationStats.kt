package com.eignex.klause.cli

import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceProblemRoute
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

internal fun cliInvocationStats(common: CommonOptions, rendering: SourceRendering): CliInvocationStats {
    val kind = (rendering as? SourceRendering.ExactSource)?.let { SourceSetup.executionKind(it.invocation) }
    return CliInvocationStats(
        common.loadElapsedMs,
        common.frontendRouting,
        when (rendering) {
            is SourceRendering.LinearSource -> "linear-source"

            is SourceRendering.ExactSource -> rendering.nativePipelineName(requireNotNull(kind))

            SourceRendering.FiniteCp -> "finite-cp"

            is SourceRendering.Interrupted -> "interrupted"
        },
        when (rendering) {
            is SourceRendering.ExactSource -> when {
                kind == SourceExecutionKind.LINEAR ->
                    (rendering.invocation.route as SourceProblemRoute.OpenTheory)
                        .request.route.name.lowercase()

                kind in setOf(
                    SourceExecutionKind.HEURISTIC, SourceExecutionKind.REFUTED, SourceExecutionKind.NUMERICAL,
                ) -> null

                kind == SourceExecutionKind.FUNCTIONS -> "transcendental"

                rendering.invocation.operation == ExecutionOperation.OPTIMIZE -> "quadratic_optimization"

                kind == SourceExecutionKind.NONLINEAR_INTEGER -> "incomplete_nia"

                else -> "exact_nra"
            }

            else -> null
        },
    )
}

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

private fun SourceRendering.ExactSource.nativePipelineName(kind: SourceExecutionKind): String {
    if (kind == SourceExecutionKind.LINEAR) return "open-theory"
    if (kind == SourceExecutionKind.HEURISTIC) return "open-local-search"
    if (kind == SourceExecutionKind.REFUTED) return "refuted"
    if (kind == SourceExecutionKind.NUMERICAL) return "numerical-mip"
    if (invocation.operation == ExecutionOperation.OPTIMIZE && kind != SourceExecutionKind.FUNCTIONS) {
        return if (kind == SourceExecutionKind.NONLINEAR_INTEGER) "quadratic-integer" else "quadratic-polynomial"
    }
    return when (kind) {
        SourceExecutionKind.NONLINEAR_INTEGER -> "nonlinear-integer-theory"
        SourceExecutionKind.FUNCTIONS -> "transcendental-theory"
        else -> "polynomial-theory"
    }
}
