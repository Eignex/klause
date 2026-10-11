package com.eignex.klause.cli

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.solver.pipeline.EngineParams
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryRequest
import com.eignex.klause.solver.pipeline.PolynomialStrategyParams
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
    val knobs = EngineParams(common.engineParams)
    val seed = knobs.long("seed") ?: common.randomSeed
    val decisions = knobs.long("max-decisions") ?: Long.MAX_VALUE
    val flips = knobs.long("max-flips") ?: Long.MAX_VALUE
    val instructions = knobs.long("max-instructions")
    val iterations = knobs.int("alns-iterations") ?: 50
    if (decisions < 0 || flips < 0 || instructions?.let { it < 0 } == true || iterations < 0) {
        usageError("polynomial strategy limits must be nonnegative")
    }
    knobs.finish(
        "polynomial-real",
        "nra-work-limit, nra-dense-coefficients, nra-matrix-cells, seed, max-decisions, max-flips, " +
            "max-instructions, alns-iterations",
    )
    return PolynomialTheoryParams(work, dense?.toInt(), matrix, timeout = timeout,
        strategy = PolynomialStrategyParams(
            backtrack = BacktrackParams(randomSeed = seed, maxDecisions = decisions, maxInstructions = instructions),
            localSearch = LocalSearchParams(randomSeed = seed, maxFlips = flips, maxInstructions = instructions),
            alnsIterations = iterations,
        ))
}

private fun polynomialAllowance(common: CommonOptions, key: String, default: Long?): Long? =
    when (val setting = takeAllowanceSetting(common.engineParams, key)) {
        AllowanceSetting.Automatic -> default
        AllowanceSetting.Unlimited -> null
        is AllowanceSetting.Remaining -> setting.units
    }
