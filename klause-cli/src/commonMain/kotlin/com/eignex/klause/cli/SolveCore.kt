package com.eignex.klause.cli

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.portfolio.ArmFault
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.ExecutionOptions
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FiniteSolveCallbacks
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.FiniteSolveRequest
import com.eignex.klause.solver.pipeline.FiniteSolveShape
import com.eignex.klause.solver.pipeline.FiniteSolveVerdict
import com.eignex.klause.solver.pipeline.NODE_LIMIT_KEY
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryOptimum
import com.eignex.klause.solver.pipeline.SourceExecutionCallbacks
import com.eignex.klause.solver.pipeline.SourceExecutionPolicy
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.solve
import com.eignex.klause.solver.pipeline.variablePartition
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.process.printErrorLine
import com.eignex.util.process.processProperty
import com.eignex.util.runtime.diagnostics.sampleHeap
import kotlin.time.Duration

// Mode-agnostic solve driver: translates frontend flags, deadlines and source annotations into pipeline
// requests and renders their results through the mode's OutputProtocol.
internal object SolveCore {

    fun solve(rawSolvable: Solvable, common: CommonOptions, protocol: OutputProtocol) {
        val invocationStats = cliInvocationStats(common, rawSolvable.rendering)
        val output = object : OutputProtocol by protocol {
            override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) {
                protocol.onAllowanceStatistics(common.debugStats?.pairs().orEmpty())
                protocol.onAllowanceStatistics(nodeBudgetStatPairs(common.nodeBudget))
                protocol.onAllowanceStatistics(invocationStats.pairs(stats))
                protocol.onStatistics(stats, solveTimeMs, solutions)
            }
        }
        val publication = ArithmeticPublication(output)
        val engine = common.resolvedEngine()
        // Presolve runs once, before any worker is built, so every worker shares the transformed problem.
        // The pipeline drops solution-set-altering passes for a pure-LS engine unless `--presolve` is explicit.
        val policy = executionPolicy(common, common.presolve != null)
        val config = common.presolve?.let { PresolveConfig.parse(it) } ?: common.config.presolveConfig()
        // Symmetry breaking collapses symmetric solutions, so it is unsound when a satisfaction run
        // enumerates (`-a`) or caps several solutions (`-n N`).
        val solutionSetSensitive = !rawSolvable.optimize && (common.allSolutions || (common.solutionCap ?: 1L) > 1L)
        // One invocation deadline covers presolve, LP preparation and search. It must stop the driver, not
        // only the engine, or the driver keeps re-entering arms that cancel at their first poll.
        val (deadline, deadlineCancel) = deadlineCancellation(common)
        val (presolveCancel, presolveBudget) = presolveAllowance(common, deadlineCancel)
        // `-p N` gives the open portfolio a lane per core, as it does the finite one.
        val openCores = common.parallel ?: 1
        val sourceControls = rawSolvable.sourceInvocation?.let { invocation ->
            SourceExecutionRequest(
                invocation, policy, ExecutionOptions.parse(common.engineParams),
                finiteRequest = { shape ->
                    finiteRequest(
                        shape, common, engine, openCores, config, solutionSetSensitive,
                        presolveCancel, presolveBudget, deadlineCancel, deadline, common.nodeBudget, policy,
                        rawSolvable.numericalCompletion, rawSolvable.toleranceCheck,
                    )
                },
                cores = openCores, randomSeed = common.randomSeed, nodeBudget = common.nodeBudget,
                timeout = deadlineCancel, presolveConfig = config,
                presolveCancellation = presolveCancel, presolveBudget = presolveBudget,
            ).also { common.nodeBudget = it.nodeBudget }
        }
        when (val pipeline = rawSolvable.rendering) {
            is SourceRendering.LinearSource -> {
                solveLinearSource(rawSolvable, pipeline, requireNotNull(sourceControls), common, output, publication)
                return
            }

            is SourceRendering.ExactSource -> {
                solveCapturedSource(
                    rawSolvable, pipeline, common, output, publication, requireNotNull(sourceControls),
                ) { budgetSpent(common, it) }
                return
            }

            is SourceRendering.Interrupted -> {
                output.begin(optimize = false, maximize = false)
                output.onVerdictContext(VerdictContext(terminationReason = pipeline.reason))
                output.onComplete(Verdict.UNKNOWN)
                if (common.statistics) output.onStatistics(SolveStats.EMPTY, rawSolvable.routingElapsedMs, 0L)
                return
            }

            SourceRendering.FiniteCp -> {
                if (sourceControls != null) {
                    executeFinite(
                        requireNotNull(sourceControls.finiteRequest).invoke(rawSolvable.finiteShape),
                        rawSolvable, common, output,
                    )
                    return
                }
            }
        }
        val nodeBudget = takeNodeBudget(common).also { common.nodeBudget = it }
        val cancel = nodeBudget?.let { deadlineCancel or ExecutionControl { it.exhausted() } } ?: deadlineCancel
        // `-p N` is the core count. Portfolio engines run sequentially at `-p1` and as a parallel pool
        // otherwise; `fixed` is single-core.
        val cores = common.parallel ?: 1
        when (engine) {
            // A source search annotation decides the heuristic and selector params are rejected; an
            // unannotated model has no heuristic to preserve, so its run accepts them.
            FiniteEngine.FIXED -> {
                runBacktrack(
                    rawSolvable, common, output, config, solutionSetSensitive, presolveCancel, presolveBudget,
                    cancel, deadline, nodeBudget, policy,
                )
            }

            // A single resolved arm runs as a one-arm pool.
            FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.MIXED, FiniteEngine.ALNS ->
                runPortfolio(
                    rawSolvable, common, output, cores, engine, config, solutionSetSensitive, presolveCancel,
                    presolveBudget, cancel, nodeBudget, policy,
                )
        }
    }

    // Some backends leave backendTimedOut false, so the shared deadline also identifies timeouts.
    private fun budgetSpent(common: CommonOptions, backendTimedOut: Boolean): Boolean =
        backendTimedOut || common.deadlineAtMs?.let { nowMillis() >= it } == true

    // Every arm spends the same invocation allowance; engines must not consume `node-limit` again.
    internal fun takeNodeBudget(common: CommonOptions): NodeBudget? = takeOpenNodeLimit(common)?.let(::NodeBudget)

    private fun takeOpenNodeLimit(common: CommonOptions): Long? =
        when (val setting = takeAllowanceSetting(common.engineParams, NODE_LIMIT_KEY)) {
            AllowanceSetting.Automatic, AllowanceSetting.Unlimited -> null
            is AllowanceSetting.Remaining -> setting.units
        }

    private fun deadlineCancellation(common: CommonOptions): Pair<Long?, ExecutionControl> {
        // Anchored once at process start so the bake and the solve share one budget.
        val deadline = common.deadlineAtMs ?: common.timeLimitMs?.let { nowMillis() + it }
        if (common.deadlineAtMs == null) common.deadlineAtMs = deadline
        val cancel = common.invocationCancellation()
        return deadline to cancel
    }

    // The floor lets a short run still buy a pass worth entering; the ceiling keeps that floor from taking
    // the whole run.
    internal fun derivedPresolveBudgetMs(timeLimitMs: Long?, fraction: Double): Long {
        if (timeLimitMs == null) return CliKnobs.DEFAULT_PRESOLVE_BUDGET_MS
        val share = (timeLimitMs * fraction).toLong()
        val ceiling = (timeLimitMs * CliKnobs.MAX_PRESOLVE_BUDGET_SHARE).toLong()
        return minOf(share.coerceAtLeast(CliKnobs.MIN_PRESOLVE_BUDGET_MS), ceiling)
    }

    // The elapsed ceiling reserves time for search when work underprices preparation.
    internal fun presolveAllowance(
        common: CommonOptions,
        solveCancel: ExecutionControl,
    ): Pair<ExecutionControl, PresolveBudget?> {
        val budget = common.sharedPresolveBudget()
        val phaseStop = common.presolveTimeCancellation or solveCancel
        return (budget?.orSpent(phaseStop) ?: phaseStop) to budget
    }

    private fun runBacktrack(
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        presolveConfig: PresolveConfig,
        solutionSetSensitive: Boolean,
        presolveCancellation: ExecutionControl,
        presolveBudget: PresolveBudget?,
        cancel: ExecutionControl,
        deadline: Long?,
        nodeBudget: NodeBudget?,
        policy: SourceExecutionPolicy,
    ) {
        executeFinite(
            finiteRequest(
                solvable.finiteShape, common, FiniteEngine.FIXED, common.parallel ?: 1, presolveConfig,
                solutionSetSensitive, presolveCancellation, presolveBudget, cancel, deadline, nodeBudget,
                policy, solvable.numericalCompletion, solvable.toleranceCheck,
            ),
            solvable,
            common,
            output,
        )
    }

    private fun presolveWorkText(budget: PresolveBudget?, before: Long, elapsed: Duration): String = budget?.let {
        ", work ${it.spent() - before} in ${elapsed.inWholeMilliseconds}ms (${it.spent()} of ${it.allowance})"
    }.orEmpty()

    internal fun printPresolved(
        original: Problem,
        presolved: Problem,
        stats: PresolveStats?,
        elapsed: Duration,
        loadElapsedMs: Long?,
        constructionBakeElapsed: Duration,
        emit: (String) -> Unit = ::printErrorLine,
    ) {
        val passes = stats?.passes.orEmpty()
        emit("presolve dry-run:")
        printPresolveDiagnostics(stats, emit)
        // A large gap between peak and retained heap points at a structure materialized and dropped.
        sampleHeap()?.let { heap ->
            val peak = heap.peakBytes?.let { "peak: ${it / MIB}MiB, " }.orEmpty()
            emit(
                "  heap retained: ${heap.retainedBytes / MIB}MiB, " +
                    "$peak" +
                    "committed: ${heap.committedBytes / MIB}MiB",
            )
        }
        // The bake ran either in the pipeline (stats.bakeElapsed) or while constructing the source problem;
        // only one is non-zero.
        val stepZeroBake = stats?.bakeElapsed ?: Duration.ZERO
        val bake = constructionBakeElapsed + stepZeroBake
        loadElapsedMs?.let { load ->
            emit("  parse: ${(load - constructionBakeElapsed.inWholeMilliseconds).coerceAtLeast(0)}ms")
            emit("  bake (step 0): $bake")
        }
        // A deferred bake is part of the presolve phase's elapsed time.
        emit("  elapsed: ${elapsed - stepZeroBake}")
        emit("  passes fired: ${if (passes.isEmpty()) "(none)" else passes.joinToString(", ")}")
        emit("  bool vars: ${presolved.numBoolVars}, int vars: ${presolved.numIntVars}")
        emit("  factors: ${original.factors.size} -> ${presolved.factors.size}")
        emit("  int-domain span: ${spanText(original)} -> ${spanText(presolved)}")
        emit("  open columns: ${openText(original)} -> ${openText(presolved)}")
        emit("  theory-eligible columns: ${theoryText(original)} -> ${theoryText(presolved)}")
        val before = factorHistogram(original)
        val after = factorHistogram(presolved)
        for (kind in (before.keys + after.keys).sorted()) {
            val b = before[kind] ?: 0
            val a = after[kind] ?: 0
            emit("  $kind: $b -> $a")
        }
        stats?.lpHarvest?.let { lp ->
            val dims = "relaxation ${lp.relaxationCols} cols / ${lp.relaxationRows} rows / ${lp.relaxationNnz} nnz"
            if (lp.skipped) {
                emit("  lp-harvest: skipped ($dims over the size budget)")
            } else {
                val parts = buildList {
                    if (lp.rootInfeasible) add("root-infeasible")
                    if (lp.boundsShaved > 0) add("shaved ${lp.boundsShaved} bound(s)")
                    if (lp.objectiveLbRaised) add("raised objective lb")
                    if (lp.constraintsRemoved > 0) add("removed ${lp.constraintsRemoved} redundant constraint(s)")
                    if (lp.equalitiesAdded > 0) add("added ${lp.equalitiesAdded} implied equality(ies)")
                }
                emit("  lp-harvest: ${parts.joinToString(", ")} ($dims)")
            }
        }
        // Asking the problem for its own `baked` would force a fresh root fixpoint on a rebuilt problem.
        if (stats?.infeasible == true) {
            emit("  INFEASIBLE: presolve proved the problem unsatisfiable")
        }
    }

    // Saturates at Long.MAX_VALUE on overflow or any open side.
    private fun domainSpan(problem: Problem): Long {
        val bounds = problem.intBounds
        var span = 0L
        for (v in 0 until problem.numIntVars) {
            if (bounds.isOpen(v)) return Long.MAX_VALUE
            // The declared value set is what the search enumerates; a column stating bounds alone has only the hull.
            val declared = problem.intDomainOrNull(v)
            val width = if (declared != null) {
                declared.max - declared.min
            } else {
                bounds.upper(v) - bounds.lower(v)
            }
            if (width < 0L) return Long.MAX_VALUE
            span += width
            if (span < 0L) return Long.MAX_VALUE
        }
        return span
    }

    // A column is open when either source bound is absent, regardless of any finite search box.
    private fun openText(problem: Problem): String {
        val bounds = problem.intBounds
        var open = 0
        for (v in 0 until problem.numIntVars) {
            if (bounds.isOpen(v)) open++
        }
        return "$open of ${problem.numIntVars}"
    }

    private fun IntBounds.isOpen(v: Int): Boolean = isOpenLower(v) || isOpenUpper(v)

    // Eligible is not decidable: an all-`Linear` model reads as fully eligible yet its integers still need
    // search. The actionable figure is the eligible columns with an open source side.
    private fun theoryText(problem: Problem): String {
        val partition = problem.variablePartition()
        var openAndEligible = 0
        val bounds = problem.intBounds
        for (v in 0 until partition.size) {
            if (!partition.isTheoryEligible(v)) continue
            if (bounds.isOpen(v)) openAndEligible++
        }
        return "${partition.theoryEligibleCount} of ${partition.size}, $openAndEligible of them open"
    }

    private fun spanText(problem: Problem): String =
        domainSpan(problem).let { if (it == Long.MAX_VALUE) "unbounded" else it.toString() }

    private fun factorHistogram(problem: Problem): Map<String, Int> =
        problem.factors.groupingBy { it::class.simpleName ?: "?" }.eachCount()

    private fun runPortfolio(
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        cores: Int,
        engine: FiniteEngine,
        presolveConfig: PresolveConfig,
        solutionSetSensitive: Boolean,
        presolveCancellation: ExecutionControl,
        presolveBudget: PresolveBudget?,
        cancel: ExecutionControl,
        nodeBudget: NodeBudget?,
        policy: SourceExecutionPolicy,
    ) {
        executeFinite(
            finiteRequest(
                solvable.finiteShape, common, engine, cores, presolveConfig,
                solutionSetSensitive, presolveCancellation, presolveBudget, cancel, null, nodeBudget,
                policy, solvable.numericalCompletion, solvable.toleranceCheck,
            ),
            solvable,
            common,
            output,
        )
    }

    private fun executionPolicy(common: CommonOptions, explicitPresolve: Boolean): SourceExecutionPolicy {
        fun ceiling(value: String?): LpConfig? = value?.let {
            runCatching { LpConfig.parse(it) }.getOrElse { failure -> usageError("--lp: ${failure.message}") }
        }
        val arms = processProperty(CliKnobs.portfolioArms)?.let { value ->
            value.toIntOrNull()?.takeIf { it > 0 }
                ?: usageError("portfolio arm default expects a positive integer")
        }
        return SourceExecutionPolicy(
            ceiling(common.lp), ceiling(defaultLp()), arms,
            common.lpPricing.takeIf { common.lpPricingExplicit }, explicitPresolve,
        )
    }

    private fun finiteRequest(
        shape: FiniteSolveShape,
        common: CommonOptions,
        engine: FiniteEngine,
        cores: Int,
        presolveConfig: PresolveConfig,
        solutionSetSensitive: Boolean,
        presolveCancellation: ExecutionControl,
        presolveBudget: PresolveBudget?,
        cancel: ExecutionControl,
        deadline: Long?,
        nodeBudget: NodeBudget?,
        policy: SourceExecutionPolicy,
        numericalCompletion: Boolean = false,
        toleranceCheck: ((Sample) -> Boolean)? = null,
    ): FiniteSolveRequest {
        val fixed = engine == FiniteEngine.FIXED
        return policy.finiteRequest(
            shape = shape,
            engine = engine,
            presolveConfig = presolveConfig,
            solutionSetSensitive = solutionSetSensitive,
            presolveBudget = presolveBudget,
            cores = cores,
            executionOptions = ExecutionOptions.parse(common.engineParams).let {
                if (numericalCompletion) it.withNumericalCompletion() else it
            },
            randomSeed = common.randomSeed,
            cancellation = cancel,
            presolveCancellation = presolveCancellation,
            nodeBudget = nodeBudget,
            solveBudgetMillis = common.timeLimitMs,
            allSolutions = common.allSolutions,
            solutionCap = common.solutionCap,
            deadlineExceeded = { fixed && deadline != null && nowMillis() > deadline },
            onEvent = if (fixed) verboseListener(common.verbose, common.diagnostic) else null,
            onPortfolioEvent = if (fixed) null else portfolioVerboseListener(common.verbose, common.diagnostic),
            onPortfolioFault = if (fixed) null else { fault -> warnPortfolioFault(fault, common.diagnostic) },
            toleranceCheck = toleranceCheck,
        )
    }

    private fun executeFinite(
        request: FiniteSolveRequest,
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
    ) {
        val workBefore = request.presolveBudget?.spent() ?: 0L
        val callbacks = FiniteSolveCallbacks(
            onSample = { sample -> emit(output, solvable, sample) },
            onImprovement = { improvement ->
                val (objective, continuousObjective) = emit(output, solvable, improvement.sample)
                if (common.statistics) {
                    output.onImprovement(
                        improvement.workerLabel,
                        objective ?: improvement.objective.toLong(),
                        continuousObjective,
                        improvement.elapsedMs,
                    )
                }
            },
        )
        val invocation = request.shape.sourceInvocation
        val result = if (invocation == null) {
            FinitePipeline.solve(request, callbacks)
        } else {
            (
                SourceSetup.execute(
                    SourceExecutionRequest(invocation, finiteRequest = { shape ->
                        require(shape === request.shape) { "finite CLI request belongs to another source invocation" }
                        request
                    }),
                    SourceExecutionCallbacks(finite = callbacks),
                ) as SourceExecutionResult.Finite
                ).result
        }
        val preparation = result.preparation
        cliLogger(common.verbose, common.diagnostic).v {
            val p0 = solvable.finiteProblem
            val p1 = preparation.problem
            "presolve [${request.engine.id}]: factors ${p0.numFactors}→${p1.numFactors}, " +
                "ints ${p0.numIntVars}→${p1.numIntVars}, bools ${p0.numBoolVars}→${p1.numBoolVars}" +
                presolveWorkText(request.presolveBudget, workBefore, result.preparationElapsed)
        }
        when (val outcome = result.outcome) {
            FiniteSolveOutcome.PreparedOnly -> {
                printPresolved(
                    solvable.finiteProblem,
                    preparation.problem,
                    preparation.presolve,
                    result.preparationElapsed,
                    common.loadElapsedMs,
                    preparation.constructionBakeElapsed,
                    common.diagnostic,
                )
                return
            }

            is FiniteSolveOutcome.DryRun -> {
                output.begin(solvable.optimize, solvable.maximize)
                common.diagnostic(outcome.heading)
                outcome.lines.forEach(common.diagnostic)
            }

            is FiniteSolveOutcome.Completed -> {
                output.begin(solvable.optimize, solvable.maximize)
                output.onVerdictContext(
                    VerdictContext(
                        budgetExhausted = budgetSpent(common, outcome.stats.run.timedOut),
                        terminationReason = outcome.terminationReason,
                        completePool = result.completeSearchAdmitted,
                    ),
                )
                output.onComplete(outcome.verdict.toCliVerdict())
                stats(
                    common,
                    output,
                    withModelObjective(
                        outcome.stats.copy(
                            presolve = (outcome.stats.presolve ?: PresolveStats()).copy(
                                effort = presolveEffortStats(
                                    request.presolveConfig,
                                    request.presolveBudget,
                                    result.preparationElapsed,
                                ),
                            ),
                        ),
                        solvable,
                        outcome.bestSample,
                    ),
                    outcome.elapsedMs + solvable.routingElapsedMs,
                    outcome.solutions,
                )
            }
        }
    }

    private fun FiniteSolveVerdict.toCliVerdict(): Verdict = when (this) {
        FiniteSolveVerdict.SAT -> Verdict.SATISFIABLE
        FiniteSolveVerdict.UNSAT -> Verdict.UNSATISFIABLE
        FiniteSolveVerdict.UNKNOWN -> Verdict.UNKNOWN
        FiniteSolveVerdict.OPTIMAL -> Verdict.OPTIMAL
        FiniteSolveVerdict.BEST_FOUND -> Verdict.BEST_FOUND
        FiniteSolveVerdict.UNBOUNDED -> Verdict.UNBOUNDED
    }

    // Returns the objective pair so callers such as arm attribution need not re-evaluate it.
    private fun emit(output: OutputProtocol, solvable: Solvable, sample: Sample): Pair<Long?, Double?> {
        val objective = solvable.objectiveValue?.invoke(sample)
        val continuousObjective = solvable.continuousObjectiveValue?.invoke(sample)
        output.onSolution(solvable.render(sample), objective, continuousObjective)
        return objective to continuousObjective
    }

    private fun stats(common: CommonOptions, output: OutputProtocol, s: SolveStats, ms: Long, solutions: Long) {
        if (common.statistics) output.onStatistics(s, ms, solutions)
    }

    // The engine records the LS incumbent in its minimizing frame; the sign-corrected objectiveValue
    // re-expresses it in the model's orientation.
    private fun withModelObjective(s: SolveStats, solvable: Solvable, sample: Sample?): SolveStats {
        val routed = s.copy(lp = s.lp.mergedWith(solvable.routingLpStats))
        if (sample == null || s.ls.incumbentObjective.isNaN()) return routed
        // The statistic is a Double, so it reports the continuous contribution where there is one
        // rather than the discrete part alone.
        solvable.continuousObjectiveValue?.let { exact ->
            return routed.copy(ls = routed.ls.copy(incumbentObjective = exact(sample)))
        }
        val objectiveValue = solvable.objectiveValue ?: return routed
        return routed.copy(ls = routed.ls.copy(incumbentObjective = objectiveValue(sample).toDouble()))
    }
}

