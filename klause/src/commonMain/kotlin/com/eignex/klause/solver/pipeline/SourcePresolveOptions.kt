package com.eignex.klause.solver.pipeline

import com.eignex.klause.presolve.PresolveConfig

internal class SourcePresolveOptions(val config: PresolveConfig, val requested: Boolean)

internal fun ExecutionOptionReader.presolve(base: PresolveConfig): SourcePresolveOptions {
    val pivot = this[ExecutionOption.affinePivotOrder]
    val preserve = this[ExecutionOption.presolvePreserveStructure]
    val impact = this[ExecutionOption.presolveStructuralImpact]
    val fraction = this[ExecutionOption.presolveAbortFraction]
    val rounds = this[ExecutionOption.presolveMaxRounds]
    val perVariable = this[ExecutionOption.presolveProbePerVar]
    val total = this[ExecutionOption.presolveProbeTotal]
    if (fraction != null && (!fraction.isFinite() || fraction !in 0.0..1.0)) {
        pipelineConfigError("presolve-abort-fraction expects a fraction in [0,1]")
    }
    for ((key, value) in listOf(
        "presolve-structural-impact" to impact,
        "presolve-max-rounds" to rounds,
        "presolve-probe-per-var" to perVariable,
        "presolve-probe-total" to total,
    )) {
        if (value != null && value < 0) pipelineConfigError("$key expects a nonnegative integer")
    }
    val requested = pivot != null || fraction != null || rounds != null || perVariable != null || total != null ||
        preserve != null || impact != null
    if (!requested) return SourcePresolveOptions(base, false)
    val ordered = pivot?.let(base::withAffinePivotOrder) ?: base
    return SourcePresolveOptions(
        ordered.withEffort(
            preserveStructure = preserve ?: ordered.preserveStructure,
            structuralImpactLimit = impact ?: ordered.structuralImpactLimit,
            abortFraction = fraction ?: ordered.abortFraction,
            maxRounds = rounds ?: ordered.maxRounds,
            probeBudgetPerVar = perVariable ?: ordered.probeBudgetPerVar(),
            probeTotalBudget = total ?: ordered.probeTotalBudget(),
        ),
        true,
    )
}

internal fun ExecutionOptions.withoutPresolve(): ExecutionOptions = without(
    "affine-pivot-order",
    "presolve-preserve-structure",
    "presolve-structural-impact",
    "presolve-abort-fraction",
    "presolve-max-rounds",
    "presolve-probe-per-var",
    "presolve-probe-total",
)
