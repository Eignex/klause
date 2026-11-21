package com.eignex.klause.solver.pipeline

internal class AutomaticLinearCuts(
    val point: Boolean = false,
    val aggregate: Boolean = false,
    val centerStep: Boolean = false,
)

internal class FiniteCompanionPolicy(
    val params: TheoryParams,
    val automatic: AutomaticLinearCuts = AutomaticLinearCuts(),
) {
    fun resolve(authority: LinearToleranceAuthority?): TheoryParams = if (authority != null) params else params.copy(
        sourcePointCuts = if (automatic.point) false else params.sourcePointCuts,
        sourceAggregateCuts = if (automatic.aggregate) false else params.sourceAggregateCuts,
        sourceRefutationCenterStep = if (automatic.centerStep) false else params.sourceRefutationCenterStep,
    )
}

private val automaticSourceTheory = TheoryParams(sourcePointCuts = true, sourceAggregateCuts = true,
    sourceRefutationCenterStep = true)

internal fun defaultSourceTheory(invocation: PreparedSourceInvocation): TheoryParams =
    if (invocation.linearSource?.automaticCutSelection == true) automaticSourceTheory else TheoryParams()

internal fun automaticSourceCuts(theory: TheoryParams): AutomaticLinearCuts =
    AutomaticLinearCuts(theory === automaticSourceTheory, theory === automaticSourceTheory,
        theory === automaticSourceTheory)

internal fun retainedSourceTheory(theory: TheoryParams): TheoryParams =
    if (theory === automaticSourceTheory) theory.copy() else theory