internal fun describeEvent(e: SearchEvent, t: Long, worker: String? = null): String {
    val who = worker?.let { " $it" }.orEmpty()
    return when (e) {
        is SearchEvent.BacktrackIncomplete ->
            "[$t ms]$who incomplete optimization: path=${e.path} stop=${e.stopCause}"
        is SearchEvent.BacktrackComponentStop -> e.stop.let {
            "[$t ms]$who component terminal: origin=${it.site} firstStop=${it.stopCause} cancelled=${it.cancelled}" +
                " unresolved=${it.unresolvedCoverage} level=${it.decisionLevel} replayed=${e.replayed}"
        }
        is SearchEvent.Restart -> "[$t ms]$who restart #${e.index} after ${e.steps} decisions"
        is SearchEvent.LearnedDbSweep -> "[$t ms]$who learned-DB sweep: kept ${e.kept}, dropped ${e.dropped}"
        is SearchEvent.Incumbent -> "[$t ms]$who incumbent objective ${e.objective}"
        is SearchEvent.WorkerAdmission -> "admission ${e.recipe} admitted=${e.admitted} " +
            "selected=${e.selected}${e.role?.let { role -> " role=$role" }.orEmpty()} " +
            "${e.requirements} declines=${e.declines}"
        is SearchEvent.PortfolioDiagnostic -> "portfolio ${e.event}"
        is SearchEvent.CompanionComparison -> "companion $e"
    }
}

