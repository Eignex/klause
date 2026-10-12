package com.eignex.klause.cli

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
            SolvablePipeline.FiniteCp -> "finite-cp"
            is SolvablePipeline.OpenTheory -> "open-theory"
            is SolvablePipeline.PolynomialTheory -> "polynomial-theory"
            is SolvablePipeline.NonlinearIntegerTheory -> "nonlinear-integer-theory"
            is SolvablePipeline.ExactTheorySatisfaction -> pipeline.pipelineName
            is SolvablePipeline.Interrupted -> "interrupted"
            is SolvablePipeline.OpenLocalSearch -> "open-local-search"
            SolvablePipeline.Refuted -> "refuted"
        },
        when (pipeline) {
            is SolvablePipeline.OpenTheory -> pipeline.request.route.name.lowercase()
            is SolvablePipeline.PolynomialTheory -> "exact_nra"
            is SolvablePipeline.NonlinearIntegerTheory -> "incomplete_nia"
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
