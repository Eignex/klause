package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.util.Cancellation

internal class SourceExecutionControls(
    val theory: TheoryParams,
    val polynomial: PolynomialTheoryParams,
    val nonlinearInteger: NonlinearIntegerTheoryParams?,
    val transcendental: TranscendentalTheoryParams?,
    val finiteRequest: (FiniteSolveShape) -> FiniteSolveRequest,
    val nodeBudget: NodeBudget?,
)

internal fun bindSourceExecutionControls(
    invocation: PreparedSourceInvocation,
    options: ExecutionOptions,
    finiteRequest: ((FiniteSolveShape) -> FiniteSolveRequest)?,
    randomSeed: Long?,
    nodeBudget: NodeBudget?,
    cores: Int,
    timeout: Cancellation,
    cancellation: Cancellation,
    presolveConfig: PresolveConfig?,
    presolveCancellation: Cancellation,
    presolveBudget: PresolveBudget?,
): SourceExecutionControls {
    val reader = options.reader()
    val limit = reader.defaulted(ExecutionOption.nodeLimit, nodeBudget?.limit)
    if (nodeBudget != null && limit != nodeBudget.limit) {
        pipelineConfigError("node-limit conflicts with the containing node allowance")
    }
    val budget = nodeBudget ?: limit?.let(::NodeBudget)
    val seed = reader[ExecutionOption.seed] ?: randomSeed ?: 0L
    val kind = SourceSetup.executionKind(invocation)
    val stop = cancellation or timeout or invocation.cancellation
    val finiteOptions = options.without("node-limit").overriddenBy(ExecutionOptions(ExecutionOption.seed(seed)))
    val finite: (FiniteSolveShape) -> FiniteSolveRequest = { shape ->
        val supplied = finiteRequest?.invoke(shape)
        if (supplied == null) {
            FiniteSolveRequest(
                shape, invocation.request.engine, presolveConfig ?: PresolveConfig.DEFAULT,
                presolveConfig != null, invocation.operation == ExecutionOperation.ENUMERATE,
                cancellation = stop, presolveCancellation = presolveCancellation or stop,
                presolveBudget = presolveBudget, cores = cores, executionOptions = finiteOptions,
                randomSeed = seed, defaultArms = autoArms(cores),
                lpConfig = if (invocation.request.engine == FiniteEngine.FIXED) LpConfig.OFF else LpConfig.AGGRESSIVE,
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = budget,
                solveBudgetMillis = null, allSolutions = invocation.operation == ExecutionOperation.ENUMERATE,
                solutionCap = null, deadlineExceeded = { timeout.isCancelled() },
                onEvent = null, onPortfolioEvent = null,
            )
        } else {
            require(supplied.cores == cores) { "finite factory supplied a different lane count" }
            supplied.withSourceControls(finiteOptions, budget, seed, stop)
        }
    }
    if (kind == SourceExecutionKind.FINITE || kind == SourceExecutionKind.REFUTED) {
        options.validateKnown()
        return SourceExecutionControls(
            TheoryParams(nodeBudget = budget, randomSeed = seed, timeout = timeout, cancellation = cancellation),
            PolynomialTheoryParams(), null, null, finite, budget,
        )
    }
    val decisions = reader[ExecutionOption.maxDecisions] ?: BacktrackParams().maxDecisions
    val flips = reader[ExecutionOption.maxFlips] ?: LocalSearchParams().maxFlips
    val instructions = reader[ExecutionOption.maxInstructions]
    val iterations = reader[ExecutionOption.alnsIterations] ?: PolynomialStrategyParams().alnsIterations
    if (decisions < 0 || flips < 0 || instructions?.let { it < 0 } == true || iterations < 0) {
        pipelineConfigError("source strategy limits must be non-negative")
    }
    val strategy = PolynomialStrategyParams(
        backtrack = BacktrackParams(
            randomSeed = seed, maxDecisions = decisions, maxInstructions = instructions, nodeBudget = budget,
        ),
        localSearch = LocalSearchParams(
            randomSeed = seed, maxFlips = flips, maxInstructions = instructions, nodeBudget = budget,
        ),
        alnsIterations = iterations,
    )
    val linear = kind in setOf(SourceExecutionKind.LINEAR, SourceExecutionKind.HEURISTIC, SourceExecutionKind.NUMERICAL)
    val theory = if (linear) {
        val defaults = TheoryParams()
        TheoryParams(
            openWorkLimit = reader.defaulted(ExecutionOption.openWorkLimit, defaults.openWorkLimit) ?: Long.MAX_VALUE,
            maxDecisions = decisions,
            sharedRestart = reader[ExecutionOption.sharedRestart],
            maxLearnedClauses = reader[ExecutionOption.maxLearned],
            lbdGlue = reader[ExecutionOption.lbdGlue] ?: defaults.lbdGlue,
            openHintFlips = reader[ExecutionOption.openHintFlips],
            openBranching = reader[ExecutionOption.openBranching] ?: defaults.openBranching,
            openHintMinSplits = reader[ExecutionOption.openHintMinSplits] ?: defaults.openHintMinSplits,
            timeout = timeout, cancellation = cancellation, nodeBudget = budget,
            alnsIterations = iterations, randomSeed = seed, maxFlips = flips, maxInstructions = instructions,
        )
    } else TheoryParams(nodeBudget = budget, randomSeed = seed, timeout = timeout, cancellation = cancellation)
    val functionDefaults = TranscendentalTheoryParams()
    val polynomial = if (linear) PolynomialTheoryParams(strategy = strategy) else {
        val defaults = PolynomialTheoryParams()
        val denseDefault = if (kind == SourceExecutionKind.FUNCTIONS) functionDefaults.maximumDenseCoefficients
            else defaults.maximumDenseCoefficients
        val dense = reader.defaulted(ExecutionOption.nraDenseCoefficients, denseDefault?.toLong())
        if (dense != null && dense !in 1..Int.MAX_VALUE.toLong()) {
            pipelineConfigError("nra-dense-coefficients expects a positive Int or unlimited")
        }
        PolynomialTheoryParams(
            maximumWork = reader.defaulted(ExecutionOption.nraWorkLimit,
                if (kind == SourceExecutionKind.FUNCTIONS) functionDefaults.maximumWork else defaults.maximumWork),
            maximumDenseCoefficients = dense?.toInt(),
            maximumMatrixCells = reader.defaulted(ExecutionOption.nraMatrixCells,
                if (kind == SourceExecutionKind.FUNCTIONS) functionDefaults.maximumMatrixCells
                else defaults.maximumMatrixCells),
            timeout = timeout, cancellation = cancellation, strategy = strategy,
        )
    }
    val integer = if (kind == SourceExecutionKind.NONLINEAR_INTEGER) {
        val defaults = NonlinearIntegerTheoryParams()
        NonlinearIntegerTheoryParams(
            polynomial,
            reader.defaulted(ExecutionOption.niaCandidates, defaults.maximumCandidates) ?: Long.MAX_VALUE,
            reader.defaulted(ExecutionOption.niaMoves, defaults.maximumMoves) ?: Long.MAX_VALUE,
            budget,
        )
    } else null
    val functions = if (kind == SourceExecutionKind.FUNCTIONS) functionDefaults.copy(
        maximumWork = polynomial.maximumWork, maximumDenseCoefficients = polynomial.maximumDenseCoefficients,
        maximumMatrixCells = polynomial.maximumMatrixCells, timeout = timeout, cancellation = cancellation,
        strategy = strategy, nodeBudget = budget,
    ) else null
    reader.finish("source ${kind.name.lowercase()}", "common strategy controls and the selected family's native limits")
    return SourceExecutionControls(theory, polynomial, integer, functions, finite, budget)
}