// Null when not verbose so the engines skip observation.
internal fun verboseListener(verbose: Boolean, emit: (String) -> Unit = ::printErrorLine): ((SearchEvent) -> Unit)? {
    if (!verbose) return null
    val log = cliLogger(verbose = true, emit = emit)
    val start = nowMillis()
    return { e -> log.v { describeEvent(e, nowMillis() - start) } }
}

// Printed whatever the verbosity: an arm claiming a result the model refutes is a configuration bug the
// run survives but nobody should miss.
internal fun warnPortfolioFault(fault: ArmFault, emit: (String) -> Unit = ::printErrorLine) {
    emit("% WARNING: portfolio arm ${fault.workerLabel} quarantined: ${fault.reason}")
}

// Workers run concurrently; the logger writes whole lines, which is the only shared state.
internal fun portfolioVerboseListener(
    verbose: Boolean, emit: (String) -> Unit = ::printErrorLine,
): ((String, SearchEvent) -> Unit)? {
    if (!verbose) return null
    val log = cliLogger(verbose = true, emit = emit)
    val start = nowMillis()
    return { worker, e -> log.v { describeEvent(e, nowMillis() - start, worker) } }
}

// Heap figures are reported in MiB, the unit `-Xmx` is set in.
private const val MIB = 1024L * 1024L

