package com.eignex.klause.solver.pipeline

import kotlin.time.TimeSource

internal fun prepareSourceExecution(request: SourceExecutionRequest): SourceExecutionResult.Prepared {
    val invocation = request.invocation
    val route = invocation.engineRoute as SourceProblemRoute.OpenTheory
    val original = route.request
    val stop = invocation.cancellation or request.theory.cancellation or request.theory.timeout
    val preparation = original.withPresolve(
        request.presolveConfig ?: original.presolveConfig,
        cancellation = original.presolveCancellation or request.presolveCancellation or stop,
        budget = request.presolveBudget ?: original.presolveBudget,
    )
    val mark = TimeSource.Monotonic.markNow()
    val result = OpenTheoryPipeline.prepare(preparation)
    return SourceExecutionResult.Prepared(invocation, original.model, result, mark.elapsedNow())
}
