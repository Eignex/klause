package com.eignex.klause.cli

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.presolve.AffinePivotOrder
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.EngineParams
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FiniteSolveCallbacks
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.FiniteSolveRequest
import com.eignex.klause.solver.pipeline.FiniteSolveVerdict
import com.eignex.klause.solver.pipeline.NODE_LIMIT_KEY
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryOptimum
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.autoArms
import com.eignex.klause.solver.pipeline.solve
import com.eignex.klause.solver.pipeline.variablePartition
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The unified, mode-agnostic solve driver. Every CLI mode (MiniZinc, XCSP3, SMT-LIB) feeds a
 * [Solvable] + [CommonOptions] in, and all terminal reporting goes out through the mode's
 * [OutputProtocol]. It translates frontend flags, deadlines, and source annotations into pipeline
 * requests, then renders their streamed models and terminal results.
 */
internal object SolveCore {

    fun solve(rawSolvable: Solvable, common: CommonOptions, output: OutputProtocol) {
        // Engine resolution: explicit `-e` wins, then `-f` (free) ≡ `-e cp`, else the configured
        // default ([defaultEngine] — the built-in [FiniteEngine.DEFAULT] or a packaged image's env override).
        val engine = common.engine
            ?.let { parseEngine(it) ?: usageError("unknown engine `$it`; expected ${engineIds()}") }
            ?: if (common.freeSearch) FiniteEngine.BACKTRACK else defaultEngine()
        // Presolve once, before any worker is built, so every engine and portfolio worker shares
        // the one transformed problem. Solution-set-altering passes (symmetry breaking, value
        // precedence) are dropped for a pure-LS engine (their ordering constraints are believed to
        // hurt local search); solutions are reconstructed at render time. An explicit `--presolve`
        // overrides this default verbatim, so an LS run can be benchmarked *with* those passes on
        // (the A/B that decides whether the LS-specific stripping is worth keeping).
        val base = common.presolve?.let { PresolveConfig.parse(it) } ?: KlauseConfig.current.presolveConfig()
        // `affine-pivot-order` selects how affine elimination orders its pivots. A cost knob only — the
        // orders differ in what they fold first, never in what the problem means — exposed so the choice
        // can be A/B'd on a corpus rather than argued about.
        val config = affinePivotOrderParam(common)?.let { base.withAffinePivotOrder(it) } ?: base
        // Symmetry breaking collapses symmetric solutions, so disable it (via auto resolution) when
        // the run wants the full solution set: enumeration (`-a`) or a multi-solution cap (`-n N`),
        // unless we're optimizing (a single optimum, where symmetry breaking is sound).
        val solutionSetSensitive = !rawSolvable.optimize && (common.allSolutions || (common.solutionCap ?: 1L) > 1L)
        // The `-t` wall-clock deadline is captured once, here, and shared by every phase: presolve, the
        // LP root build/solve inside the engine, and the search loop. Building it per-phase would let
        // each phase restart the clock and blow the limit cumulatively.
        // Taken before the token is built, because the allowance has to stop the driver and not only the
        // engine: an engine-local check leaves the driver re-entering arms that cancel at their first
        // poll, which burns the whole deadline and overshoots the cap several times over.
        val (deadline, deadlineCancel) = deadlineCancellation(common)
        val (presolveCancel, presolveBudget) = presolveAllowance(common, deadlineCancel, deadline)
        when (val pipeline = rawSolvable.pipeline) {
            is SolvablePipeline.OpenTheory -> {
                val nodeLimit = takeOpenNodeLimit(common)
                if (common.allSolutions || (common.solutionCap ?: 1L) > 1L) {
                    usageError("all-solution enumeration is unavailable for open theory models")
                }
                val theoryParams = TheoryParams(
                    maxLeaves = Long.MAX_VALUE,
                    openWorkLimit = nodeLimit ?: Long.MAX_VALUE,
                    maxDecisions = takeOpenLongParam(common, "max-decisions", nonNegative = true) ?: Long.MAX_VALUE,
                    sharedRestart = takeOpenLongParam(common, "shared-restart", nonNegative = false),
                    maxLearnedClauses = takeOpenIntParam(common, "max-learned", nonNegative = true),
                    lbdGlue = takeOpenIntParam(common, "lbd-glue", nonNegative = true) ?: 2,
                    openHintFlips = takeOpenLongParam(common, "open-hint-flips", nonNegative = true),
                    cancellation = deadlineCancel,
                    timeout = deadlineCancel,
                )
                val request = pipeline.request.withPresolve(
                    config,
                    cancellation = presolveCancel,
                    budget = presolveBudget,
                )
                val objective = request.objective
                if (objective != null) {
                    output.begin(optimize = true, maximize = request.maximize)
                    solveOpenTheoryOptimum(request, pipeline.render, theoryParams, common.statistics, output) {
                        budgetSpent(common, it)
                    }
                    return
                }
                output.begin(optimize = false, maximize = false)
                val result = (
                    OpenTheoryPipeline.execute(
                        request,
                        theoryParams,
                    ) as OpenTheoryExecution.Satisfy
                    ).result
                output.onVerdictContext(
                    VerdictContext(
                        budgetExhausted = budgetSpent(common, result.stats.run.timedOut),
                        terminationReason = (result as? OpenTheoryResult.Unknown)?.reason,
                    ),
                )
                when (result) {
                    is OpenTheoryResult.Sat -> {
                        output.onSolution(pipeline.render(result.assignment), null)
                        output.onComplete(Verdict.SATISFIABLE)
                    }

                    is OpenTheoryResult.Unsat -> output.onComplete(Verdict.UNSATISFIABLE)

                    is OpenTheoryResult.Unknown -> output.onComplete(Verdict.UNKNOWN)
                }
                if (common.statistics) {
                    output.onStatistics(
                        result.stats,
                        result.stats.run.wallMs,
                        if (result is OpenTheoryResult.Sat) 1L else 0L,
                    )
                }
                return
            }

            SolvablePipeline.FiniteCp -> Unit
        }
        val nodeBudget = takeNodeBudget(common)
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
                rejectParallel(engine, cores, alt = null)
                runBacktrack(
                    rawSolvable, common, output, config, solutionSetSensitive, presolveCancel, presolveBudget,
                    cancel, deadline, nodeBudget,
                )
            }

