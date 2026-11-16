package com.eignex.klause.compile

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.QuadraticOptimizationExecution
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.TerminationReason

internal fun CompiledSourceBinding.bindResult(
    execution: SourceExecutionResult.Quadratic,
    stopped: () -> TerminationReason,
): QuadraticOptimizationExecution {
    require(execution.invocation.source.originalSource === originalProblem)
    val stop = execution.invocation.cancellation
    val native = execution.result
    fun interrupted(): QuadraticOptimizationExecution = native.copy(result = QuadraticOptimizationResult.Unknown(
        null, stopped(), native.result.sense, native.result.stats,
    ))
    if (stop()) return interrupted()
    fun sample(point: Sample): Sample {
        stop.charge(point.numBoolVars.toLong() + point.numIntVars + point.numRealVars)
        require(point.coordinateFrame == null || point.coordinateFrame === originalProblem) {
            "checked source incumbent retains a foreign coordinate frame"
        }
        require(point.numBoolVars == originalProblem.numBoolVars && point.numIntVars == originalProblem.numIntVars &&
            point.numRealVars == originalProblem.numRealVars) { "checked source incumbent has incomplete coordinates" }
        return point.withCoordinateFrame(originalProblem)
    }
    fun incumbent(point: QuadraticIncumbent): QuadraticIncumbent = point.copy(sample = sample(point.sample))
    val result = when (val original = native.result) {
        is QuadraticOptimizationResult.Optimal -> original.copy(incumbent = incumbent(original.incumbent))
        is QuadraticOptimizationResult.UnattainedLimit -> original.copy(incumbent = incumbent(original.incumbent))
        is QuadraticOptimizationResult.Unbounded -> original.copy(incumbent = incumbent(original.incumbent))
        is QuadraticOptimizationResult.BestFound -> original.copy(incumbent = incumbent(original.incumbent))
        is QuadraticOptimizationResult.Infeasible, is QuadraticOptimizationResult.Unknown -> original
    }
    val publication = native.acceptedIncumbent?.let { it.copy(incumbent = incumbent(it.incumbent)) }
    if (stop()) return interrupted()
    return native.copy(result = result).also { it.retainPublication(publication) }
}
