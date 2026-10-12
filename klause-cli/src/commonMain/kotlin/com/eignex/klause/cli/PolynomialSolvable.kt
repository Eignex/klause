package com.eignex.klause.cli

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.EngineParams
import com.eignex.klause.solver.pipeline.ExactTheorySatisfactionRequest
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryOptimum
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.PolynomialStrategyParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryRequest
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.qfnra.ExactNraAssignment
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import com.eignex.klause.util.times

internal fun polynomialSolvable(
    request: PolynomialTheoryRequest,
    render: (OpenTheoryAssignment) -> String,
    admissionElapsedMs: Long,
): Solvable = exactTheorySolvable(ExactTheorySatisfactionRequest { params, engine, _ ->
    OpenTheoryPipeline.execute(request, params, engine)
}, render, admissionElapsedMs)

internal fun polynomialSolvable(
    route: SourceProblemRoute.PolynomialTheory,
    render: (OpenTheoryAssignment) -> String,
    routingElapsedMs: Long = 0,
): Solvable = sourceSolvable(
    if (route.objective == null) SolvablePipeline.ExactTheorySatisfaction(
        ExactTheorySatisfactionRequest { params, engine, _ ->
            OpenTheoryPipeline.execute(route.request, params, engine)
        }, render,
    ) else SolvablePipeline.PolynomialTheory(route.request, render, route.objective, route.maximize), routingElapsedMs,
)

internal fun exactTheorySolvable(
    request: ExactTheorySatisfactionRequest,
    render: (OpenTheoryAssignment) -> String,
    routingElapsedMs: Long = 0,
): Solvable = sourceSolvable(SolvablePipeline.ExactTheorySatisfaction(request, render), routingElapsedMs)

internal fun interruptedSolvable(reason: TerminationReason, admissionElapsedMs: Long): Solvable =
    sourceSolvable(SolvablePipeline.Interrupted(reason), admissionElapsedMs)

internal fun polynomialOptimum(result: MinimizeResult, objective: LinearObjective): OpenTheoryOptimum {
    fun witness(sample: Sample): OpenTheoryAssignment = OpenTheoryAssignment.Rebuilt(
        OpenTheoryAssignment.ExactNra(ExactNraAssignment(sample.bools, requireNotNull(sample.algebraicReals))),
        sample.bools, Array(sample.numIntVars) { sample.exactIntValue(it) },
    )
    fun value(sample: Sample): BigFraction {
        var sum = bigIntOf(objective.constant)
        objective.boolWeights.forEachIndexed { id, weight -> if (sample.boolValue(id)) sum += bigIntOf(weight) }
        objective.intCoefficients.forEachIndexed { id, coefficient ->
            sum += bigIntOf(coefficient) * sample.exactIntValue(id)
        }
        return BigFraction.of(sum, BIG_ONE)
    }
    return when (result) {
        is MinimizeResult.Optimal -> OpenTheoryOptimum.Optimal(
            witness(result.sample), value(result.sample), result.stats,
        )
        is MinimizeResult.BestFound -> OpenTheoryOptimum.Bounded(
            witness(result.sample), value(result.sample), result.reason, result.stats,
        )
        is MinimizeResult.Infeasible -> OpenTheoryOptimum.Infeasible(result.stats)
        is MinimizeResult.Unknown -> OpenTheoryOptimum.Bounded(null, null, result.reason, result.stats)
        is MinimizeResult.Unbounded -> OpenTheoryOptimum.Bounded(
            witness(result.sample), value(result.sample), TerminationReason.Unsupported, result.stats,
        )
    }
}

private fun sourceSolvable(pipeline: SolvablePipeline, admissionElapsedMs: Long): Solvable = Solvable(
    finite = null,
    render = { error("an open source witness is rendered without narrowing to Sample") },
    objectiveValue = null,
    pipeline = pipeline,
    routingElapsedMs = admissionElapsedMs,
)

internal fun polynomialParams(common: CommonOptions, timeout: Cancellation): PolynomialTheoryParams {
    val nodeBudget = common.nodeBudget ?: when (val setting = takeAllowanceSetting(common.engineParams, "node-limit")) {
        AllowanceSetting.Automatic, AllowanceSetting.Unlimited -> null
        is AllowanceSetting.Remaining -> NodeBudget(setting.units)
    }.also { common.nodeBudget = it }
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
            backtrack = BacktrackParams(
                randomSeed = seed, maxDecisions = decisions, maxInstructions = instructions, nodeBudget = nodeBudget,
            ),
            localSearch = LocalSearchParams(
                randomSeed = seed, maxFlips = flips, maxInstructions = instructions, nodeBudget = nodeBudget,
            ),
            alnsIterations = iterations,
        ))
}

private fun polynomialAllowance(common: CommonOptions, key: String, default: Long?): Long? =
    when (val setting = takeAllowanceSetting(common.engineParams, key)) {
        AllowanceSetting.Automatic -> default
        AllowanceSetting.Unlimited -> null
        is AllowanceSetting.Remaining -> setting.units
    }