            // The parallel-capable portfolio engines: their mix is carried on the enum. `ls` resolves
            // a four-axis arm pool from its --params (a `strategy=` base plus per-axis edits); `cp`
            // resolves a per-solver override pool from its --params (var-/val-selector, luby, …). A
            // single resolved arm runs as a one-arm pool.
            FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.MIXED, FiniteEngine.ALNS ->
                runPortfolio(
                    rawSolvable, common, output, cores, engine, config, solutionSetSensitive, presolveCancel,
                    presolveBudget, cancel, nodeBudget,
                )
        }
    }

    /**
     * Whether the `-t` budget is gone by the time a verdict is rendered.
     *
     * [backendTimedOut] is the backend's own flag, which not every path sets — a census of corpus
     * unknowns attributed 26 of 36 miplib instances to "stopped for no stated reason" purely because the
     * MPS path leaves it false. The deadline itself is the reliable witness: it is anchored once at
     * process start, so comparing against it here catches every path that ran out of time. Consulted only
     * to explain a soft verdict, so a decided run finishing exactly on the boundary costs nothing.
     */
    private fun budgetSpent(common: CommonOptions, backendTimedOut: Boolean): Boolean =
        backendTimedOut || common.deadlineAtMs?.let { nowMillis() >= it } == true

    /** Reject `-p N>1` for a single-core engine; [alt], when given, is the parallel engine to suggest. */
    private fun rejectParallel(engine: FiniteEngine, cores: Int, alt: FiniteEngine?) {
        if (cores <= 1) return
        val hint = alt?.let { "; use '${it.id}' for a parallel pool" } ?: " (FD track); drop -p"
        usageError("engine '${engine.id}' is single-core$hint")
    }

    /**
     * The `node-limit` engine param as a [NodeBudget], **removed** from [CommonOptions.engineParams] as
     * it is read. One budget per invocation is the whole point — every arm spends the same allowance —
     * and [EngineParams] consumes keys per instance, so leaving it in place would both build a second
     * budget downstream and, where it did not, fail validation as an unknown key.
     */
    private fun takeNodeBudget(common: CommonOptions): NodeBudget? {
        val entry = common.engineParams.firstOrNull { it.startsWith("$NODE_LIMIT_KEY=") } ?: return null
        common.engineParams.remove(entry)
        val raw = entry.substringAfter('=')
        val limit = raw.toLongOrNull()
            ?: usageError("engine param `$NODE_LIMIT_KEY` expects an integer, got `$raw`")
        if (limit <= 0) usageError("engine param `$NODE_LIMIT_KEY` expects a positive node count, got $limit")
        return NodeBudget(limit)
    }

    /** Consume the route-local fixed-work limit for an open-theory solve. */
    private fun takeOpenNodeLimit(common: CommonOptions): Long? {
        val entry = common.engineParams.firstOrNull { it.startsWith("$NODE_LIMIT_KEY=") } ?: return null
        common.engineParams.remove(entry)
        val raw = entry.substringAfter('=')
        val limit = raw.toLongOrNull()
            ?: usageError("engine param `$NODE_LIMIT_KEY` expects a non-negative integer, got `$raw`")
        if (limit < 0) usageError("engine param `$NODE_LIMIT_KEY` expects a non-negative integer, got $limit")
        return limit
    }

    private fun takeOpenLongParam(common: CommonOptions, key: String, nonNegative: Boolean): Long? {
        val entry = common.engineParams.firstOrNull { it.startsWith("$key=") } ?: return null
        common.engineParams.remove(entry)
        val raw = entry.substringAfter('=')
        val value = raw.toLongOrNull() ?: usageError("engine param `$key` expects an integer, got `$raw`")
        val invalid = if (nonNegative) value < 0 else value <= 0
        if (invalid) {
            usageError(
                "engine param `$key` expects a ${if (nonNegative) "non-negative" else "positive"} integer, got $value",
            )
        }
        return value
    }

    private fun takeOpenIntParam(common: CommonOptions, key: String, nonNegative: Boolean): Int? =
        takeOpenLongParam(common, key, nonNegative)?.let {
            if (it > Int.MAX_VALUE) usageError("engine param `$key` exceeds Int.MAX_VALUE")
            it.toInt()
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
     * budget rather than a flat figure. An explicit `klause.presolve.budget.ms` still wins.
     */
    private fun presolveAllowance(
        common: CommonOptions,
        solveCancel: Cancellation,
        solveDeadline: Long?,
    ): Pair<Cancellation, PresolveBudget?> {
        val explicit = cliProp(CliKnobs.presolveBudgetMs)?.toLongOrNull()
        val fraction = cliProp(CliKnobs.presolveBudgetFraction)?.toDoubleOrNull()
            ?: CliKnobs.DEFAULT_PRESOLVE_BUDGET_FRACTION
        val budgetMs = explicit ?: derivedPresolveBudgetMs(common.timeLimitMs, fraction)
        if (budgetMs <= 0) return solveCancel to null
        val presolveDeadline = nowMillis() + budgetMs
        val cap = solveDeadline?.let { minOf(it, presolveDeadline) } ?: presolveDeadline
        return Cancellation { nowMillis() > cap } to PresolveBudget { cap - nowMillis() }
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
    ) {
        // `--lp CEILING` selects the LP emphasis for the naked engine too (it powers the single-engine
        // LP-success measurement under `-s`); the flag wins, then the `klause.lp` env default, else the
        // route default (LP off for naked CP).
        val lpConfig = (common.lp ?: defaultLp())?.let {
            runCatching { LpConfig.parse(it) }.getOrElse { e -> usageError("--lp: ${e.message}") }
        } ?: LpConfig.OFF
        executeFinite(
            FiniteSolveRequest(
                shape = solvable.finiteShape,
                engine = FiniteEngine.FIXED,
                presolveConfig = presolveConfig,
                explicitPresolveConfig = common.presolve != null,
                solutionSetSensitive = solutionSetSensitive,
                presolveBudget = presolveBudget,
                cores = 1,
                engineParams = common.engineParams,
                randomSeed = common.randomSeed,
                defaultArms = 1,
                lpConfig = lpConfig,
                cancellation = cancel,
                presolveCancellation = presolveCancellation,
                nodeBudget = nodeBudget,
                solveBudgetMillis = common.timeLimitMs,
                allSolutions = common.allSolutions,
                solutionCap = common.solutionCap,
                deadlineExceeded = { deadline != null && nowMillis() > deadline },
                onEvent = verboseListener(common.verbose),
                onPortfolioEvent = null,
                prepareOnly = EngineParams(common.engineParams).bool("dry-run-presolve") == true,
            ),
            solvable,
            common,
            output,
        )
    }

    /** Print what presolve did (`dry-run-presolve`) to stderr: the presolve-phase wall time,
     *  variable/constraint counts, total integer-domain span, the per-factor-kind histogram delta, the LP
     *  harvest's own contribution, and any proven infeasibility — so the effect (and cost) of a
     *  `--presolve` config can be inspected and A/B-compared without solving. The elapsed time is the
     *  presolve phase alone, excluding JVM startup and parsing, so it is the figure to watch when tuning
     *  presolve cost. */
    private fun printPresolved(
        original: Problem,
        presolved: Problem,
        stats: PresolveStats?,
        elapsed: Duration,
        loadElapsedMs: Long?,
        constructionBakeElapsed: Duration,
    ) {
        val passes = stats?.passes.orEmpty()
        errPrintln("presolve dry-run:")
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
        // a lazy that a rebuilt (already-folded) problem has never run, so the report pays a fresh root
        // fixpoint over every factor - after the whole summary has printed, which from outside reads as
        // the process hanging rather than as the diagnostic costing anything.
        if (stats?.infeasible == true) {
            errPrintln("  INFEASIBLE: presolve proved the problem unsatisfiable")
        }
    }

    /**
     * Total integer-domain span `Σ (max − min)` — the coarse problem-size measure presolve shrinks —
     * saturating at [Long.MAX_VALUE]. Both the per-domain width and the sum exceed a `Long` on a model
     * with near-full-range domains, and a wrapped total reads as a plausible figure rather than as
     * overflow, so each is checked. Matches the saturating convention of `PresolveShared.maxIntSpan`.
     */
    private fun domainSpan(problem: Problem): Long {
        var span = 0L
        for (d in problem.requireFiniteIntDomains()) {
            val width = d.max - d.min
            if (width < 0L) return Long.MAX_VALUE
            span += width
            if (span < 0L) return Long.MAX_VALUE
        }
        return span
    }

    /**
     * How many integer columns carry no usable bound, as `<open> of <total>`.
     *
     * [spanText] saturates on the first column it cannot sum, so one open column and ten thousand read
     * identically as "unbounded" - which is what makes an open-domain model look like a wall presolve
     * cannot touch when it may be nothing of the sort. This is the count that line hides.
     *
     * A column counts as open when its width overflows `Long` or exceeds [OPEN_WIDTH]. The exact cut is
     * not load-bearing: real models put their columns either near the integer limit or well under a million, so
     * nothing observed lands near it.
     */

    private fun openText(problem: Problem): String {
        var open = 0
        for (d in problem.requireFiniteIntDomains()) {
            val width = d.max - d.min
            if (width < 0L || width > OPEN_WIDTH) open++
        }
        return "$open of ${problem.requireFiniteIntDomains().size}"
    }

    /**
     * Integer columns no factor needs a finite domain for, so nothing about them has to be enumerated.
     *
     * Eligible is not the same as decidable: an MPS model is entirely [Linear] rows and reads as fully
     * eligible, yet its integers still have to be searched until something decides them. The actionable
     * figure is the second one — a theory-eligible column with an open source side.
     */
    private fun theoryText(problem: Problem): String {
        val partition = problem.variablePartition()
        var openAndEligible = 0
        for (v in 0 until partition.size) {
            if (!partition.isTheoryEligible(v)) continue
            if (problem.intBounds.isOpenLower(v) || problem.intBounds.isOpenUpper(v)) openAndEligible++
        }
        return "${partition.theoryEligibleCount} of ${partition.size}, $openAndEligible of them open"
    }

    /** [domainSpan] for the dry-run readout, naming the saturated case instead of printing a number
     *  that would read as an exact total. */
    private fun spanText(problem: Problem): String =
        domainSpan(problem).let { if (it == Long.MAX_VALUE) "unbounded" else it.toString() }

    /**
     * The `affine-pivot-order` param, or `null` to keep the configured default. This is a *presolve*
     * knob sharing the `--param` namespace the engines validate, so it is consumed out of
     * [CommonOptions.engineParams] here — otherwise every engine's unknown-key check would reject it.
     */
    private fun affinePivotOrderParam(common: CommonOptions): AffinePivotOrder? {
        val prefix = "$AFFINE_PIVOT_ORDER_KEY="
        val idx = common.engineParams.indexOfFirst { it.startsWith(prefix) }
        if (idx < 0) return null
        val id = common.engineParams.removeAt(idx).removePrefix(prefix)
        return AffinePivotOrder.entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
            ?: usageError(
                "engine param `$AFFINE_PIVOT_ORDER_KEY` expects " +
                    AffinePivotOrder.entries.joinToString("|") { it.name.lowercase() } + ", got `$id`",
            )
    }

    private const val AFFINE_PIVOT_ORDER_KEY = "affine-pivot-order"

    /** Width above which [openText] reads a column as carrying no usable bound. */
    private const val OPEN_WIDTH = 1L shl 40

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
    ) {
        // Default arm-pool size: an env override, else auto-tuned from the core count.
        val defaultArms = cliProp(CliKnobs.portfolioArms)?.toIntOrNull() ?: autoArms(cores)
        // `--lp CEILING` caps the portfolio's LP emphasis; the flag wins, then the `klause.lp`
        // env default, else AGGRESSIVE (uncapped — the pool spreads the LP intensity itself); `off`
        // disables LP across the pool.
        val lpCeiling = (common.lp ?: defaultLp())?.let {
            runCatching { LpConfig.parse(it) }.getOrElse { e -> usageError("--lp: ${e.message}") }
        } ?: LpConfig.AGGRESSIVE
        executeFinite(
            FiniteSolveRequest(
                shape = solvable.finiteShape,
                engine = engine,
                presolveConfig = presolveConfig,
                explicitPresolveConfig = common.presolve != null,
                solutionSetSensitive = solutionSetSensitive,
                presolveBudget = presolveBudget,
                cores = cores,
                engineParams = common.engineParams,
                randomSeed = common.randomSeed,
                defaultArms = defaultArms,
                lpConfig = lpCeiling,
                cancellation = cancel,
                presolveCancellation = presolveCancellation,
                nodeBudget = nodeBudget,
                solveBudgetMillis = null,
                allSolutions = common.allSolutions,
                solutionCap = common.solutionCap,
                deadlineExceeded = { false },
                onEvent = null,
                onPortfolioEvent = portfolioVerboseListener(common.verbose),
                prepareOnly = EngineParams(common.engineParams).bool("dry-run-presolve") == true,
            ),
            solvable,
            common,
            output,
        )
    }

    private fun executeFinite(
        request: FiniteSolveRequest,
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
    ) {
        val result = FinitePipeline.solve(
            request,
            FiniteSolveCallbacks(
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
            ),
        )
        val preparation = result.preparation
        cliLogger(common.verbose).v {
            val p0 = solvable.finiteProblem
            val p1 = preparation.problem
            "presolve [${request.engine.id}]: factors ${p0.numFactors}→${p1.numFactors}, " +
                "ints ${p0.numIntVars}→${p1.numIntVars}, bools ${p0.numBoolVars}→${p1.numBoolVars}"
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
                        completePool = !request.engine.pureLocalSearch,
                    ),
                )
                output.onComplete(outcome.verdict.toCliVerdict())
                stats(
                    common,
                    output,
                    withModelObjective(outcome.stats, solvable, outcome.bestSample),
                    outcome.elapsedMs,
                    outcome.solutions,
                    preparation.presolve,
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
    }

    /** Renders [sample] and returns its (objective, continuousObjective) pair so a caller that also
     *  needs those values (e.g. arm attribution) doesn't re-walk the objective's coefficient arrays. */
    private fun emit(output: OutputProtocol, solvable: Solvable, sample: Sample): Pair<Long?, Double?> {
        val objective = solvable.objectiveValue?.invoke(sample)
        val continuousObjective = solvable.continuousObjectiveValue?.invoke(sample)
        output.onSolution(solvable.render(sample), objective, continuousObjective)
        return objective to continuousObjective
    }

    private fun stats(
        common: CommonOptions,
        output: OutputProtocol,
        s: SolveStats,
        ms: Long,
        solutions: Long,
        presolve: PresolveStats?,
    ) {
        if (common.statistics) output.onStatistics(s.copy(presolve = presolve), ms, solutions)
    }

    /** Re-express the LS incumbent objective in the model's orientation, reusing the same sign-corrected
     *  [Solvable.objectiveValue] that renders solutions and arm attribution. The engine records the
     *  incumbent in its internal "lower is better" frame (maximisation via a negated coefficient); the
     *  objective lambda reads the canonical objective variable in original units, which also reconciles
     *  the LS functional gradient view back to the linear objective. No-op for satisfy / infeasible
     *  runs (no incumbent) and non-MiniZinc modes without an objective lambda. */
    private fun withModelObjective(s: SolveStats, solvable: Solvable, sample: Sample?): SolveStats {
        if (sample == null || s.ls.incumbentObjective.isNaN()) return s
        // The statistic is already a Double, so it reports the continuous contribution where there is
        // one rather than the discrete part the `o` line stopped reporting alone.
        solvable.continuousObjectiveValue?.let { exact ->
            return s.copy(ls = s.ls.copy(incumbentObjective = exact(sample)))
        }
        val objectiveValue = solvable.objectiveValue ?: return s
        return s.copy(ls = s.ls.copy(incumbentObjective = objectiveValue(sample).toDouble()))
    }
}

