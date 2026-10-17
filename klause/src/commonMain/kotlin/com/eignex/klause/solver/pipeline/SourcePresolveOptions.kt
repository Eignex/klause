package com.eignex.klause.solver.pipeline

import com.eignex.klause.presolve.PresolveConfig

internal class SourcePresolveOptions(val config: PresolveConfig, val requested: Boolean)

internal fun ExecutionOptionReader.presolve(base: PresolveConfig): SourcePresolveOptions {
    val pivot = this[ExecutionOption.affinePivotOrder]
    val fraction = this[ExecutionOption.presolveAbortFraction]
    val rounds = this[ExecutionOption.presolveMaxRounds]
    val perVariable = this[ExecutionOption.presolveProbePerVar]
    val total = this[ExecutionOption.presolveProbeTotal]
    if (fraction != null && (!fraction.isFinite() || fraction !in 0.0..1.0)) {
        pipelineConfigError("presolve-abort-fraction expects a fraction in [0,1]")
    }
    for ((key, value) in listOf(
        "presolve-max-rounds" to rounds, "presolve-probe-per-var" to perVariable, "presolve-probe-total" to total,
    )) {
        if (value != null && value < 0) pipelineConfigError("$key expects a nonnegative integer")
    }
    val requested = pivot != null || fraction != null || rounds != null || perVariable != null || total != null
    if (!requested) return SourcePresolveOptions(base, false)
    val ordered = pivot?.let(base::withAffinePivotOrder) ?: base
    return SourcePresolveOptions(ordered.withEffort(
        abortFraction = fraction ?: ordered.abortFraction,
        maxRounds = rounds ?: ordered.maxRounds,
        probeBudgetPerVar = perVariable ?: ordered.probeBudgetPerVar(),
        probeTotalBudget = total ?: ordered.probeTotalBudget(),
    ), true)
}

internal fun ExecutionOptions.withoutPresolve(): ExecutionOptions = without(
    "affine-pivot-order", "presolve-abort-fraction", "presolve-max-rounds", "presolve-probe-per-var", "presolve-probe-total",
)
