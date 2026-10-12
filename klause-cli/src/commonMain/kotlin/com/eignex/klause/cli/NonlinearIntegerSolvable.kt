package com.eignex.klause.cli

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.solver.pipeline.NonlinearIntegerTheoryParams
import com.eignex.klause.solver.pipeline.NonlinearIntegerTheoryRequest
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.util.Cancellation

internal fun nonlinearIntegerSolvable(
    request: NonlinearIntegerTheoryRequest,
    render: (OpenTheoryAssignment) -> String,
    admissionElapsedMs: Long,
): Solvable = Solvable(
    finite = null,
    render = { error("an exact nonlinear source witness uses its original coordinate mapping") },
    objectiveValue = null,
    pipeline = SolvablePipeline.NonlinearIntegerTheory(request, render),
    routingElapsedMs = admissionElapsedMs,
)

internal fun nonlinearIntegerParams(
    common: CommonOptions,
    timeout: Cancellation,
    nodeBudget: NodeBudget?,
): NonlinearIntegerTheoryParams {
    val candidates = nonlinearAllowance(common, "nia-candidates", 1024)
    val moves = nonlinearAllowance(common, "nia-moves", 256)
    return NonlinearIntegerTheoryParams(polynomialParams(common, timeout), candidates, moves, nodeBudget)
}

private fun nonlinearAllowance(common: CommonOptions, key: String, default: Long): Long =
    when (val setting = takeAllowanceSetting(common.engineParams, key)) {
        AllowanceSetting.Automatic -> default
        AllowanceSetting.Unlimited -> Long.MAX_VALUE
        is AllowanceSetting.Remaining -> setting.units
    }
