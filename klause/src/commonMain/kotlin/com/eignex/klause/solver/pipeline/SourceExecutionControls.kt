package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.LocalSearchParams
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
    val presolveConfig: PresolveConfig?,
    val prepareOnly: Boolean,
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
    policy: SourceExecutionPolicy,
): SourceExecutionControls {
    val reader = options.reader()
    val limit = reader.defaulted(ExecutionOption.nodeLimit, nodeBudget?.limit)
    if (nodeBudget != null && limit != nodeBudget.limit) {
        pipelineConfigError("node-limit conflicts with the containing node allowance")
    }
    val budget = nodeBudget ?: limit?.let(::NodeBudget)
    val seed = reader[ExecutionOption.seed] ?: randomSeed ?: 0L
    val kind = SourceSetup.executionKind(invocation)
    val preparation = reader.presolve(presolveConfig ?: PresolveConfig.DEFAULT)
    val prepareOnly = reader[ExecutionOption.dryRunPresolve] == true
    policy.validate(kind, invocation.request.engine, preparation.requested, prepareOnly, preparation.config,
        invocation.canonicalPolynomial)
    val resolvedPresolve = preparation.config.takeIf { presolveConfig != null || preparation.requested }
    val stop = cancellation or timeout or invocation.cancellation
    val companions = invocation.requestsFiniteNumericalCompanions
    val finiteOptions = options.withoutPresolve().without("node-limit", "dry-run-presolve").let {
        if (companions) it.without("source-lp-work-limit", "source-lp-allocation-limit", "source-lp-operations",
            "source-refutation-reuse", "source-refutation-propagation", "source-refutation-resolution") else it
    }
        .overriddenBy(ExecutionOptions(ExecutionOption.seed(seed)))
    val finite: (FiniteSolveShape) -> FiniteSolveRequest = { shape ->
        val supplied = finiteRequest?.invoke(shape)
        if (supplied == null) {
            policy.finiteRequest(
                shape, invocation.request.engine, resolvedPresolve ?: PresolveConfig.DEFAULT,
                invocation.operation == ExecutionOperation.ENUMERATE,
                cancellation = stop, presolveCancellation = presolveCancellation or stop,
                presolveBudget = presolveBudget, cores = cores, executionOptions = finiteOptions,
                randomSeed = seed, nodeBudget = budget,
                solveBudgetMillis = null, allSolutions = invocation.operation == ExecutionOperation.ENUMERATE,
                solutionCap = null, deadlineExceeded = { timeout.isCancelled() },
                onEvent = null, onPortfolioEvent = null, prepareOnly = prepareOnly,
            )
        } else {
            require(supplied.cores == cores) { "finite factory supplied a different lane count" }
            supplied.withSourceControls(finiteOptions, budget, seed, stop, policy, resolvedPresolve, prepareOnly,
                numericalCompanions = companions)
        }
    }
    if (kind == SourceExecutionKind.FINITE || kind == SourceExecutionKind.REFUTED) {
        options.validateKnown()
        val defaults = TheoryParams(nodeBudget = budget, randomSeed = seed, timeout = timeout,
            cancellation = cancellation)
        val theory = if (companions) defaults.copy(
            maxDecisions = reader[ExecutionOption.maxDecisions] ?: defaults.maxDecisions,
            maxFlips = reader[ExecutionOption.maxFlips] ?: defaults.maxFlips,
            maxInstructions = reader[ExecutionOption.maxInstructions],
            sourceLpWorkLimit = reader.defaulted(ExecutionOption.sourceLpWorkLimit,
                defaults.sourceLpWorkLimit) ?: Long.MAX_VALUE,
            sourceLpAllocationLimit = reader.defaulted(ExecutionOption.sourceLpAllocationLimit,
                defaults.sourceLpAllocationLimit) ?: Long.MAX_VALUE,
            sourceLpOperations = reader[ExecutionOption.sourceLpOperations] ?: defaults.sourceLpOperations,
            sourceRefutationReuse = reader[ExecutionOption.sourceRefutationReuse] ?: defaults.sourceRefutationReuse,
            sourceRefutationPropagation = reader[ExecutionOption.sourceRefutationPropagation]
                ?: defaults.sourceRefutationPropagation,
            sourceRefutationResolution = reader[ExecutionOption.sourceRefutationResolution]
                ?: defaults.sourceRefutationResolution,
        ) else defaults
        return SourceExecutionControls(
            theory,
            PolynomialTheoryParams(),
            null,
            null,
            finite,
            budget,
            resolvedPresolve,
            prepareOnly,
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
            randomSeed = seed,
            maxDecisions = decisions,
            maxInstructions = instructions,
            nodeBudget = budget,
        ),
        localSearch = LocalSearchParams(
            randomSeed = seed,
            maxFlips = flips,
            maxInstructions = instructions,
            nodeBudget = budget,
        ),
        alnsIterations = iterations,
    )
    val linear = kind in setOf(SourceExecutionKind.LINEAR, SourceExecutionKind.HEURISTIC, SourceExecutionKind.NUMERICAL)
    val theory = if (linear) {
        val defaults = TheoryParams()
        TheoryParams(
            openWorkLimit = reader.defaulted(ExecutionOption.openWorkLimit, defaults.openWorkLimit) ?: Long.MAX_VALUE,
            sourceLpWorkLimit = reader.defaulted(ExecutionOption.sourceLpWorkLimit, defaults.sourceLpWorkLimit)
                ?: Long.MAX_VALUE,
            sourceLpAllocationLimit = reader.defaulted(ExecutionOption.sourceLpAllocationLimit,
                defaults.sourceLpAllocationLimit) ?: Long.MAX_VALUE,
            sourceLpOperations = reader[ExecutionOption.sourceLpOperations] ?: defaults.sourceLpOperations,
            sourceCuts = reader[ExecutionOption.sourceCuts] ?: defaults.sourceCuts,
            sourcePointCuts = reader[ExecutionOption.sourcePointCuts] ?: defaults.sourcePointCuts,
            sourceAggregateCuts = reader[ExecutionOption.sourceAggregateCuts] ?: defaults.sourceAggregateCuts,
            sourceRefutationReuse = reader[ExecutionOption.sourceRefutationReuse] ?: defaults.sourceRefutationReuse,
            sourceRefutationPropagation = reader[ExecutionOption.sourceRefutationPropagation]
                ?: defaults.sourceRefutationPropagation,
            sourceRefutationResolution = reader[ExecutionOption.sourceRefutationResolution]
                ?: defaults.sourceRefutationResolution,
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
    } else {
        TheoryParams(nodeBudget = budget, randomSeed = seed, timeout = timeout, cancellation = cancellation)
    }
    val functionDefaults = TranscendentalTheoryParams()
    val polynomial = if (linear) {
        PolynomialTheoryParams(strategy = strategy)
    } else {
        val defaults = PolynomialTheoryParams()
        val denseDefault = if (kind == SourceExecutionKind.FUNCTIONS) {
            functionDefaults.maximumDenseCoefficients
        } else {
            defaults.maximumDenseCoefficients
        }
        val dense = reader.defaulted(ExecutionOption.nraDenseCoefficients, denseDefault?.toLong())
        if (dense != null && dense !in 1..Int.MAX_VALUE.toLong()) {
            pipelineConfigError("nra-dense-coefficients expects a positive Int or unlimited")
        }
        PolynomialTheoryParams(
            maximumWork = reader.defaulted(
                ExecutionOption.nraWorkLimit,
                if (kind == SourceExecutionKind.FUNCTIONS) functionDefaults.maximumWork else defaults.maximumWork,
            ),
            maximumDenseCoefficients = dense?.toInt(),
            maximumMatrixCells = reader.defaulted(
                ExecutionOption.nraMatrixCells,
                if (kind == SourceExecutionKind.FUNCTIONS) {
                    functionDefaults.maximumMatrixCells
                } else {
                    defaults.maximumMatrixCells
                },
            ),
            timeout = timeout,
            cancellation = cancellation,
            strategy = strategy,
        )
    }
    val integer = if (kind == SourceExecutionKind.NONLINEAR_INTEGER) {
        val defaults = NonlinearIntegerTheoryParams()
        NonlinearIntegerTheoryParams(
            polynomial,
            reader.defaulted(ExecutionOption.niaCandidates, defaults.maximumCandidates) ?: Long.MAX_VALUE,
            reader.defaulted(ExecutionOption.niaMoves, defaults.maximumMoves) ?: Long.MAX_VALUE,
            budget,
            reader.defaulted(ExecutionOption.niaCellRefinement, defaults.cellRefinement),
            reader.defaulted(ExecutionOption.niaCellReuse, defaults.reuseSourceCells),
            reader.defaulted(ExecutionOption.niaCellSteering, defaults.steerSourceCells),
            reader.defaulted(ExecutionOption.niaIntegerRoots, defaults.admitIntegerRoots),
            reader.defaulted(ExecutionOption.niaResidues, defaults.residueRefutation),
        )
    } else {
        null
    }
    val functions = if (kind == SourceExecutionKind.FUNCTIONS) {
        functionDefaults.copy(
            maximumWork = polynomial.maximumWork,
            maximumDenseCoefficients = polynomial.maximumDenseCoefficients,
            maximumMatrixCells = polynomial.maximumMatrixCells,
            timeout = timeout,
            cancellation = cancellation,
            strategy = strategy,
            nodeBudget = budget,
        )
    } else {
        null
    }
    reader.finish("source ${kind.name.lowercase()}", "common strategy controls and the selected family's native limits")
    return SourceExecutionControls(
        theory, polynomial, integer, functions, finite, budget, resolvedPresolve, prepareOnly,
    )
}

private fun FiniteSolveRequest.withSourceControls(
    options: ExecutionOptions,
    budget: NodeBudget?,
    seed: Long,
    stop: Cancellation,
    policy: SourceExecutionPolicy,
    resolvedPresolve: PresolveConfig?,
    preparationOnly: Boolean,
    numericalCompanions: Boolean,
): FiniteSolveRequest {
    require(sourceControls == null) { "native finite controls must use the typed source execution constructor" }
    require(nodeBudget == null || nodeBudget === budget) { "finite factory supplied a different node allowance" }
    require(executionOptions.reader().defaulted(ExecutionOption.nodeLimit, budget?.limit) == budget?.limit) {
        "finite factory supplied a conflicting node-limit"
    }
    return FiniteSolveRequest(
        shape, engine, resolvedPresolve ?: presolveConfig,
        explicitPresolveConfig || policy.explicitPresolve, solutionSetSensitive,
        cancellation = cancellation or stop, presolveCancellation = presolveCancellation or stop,
        presolveBudget = presolveBudget, cores = cores,
        executionOptions = policy.options(
            engine,
            executionOptions.withoutPresolve().without("node-limit", "dry-run-presolve").let {
                if (numericalCompanions) it.without("source-lp-work-limit", "source-lp-allocation-limit",
                    "source-lp-operations") else it
            }.overriddenBy(options),
        ),
        randomSeed = seed, defaultArms = policy.arms(engine, cores, defaultArms),
        lpConfig = policy.lpConfig(engine, lpConfig),
        zeroObjectivePricing = policy.zeroObjectivePricing ?: zeroObjectivePricing,
        nodeBudget = budget, solveBudgetMillis = solveBudgetMillis, allSolutions = allSolutions,
        solutionCap = solutionCap,
        deadlineExceeded = deadlineExceeded, onEvent = onEvent, onPortfolioEvent = onPortfolioEvent,
        onPortfolioFault = onPortfolioFault, prepareOnly = prepareOnly || preparationOnly,
        toleranceCheck = toleranceCheck,
    )
}