private fun FiniteSolveRequest.withSourceControls(
    options: ExecutionOptions,
    budget: NodeBudget?,
    seed: Long,
    stop: Cancellation,
): FiniteSolveRequest {
    require(sourceControls == null) { "native finite controls must use the typed source execution constructor" }
    require(nodeBudget == null || nodeBudget === budget) { "finite factory supplied a different node allowance" }
    require(executionOptions.reader().defaulted(ExecutionOption.nodeLimit, budget?.limit) == budget?.limit) {
        "finite factory supplied a conflicting node-limit"
    }
    return FiniteSolveRequest(
        shape, engine, presolveConfig, explicitPresolveConfig, solutionSetSensitive,
        cancellation = cancellation or stop, presolveCancellation = presolveCancellation or stop,
        presolveBudget = presolveBudget, cores = cores,
        executionOptions = executionOptions.without("node-limit").overriddenBy(options),
        randomSeed = seed, defaultArms = defaultArms, lpConfig = lpConfig, zeroObjectivePricing = zeroObjectivePricing,
        nodeBudget = budget, solveBudgetMillis = solveBudgetMillis, allSolutions = allSolutions,
        solutionCap = solutionCap,
        deadlineExceeded = deadlineExceeded, onEvent = onEvent, onPortfolioEvent = onPortfolioEvent,
        onPortfolioFault = onPortfolioFault, prepareOnly = prepareOnly, toleranceCheck = toleranceCheck,
    )
}