/** Render one [SearchEvent] as a `-v` line; [worker] tags portfolio workers. */
internal fun describeEvent(e: SearchEvent, t: Long, worker: String? = null): String {
    val who = worker?.let { " $it" }.orEmpty()
    return when (e) {
        is SearchEvent.Restart -> "[$t ms]$who restart #${e.index} after ${e.steps} decisions"
        is SearchEvent.LearnedDbSweep -> "[$t ms]$who learned-DB sweep: kept ${e.kept}, dropped ${e.dropped}"
        is SearchEvent.Incumbent -> "[$t ms]$who incumbent objective ${e.objective}"
    }
}

/** Live-event listener for `-v`; null when not verbose so the engines skip observation. */
internal fun verboseListener(verbose: Boolean): ((SearchEvent) -> Unit)? {
    if (!verbose) return null
    val log = cliLogger(verbose = true)
    val start = nowMillis()
    return { e -> log.v { describeEvent(e, nowMillis() - start) } }
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

/**
 * Minimize [objective] over an open theory route and report the outcome.
 *
 * A maximize is driven as the minimization of the negated objective, and the reported value is negated
 * back, so the descent has one direction and the sign lives only at this boundary.
 */
private fun solveOpenTheoryOptimum(
    request: OpenTheoryRequest,
    render: (com.eignex.klause.solver.pipeline.OpenTheoryAssignment) -> String,
    params: TheoryParams,
    statistics: Boolean,
    output: OutputProtocol,
    budgetExhausted: (Boolean) -> Boolean,
) {
    val result = (
        OpenTheoryPipeline.execute(
            request,
            params,
        ) as OpenTheoryExecution.Optimize
        ).result
    val reported: (BigInteger) -> Long? = { value ->
        val signed = if (request.maximize) -value else value
        // An objective past 64 bits is reported as absent rather than as a wrapped number.
        if (signed >= LONG_MIN_BIG && signed <= LONG_MAX_BIG) signed.longValue() else null
    }
    output.onVerdictContext(
        VerdictContext(
            budgetExhausted = budgetExhausted(result.stats.run.timedOut),
            terminationReason = (result as? OpenTheoryOptimum.Bounded)?.reason,
        ),
    )
    when (result) {
        is OpenTheoryOptimum.Optimal -> {
            output.onSolution(render(result.assignment), reported(result.value))
            output.onComplete(Verdict.OPTIMAL)
        }

        is OpenTheoryOptimum.Infeasible -> output.onComplete(Verdict.UNSATISFIABLE)

        is OpenTheoryOptimum.Bounded -> {
            val incumbent = result.incumbent
            if (incumbent == null) {
                output.onComplete(Verdict.UNKNOWN)
            } else {
                output.onSolution(render(incumbent), result.value?.let(reported))
                output.onComplete(Verdict.BEST_FOUND)
            }
        }
    }
    if (statistics) {
        val found = when (result) {
            is OpenTheoryOptimum.Optimal -> 1L
            is OpenTheoryOptimum.Infeasible -> 0L
            is OpenTheoryOptimum.Bounded -> if (result.incumbent == null) 0L else 1L
        }
        output.onStatistics(result.stats, result.stats.run.wallMs, found)
    }
}

private val LONG_MIN_BIG = BigInteger.fromLong(Long.MIN_VALUE)
private val LONG_MAX_BIG = BigInteger.fromLong(Long.MAX_VALUE)
