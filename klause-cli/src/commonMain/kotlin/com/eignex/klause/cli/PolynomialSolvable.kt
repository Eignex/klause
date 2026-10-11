package com.eignex.klause.cli

import com.eignex.klause.solver.pipeline.EngineParams
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryRequest
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation

internal fun polynomialSolvable(
    request: PolynomialTheoryRequest,
    render: (OpenTheoryAssignment) -> String,
    admissionElapsedMs: Long,
): Solvable = sourceSolvable(SolvablePipeline.PolynomialTheory(request, render), admissionElapsedMs)

internal fun interruptedSolvable(reason: TerminationReason, admissionElapsedMs: Long): Solvable =
    sourceSolvable(SolvablePipeline.Interrupted(reason), admissionElapsedMs)

private fun sourceSolvable(pipeline: SolvablePipeline, admissionElapsedMs: Long): Solvable = Solvable(
    finite = null,
    render = { error("an open source witness is rendered without narrowing to Sample") },
    objectiveValue = null,
    pipeline = pipeline,
    routingElapsedMs = admissionElapsedMs,
)

internal fun polynomialParams(common: CommonOptions, timeout: Cancellation): PolynomialTheoryParams {
    val work = polynomialAllowance(common, "nra-work-limit", default = null)
    val dense = polynomialAllowance(common, "nra-dense-coefficients", default = 65536)
    if (dense != null && dense !in 1..Int.MAX_VALUE.toLong()) {
        usageError("engine param `nra-dense-coefficients` expects a positive Int or unlimited")
    }
    val matrix = polynomialAllowance(common, "nra-matrix-cells", default = 262144)
    EngineParams(common.engineParams).finish(
        "polynomial-real",
        "nra-work-limit, nra-dense-coefficients, nra-matrix-cells",
    )
    return PolynomialTheoryParams(work, dense?.toInt(), matrix, timeout = timeout)
}

private fun polynomialAllowance(common: CommonOptions, key: String, default: Long?): Long? =
    when (val setting = takeAllowanceSetting(common.engineParams, key)) {
        AllowanceSetting.Automatic -> default
        AllowanceSetting.Unlimited -> null
        is AllowanceSetting.Remaining -> setting.units
    }
