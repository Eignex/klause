package com.eignex.klause.cli

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.portfolio.ArmFault
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.simplex.exact.BigFraction
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
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The unified, mode-agnostic solve driver. Every CLI mode (MiniZinc, XCSP3, SMT-LIB) feeds a
 * [Solvable] + [CommonOptions] in, and all terminal reporting goes out through the mode's
 * [OutputProtocol]. It translates frontend flags, deadlines, and source annotations into pipeline
 * requests, then renders their streamed models and terminal results.
 */
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
        // Engine resolution: explicit `-e` wins, then `-f` (free) ≡ `-e cp`, else the configured
        // default ([defaultEngine] — the built-in [FiniteEngine.DEFAULT] or a packaged image's env override).
        val engine = common.resolvedEngine()
        // Presolve once, before any worker is built, so every engine and portfolio worker shares
        // the one transformed problem. The pipeline drops solution-set-altering passes (symmetry
        // breaking, value precedence) for a pure-LS engine unless `--presolve` is explicit;
        // solutions are reconstructed at render time.
        val policy = executionPolicy(common, common.presolve != null)
        val config = common.presolve?.let { PresolveConfig.parse(it) } ?: common.config.presolveConfig()
        // Symmetry breaking collapses symmetric solutions, so disable it (via auto resolution) when
        // the run wants the full solution set: enumeration (`-a`) or a multi-solution cap (`-n N`),
        // unless we're optimizing (a single optimum, where symmetry breaking is sound).
        val solutionSetSensitive = !rawSolvable.optimize && (common.allSolutions || (common.solutionCap ?: 1L) > 1L)
        // One invocation deadline covers presolve, LP preparation and search.
        // Taken before the token is built, because the allowance has to stop the driver and not only the
        // engine: an engine-local check leaves the driver re-entering arms that cancel at their first
        // poll, which burns the whole deadline and overshoots the cap several times over.
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
        val cancel = nodeBudget?.let { deadlineCancel or Cancellation { it.exhausted() } } ?: deadlineCancel
        // `-p N` is MiniZinc-standard parallelism = the **core** count. The portfolio engines
        // (cp/mixed/ls) run sequentially at `-p1` and as a parallel pool at `-pN`. The one naked
        // engine (fixed) is inherently single-core.
        val cores = common.parallel ?: 1
        when (engine) {
            // Naked single backtrack (FD track). A source search annotation, when present, decides
            // the heuristic and per-solver selector --params are rejected; an unannotated model has
            // no heuristic to preserve, so its one run accepts them (see runBacktrack).
            FiniteEngine.FIXED -> {
                runBacktrack(
                    rawSolvable, common, output, config, solutionSetSensitive, presolveCancel, presolveBudget,
                    cancel, deadline, nodeBudget, policy,
                )
            }

            // The parallel-capable portfolio engines: their mix is carried on the enum. `ls` resolves
            // a four-axis arm pool from its --params (a `strategy=` base plus per-axis edits); `cp`
            // resolves a per-solver override pool from its --params (var-/val-selector, luby, …). A
            // single resolved arm runs as a one-arm pool.
            FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.MIXED, FiniteEngine.ALNS ->
                runPortfolio(
                    rawSolvable, common, output, cores, engine, config, solutionSetSensitive, presolveCancel,
                    presolveBudget, cancel, nodeBudget, policy,
                )
        }
    }

    /**
     * Whether the invocation deadline has expired when rendering an undecided verdict.
     * Some backends leave [backendTimedOut] false, so the shared deadline also identifies
     * timeouts. Decided verdicts are unaffected.
     */
    private fun budgetSpent(common: CommonOptions, backendTimedOut: Boolean): Boolean =
        backendTimedOut || common.deadlineAtMs?.let { nowMillis() >= it } == true

    /**
     * Consumes `node-limit` as a shared [NodeBudget] and removes it from [CommonOptions.engineParams].
     * Every arm spends the same invocation allowance; engines must not consume the key again.
     */
    internal fun takeNodeBudget(common: CommonOptions): NodeBudget? = takeOpenNodeLimit(common)?.let(::NodeBudget)

    /** Consume the route-local fixed-work limit for an open-theory solve. */
    private fun takeOpenNodeLimit(common: CommonOptions): Long? =
        when (val setting = takeAllowanceSetting(common.engineParams, NODE_LIMIT_KEY)) {
            AllowanceSetting.Automatic, AllowanceSetting.Unlimited -> null
            is AllowanceSetting.Remaining -> setting.units
        }

    private fun deadlineCancellation(common: CommonOptions): Pair<Long?, Cancellation> {
        // Anchored once at process start (see CommonOptions.deadlineAtMs) so the bake and the
        // solve share one budget; fall back to a fresh anchor if it was never set.
        val deadline = common.deadlineAtMs ?: common.timeLimitMs?.let { nowMillis() + it }
        // Deadline-backed (Monotonic) rather than a bare `nowMillis()` predicate, so budget-relative tokens
        // like [Cancellation.shorten] can be computed from it downstream (the ALNS bootstrap). Fires at the
        // same absolute instant as the epoch deadline.
        val cancel = if (deadline != null) {
            Cancellation.after((deadline - nowMillis()).coerceAtLeast(0L).milliseconds)
        } else {
            Cancellation.Never
        }
        return deadline to cancel
    }

    /**
     * Presolve's slice of a [timeLimitMs] run at [fraction], bounded below by
     * [CliKnobs.MIN_PRESOLVE_BUDGET_MS] so a short run still buys a pass worth entering, and above by
     * [CliKnobs.MAX_PRESOLVE_BUDGET_SHARE] so that floor can never take the whole run. A run with no
     * `-t` has no total to take a share of, so it falls back to the flat backstop.
     */
    internal fun derivedPresolveBudgetMs(timeLimitMs: Long?, fraction: Double): Long {
        if (timeLimitMs == null) return CliKnobs.DEFAULT_PRESOLVE_BUDGET_MS
        val share = (timeLimitMs * fraction).toLong()
        val ceiling = (timeLimitMs * CliKnobs.MAX_PRESOLVE_BUDGET_SHARE).toLong()
        return minOf(share.coerceAtLeast(CliKnobs.MIN_PRESOLVE_BUDGET_MS), ceiling)
    }

    /**
     * The presolve phase's allowance, as a [Cancellation] for the phase and a [PresolveBudget] the round
     * engine slices per pass. Derived by [derivedPresolveBudgetMs] as a share of the run's own `-t`
     * budget rather than a flat figure, and counted in work. An explicit `klause.presolve.budget.ms` takes
     * precedence. A shared elapsed ceiling also reserves time for search when work underprices preparation.
     */
    internal fun presolveAllowance(
        common: CommonOptions,
        solveCancel: Cancellation,
    ): Pair<Cancellation, PresolveBudget?> {
        val budget = common.sharedPresolveBudget()
        val phaseStop = common.presolveTimeCancellation or solveCancel
        return (budget?.orSpent(phaseStop) ?: phaseStop) to budget
    }

    /** Naked single backtrack solve for the `fixed`/FD engine. A model annotation selects its heuristic;
     *  without one, the standard conflict-driven heuristic is used and selector overrides are accepted. */
    private fun runBacktrack(
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        presolveConfig: PresolveConfig,
        solutionSetSensitive: Boolean,
        presolveCancellation: Cancellation,
        presolveBudget: PresolveBudget?,
        cancel: Cancellation,
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

    /**
     * The work [budget] was charged since [before], beside the [elapsed] it took and the whole allowance's
     * standing, relating the work unit to time. Empty when presolve ran without a budget.
     */
    private fun presolveWorkText(budget: PresolveBudget?, before: Long, elapsed: Duration): String = budget?.let {
        ", work ${it.spent() - before} in ${elapsed.inWholeMilliseconds}ms (${it.spent()} of ${it.allowance})"
    }.orEmpty()

    /**
     * Print presolve timing, size and factor-count changes, LP harvest statistics and proven
     * infeasibility for `dry-run-presolve`. Timing excludes JVM startup and parsing.
     */
    internal fun printPresolved(
        original: Problem,
        presolved: Problem,
        stats: PresolveStats?,
        elapsed: Duration,
        loadElapsedMs: Long?,
        constructionBakeElapsed: Duration,
    ) {
        val passes = stats?.passes.orEmpty()
        errPrintln("presolve dry-run:")
        printPresolveDiagnostics(stats)
        // Heap after ingest, which is the quantity an OOM at `-Xmx` is about. Retained is what
        // the built problem holds; peak includes the transients the build passed through, so a large gap
        // points at a structure that is materialized and dropped rather than one that is kept.
        sampleHeap()?.let { heap ->
            val peak = heap.peakBytes?.let { "peak: ${it / MIB}MiB, " }.orEmpty()
            errPrintln(
                "  heap retained: ${heap.retainedBytes / MIB}MiB, " +
                    "$peak" +
                    "committed: ${heap.committedBytes / MIB}MiB",
            )
        }
        // Split the load into parse, the root bake (step 0), and the presolve passes. The base bake is
        // deferred to the pipeline (stats.bakeElapsed) for a front-end problem, or ran while constructing
        // the source problem otherwise — only one is non-zero. `elapsed` is the whole presolve phase,
        // which for a deferred problem includes the step-0 bake, so the passes get the remainder.
        val stepZeroBake = stats?.bakeElapsed ?: Duration.ZERO
        val bake = constructionBakeElapsed + stepZeroBake
        loadElapsedMs?.let { load ->
            errPrintln("  parse: ${(load - constructionBakeElapsed.inWholeMilliseconds).coerceAtLeast(0)}ms")
            errPrintln("  bake (step 0): $bake")
        }
        // Presolve passes alone (the step-0 bake is broken out above; for a deferred problem it is part
        // of the pipeline's wall time, so subtract it to leave the passes' own cost).
        errPrintln("  elapsed: ${elapsed - stepZeroBake}")
        errPrintln("  passes fired: ${if (passes.isEmpty()) "(none)" else passes.joinToString(", ")}")
        errPrintln("  bool vars: ${presolved.numBoolVars}, int vars: ${presolved.numIntVars}")
        errPrintln("  factors: ${original.factors.size} -> ${presolved.factors.size}")
        errPrintln("  int-domain span: ${spanText(original)} -> ${spanText(presolved)}")
        errPrintln("  open columns: ${openText(original)} -> ${openText(presolved)}")
        errPrintln("  theory-eligible columns: ${theoryText(original)} -> ${theoryText(presolved)}")
        val before = factorHistogram(original)
        val after = factorHistogram(presolved)
        for (kind in (before.keys + after.keys).sorted()) {
            val b = before[kind] ?: 0
            val a = after[kind] ?: 0
            errPrintln("  $kind: $b -> $a")
        }
        // The LP harvest's own contribution, isolated from the combinatorial passes whose net effect the
        // counts above conflate — the point of inspecting the LP presolve specifically.
        stats?.lpHarvest?.let { lp ->
            val dims = "relaxation ${lp.relaxationCols} cols / ${lp.relaxationRows} rows / ${lp.relaxationNnz} nnz"
            if (lp.skipped) {
                errPrintln("  lp-harvest: skipped ($dims over the size budget)")
            } else {
                val parts = buildList {
                    if (lp.rootInfeasible) add("root-infeasible")
                    if (lp.boundsShaved > 0) add("shaved ${lp.boundsShaved} bound(s)")
                    if (lp.objectiveLbRaised) add("raised objective lb")
                    if (lp.constraintsRemoved > 0) add("removed ${lp.constraintsRemoved} redundant constraint(s)")
                    if (lp.equalitiesAdded > 0) add("added ${lp.equalitiesAdded} implied equality(ies)")
                }
                errPrintln("  lp-harvest: ${parts.joinToString(", ")} ($dims)")
            }
        }
        // Read the verdict presolve already reached. Asking the problem for its own `baked` instead forces
        // a lazy that a rebuilt (already-folded) problem has never run, paying a fresh root fixpoint over
        // every factor after the summary has printed.
        if (stats?.infeasible == true) {
            errPrintln("  INFEASIBLE: presolve proved the problem unsatisfiable")
        }
    }

    /**
     * Saturating total integer-domain span `sum(max - min)`. Widths and the sum are checked
     * for overflow. Any open side saturates the total; [openText] reports the open-column count.
     */
    private fun domainSpan(problem: Problem): Long {
        val bounds = problem.intBounds
        var span = 0L
        for (v in 0 until problem.numIntVars) {
            if (bounds.isOpen(v)) return Long.MAX_VALUE
            // The value set a closed column declares is what the search enumerates, and the fold narrows
            // it; the bounds are the hull it sits in. A column stating bounds alone has only the hull.
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

    /**
     * Open integer columns as `<open> of <total>`. A column is open when either source bound
     * is absent, regardless of any finite search box. Complements the saturated [spanText].
     */
    private fun openText(problem: Problem): String {
        val bounds = problem.intBounds
        var open = 0
        for (v in 0 until problem.numIntVars) {
            if (bounds.isOpen(v)) open++
        }
        return "$open of ${problem.numIntVars}"
    }

    /** Whether the model declares no bound on one of integer column [v]'s sides. */
    private fun IntBounds.isOpen(v: Int): Boolean = isOpenLower(v) || isOpenUpper(v)

    /**
     * Integer columns no factor needs a finite domain for, so nothing about them has to be enumerated.
     *
     * Eligible is not the same as decidable: an MPS model is entirely `Linear` rows and reads as fully
     * eligible, yet its integers still have to be searched until something decides them. The actionable
     * figure is the second one — a theory-eligible column with an open source side.
     */
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

    /** [domainSpan] for the dry-run readout, naming the saturated case instead of printing a number
     *  that would read as an exact total. */
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
        presolveCancellation: Cancellation,
        presolveBudget: PresolveBudget?,
        cancel: Cancellation,
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
        val arms = cliProp(CliKnobs.portfolioArms)?.let { value ->
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
        presolveCancellation: Cancellation,
        presolveBudget: PresolveBudget?,
        cancel: Cancellation,
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
            onEvent = if (fixed) verboseListener(common.verbose) else null,
            onPortfolioEvent = if (fixed) null else portfolioVerboseListener(common.verbose),
            onPortfolioFault = if (fixed) null else ::warnPortfolioFault,
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
        result.nativeGridOutcome?.let(output::onNativeGridOutcome)
        val preparation = result.preparation
        cliLogger(common.verbose).v {
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
                )
                return
            }

            is FiniteSolveOutcome.DryRun -> {
                output.begin(solvable.optimize, solvable.maximize)
                errPrintln(outcome.heading)
                outcome.lines.forEach(::errPrintln)
            }

            is FiniteSolveOutcome.Completed -> {
                output.begin(solvable.optimize, solvable.maximize)
                output.onVerdictContext(
                    VerdictContext(
                        budgetExhausted = budgetSpent(common, outcome.stats.run.timedOut),
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

    /** Renders [sample] and returns its (objective, continuousObjective) pair so a caller that also
     *  needs those values (e.g. arm attribution) doesn't re-walk the objective's coefficient arrays. */
    private fun emit(output: OutputProtocol, solvable: Solvable, sample: Sample): Pair<Long?, Double?> {
        val objective = solvable.objectiveValue?.invoke(sample)
        val continuousObjective = solvable.continuousObjectiveValue?.invoke(sample)
        output.onSolution(solvable.render(sample), objective, continuousObjective)
        return objective to continuousObjective
    }

    private fun stats(common: CommonOptions, output: OutputProtocol, s: SolveStats, ms: Long, solutions: Long) {
        if (common.statistics) output.onStatistics(s, ms, solutions)
    }

    /** Re-express the LS incumbent objective in the model's orientation, reusing the same sign-corrected
     *  [Solvable.objectiveValue] that renders solutions. The engine records the incumbent in its internal
     *  "lower is better" frame (maximisation via a negated coefficient); the objective lambda reads the
     *  canonical objective variable in original units. Only the routing LP stats are merged for runs
     *  without an incumbent or without an objective lambda. */
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

/** Render one [SearchEvent] as a `-v` line; [worker] tags portfolio workers. */
internal fun describeEvent(e: SearchEvent, t: Long, worker: String? = null): String {
    val who = worker?.let { " $it" }.orEmpty()
    return when (e) {
        is SearchEvent.Restart -> "[$t ms]$who restart #${e.index} after ${e.steps} decisions"
        is SearchEvent.LearnedDbSweep -> "[$t ms]$who learned-DB sweep: kept ${e.kept}, dropped ${e.dropped}"
        is SearchEvent.Incumbent -> "[$t ms]$who incumbent objective ${e.objective}"
        is SearchEvent.WorkerAdmission -> "admission ${e.recipe} admitted=${e.admitted} ${e.requirements} declines=${e.declines}"
        is SearchEvent.PortfolioDiagnostic -> "portfolio ${e.event}"
    }
}

/** Live-event listener for `-v`; null when not verbose so the engines skip observation. */
internal fun verboseListener(verbose: Boolean): ((SearchEvent) -> Unit)? {
    if (!verbose) return null
    val log = cliLogger(verbose = true)
    val start = nowMillis()
    return { e -> log.v { describeEvent(e, nowMillis() - start) } }
}

/**
 * Print a quarantined portfolio arm to stderr whatever the verbosity: an arm claiming a result the model refutes is a
 * bug in that arm's configuration, which the run survives but nobody should miss.
 */
internal fun warnPortfolioFault(fault: ArmFault) {
    errPrintln("% WARNING: portfolio arm ${fault.workerLabel} quarantined: ${fault.reason}")
}

/** Per-worker `-v` listener for the portfolio paths. Workers run concurrently; the logger
 *  writes whole lines, which is the only shared state. */
internal fun portfolioVerboseListener(verbose: Boolean): ((String, SearchEvent) -> Unit)? {
    if (!verbose) return null
    val log = cliLogger(verbose = true)
    val start = nowMillis()
    return { worker, e -> log.v { describeEvent(e, nowMillis() - start, worker) } }
}

/** Bytes per mebibyte — heap figures are reported in MiB, the unit `-Xmx` is set in. */
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