internal fun reportOpenTheoryOptimum(
    execution: OpenTheoryExecution.Optimize,
    maximize: Boolean,
    render: (OpenTheoryAssignment) -> String,
    statistics: Boolean,
    output: OutputProtocol,
    routingLpStats: LpStats,
    routingElapsedMs: Long,
    completePool: Boolean? = null,
    publication: ArithmeticPublication,
    budgetExhausted: (Boolean) -> Boolean,
) {
    val result = execution.result
    val receipt = execution.acceptedIncumbent
    result.linearResult?.let { linear ->
        reportLinearOptimization(
            linear, { render(OpenTheoryAssignment.Sampled(it)) }, output, publication,
            statistics, routingElapsedMs, completePool, routingLpStats, budgetExhausted, receipt,
        )
        return
    }
    val resultStats = result.stats.copy(lp = result.stats.lp.mergedWith(routingLpStats))

    fun solution(assignment: OpenTheoryAssignment?, value: BigFraction?): Boolean {
        receipt?.let {
            return publication.publish(render(OpenTheoryAssignment.Sampled(it.sample)), it.value, it.version)
        }
        val signed = if (maximize) value?.negated() else value
        return if (assignment != null && signed != null) {
            publication.publish(render(assignment), SourceValue.Rational(signed))
        } else {
            false
        }
    }

    fun complete(verdict: Verdict, accepted: Boolean) {
        output.onComplete(
            if (accepted) {
                verdict
            } else if (publication.solutions > 0L) {
                Verdict.BEST_FOUND
            } else {
                Verdict.UNKNOWN
            },
        )
    }
    output.onVerdictContext(
        VerdictContext(
            budgetExhausted = budgetExhausted(resultStats.run.timedOut),
            terminationReason = (result as? OpenTheoryOptimum.Bounded)?.reason,
            completePool = completePool,
        ),
    )
    when (result) {
        is OpenTheoryOptimum.Optimal -> {
            complete(Verdict.OPTIMAL, solution(result.assignment, result.value))
        }

        is OpenTheoryOptimum.Infeasible -> output.onComplete(Verdict.UNSATISFIABLE)

        // The witness is reported like any other solution: it is one, and the verdict beside it says the
        // objective reaches past it without limit rather than that this is the best value found.
        is OpenTheoryOptimum.Unbounded -> {
            complete(Verdict.UNBOUNDED, solution(result.witness, result.value))
        }

        is OpenTheoryOptimum.Bounded -> {
            val incumbent = result.incumbent
            if (incumbent == null) {
                if (receipt == null) {
                    output.onComplete(Verdict.UNKNOWN)
                } else {
                    complete(Verdict.BEST_FOUND, solution(null, null))
                }
            } else {
                complete(Verdict.BEST_FOUND, solution(incumbent, result.value))
            }
        }
    }
    if (statistics) {
        output.onStatistics(resultStats, resultStats.run.wallMs + routingElapsedMs, publication.solutions)
    }
}
