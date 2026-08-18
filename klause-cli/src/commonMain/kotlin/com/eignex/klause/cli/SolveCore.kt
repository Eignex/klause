package com.eignex.klause.cli

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.portfolio.AttributedImprovement
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.SequentialPortfolio
import com.eignex.klause.presolve.AffinePivotOrder
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The unified, mode-agnostic solve driver. Every CLI mode (MiniZinc, XCSP3, SMT-LIB) feeds a
 * [Solvable] + [CommonOptions] in, and all terminal reporting goes out through the mode's
 * [OutputProtocol] — so engine selection, the `-p N` worker split, deadline/cancellation,
 * `--param` application, CP-seeding, the satisfy/`-a`/`-n` enumeration and optimize streaming
 * loops live here exactly once instead of being duplicated per front-end.
 */
internal object SolveCore {

    fun solve(rawSolvable: Solvable, common: CommonOptions, output: OutputProtocol) {
        // Engine resolution: explicit `-e` wins, then `-f` (free) ≡ `-e cp`, else the configured
        // default ([defaultEngine] — the built-in [Engine.DEFAULT] or a packaged image's env override).
        val engine = common.engine
            ?.let { Engine.fromId(it) ?: usageError("unknown engine `$it`; expected ${Engine.ids()}") }
            ?: if (common.freeSearch) Engine.CP else defaultEngine()
        // Presolve once, before any worker is built, so every engine and portfolio worker shares
        // the one transformed problem. Solution-set-altering passes (symmetry breaking, value
        // precedence) are dropped for a pure-LS engine (their ordering constraints are believed to
        // hurt local search); solutions are reconstructed at render time. An explicit `--presolve`
        // overrides this default verbatim, so an LS run can be benchmarked *with* those passes on
        // (the A/B that decides whether the LS-specific stripping is worth keeping).
        val base = common.presolve?.let { PresolveConfig.parse(it) } ?: KlauseConfig.current.presolveConfig()
        val forEngine = if (engine.pureLs && common.presolve == null) base.forLocalSearch() else base
        // `affine-pivot-order` selects how affine elimination orders its pivots. A cost knob only — the
        // orders differ in what they fold first, never in what the problem means — exposed so the choice
        // can be A/B'd on a corpus rather than argued about.
        val config = affinePivotOrderParam(common)?.let { forEngine.withAffinePivotOrder(it) } ?: forEngine
        // Symmetry breaking collapses symmetric solutions, so disable it (via auto resolution) when
        // the run wants the full solution set: enumeration (`-a`) or a multi-solution cap (`-n N`),
        // unless we're optimizing (a single optimum, where symmetry breaking is sound).
        val solutionSetSensitive = !rawSolvable.optimize && (common.allSolutions || (common.solutionCap ?: 1L) > 1L)
        // The `-t` wall-clock deadline is captured once, here, and shared by every phase: presolve, the
        // LP root build/solve inside the engine, and the search loop. Building it per-phase would let
        // each phase restart the clock and blow the limit cumulatively.
        val (deadline, cancel) = deadlineCancellation(common)
        val presolveStart = TimeSource.Monotonic.markNow()
        val (presolveCancel, presolveBudget) = presolveAllowance(common, cancel, deadline)
        val solvable = rawSolvable.presolved(
            config,
            solutionSetSensitive,
            presolveCancel,
            boundingCancellation = cancel,
            presolveBudget = presolveBudget,
        )
        val presolveElapsed = presolveStart.elapsedNow()
        // `dry-run-presolve` prints what presolve produced and exits without solving — a fast,
        // engine-independent way to inspect/A-B a presolve config (engine param, like dry-run-solver).
        if (EngineParams(common.engineParams).bool("dry-run-presolve") == true) {
            printPresolved(
                rawSolvable.problem,
                solvable.problem,
                solvable.presolve,
                presolveElapsed,
                common.loadElapsedMs,
            )
            return
        }
        cliLogger(common.verbose).v {
            val p0 = rawSolvable.problem
            val p1 = solvable.problem
            "presolve [${engine.id}]: factors ${p0.numFactors}→${p1.numFactors}, " +
                "ints ${p0.numIntVars}→${p1.numIntVars}, bools ${p0.numBoolVars}→${p1.numBoolVars}"
        }
        output.begin(solvable.optimize, solvable.maximize)

        // Presolve already proved infeasibility (e.g. a gcd-indivisible equality caught by the
        // first-running coefficient-strengthening pass): report it directly rather than invoking a
        // solver, whose root bake would re-derive it by O(span) bound-narrowing on a wide clamped domain.
        if (solvable.presolve?.infeasible == true) {
            output.onComplete(Verdict.UNSATISFIABLE)
            return
        }

        // `-p N` is MiniZinc-standard parallelism = the **core** count. The portfolio engines
        // (cp/mixed/ls) run sequentially at `-p1` and as a parallel pool at `-pN`. The one naked
        // engine (fixed) is inherently single-core.
        val cores = common.parallel ?: 1
        when (engine) {
            // Naked single backtrack following the model's search annotation (FD track). The
            // annotation decides the heuristic, so per-solver selector --params are rejected.
            Engine.FIXED -> {
                rejectParallel(engine, cores, alt = null)
                runBacktrack(solvable, common, output, cancel, deadline)
            }

            // The parallel-capable portfolio engines: their mix is carried on the enum. `ls` resolves
            // a four-axis arm pool from its --params (a `strategy=` base plus per-axis edits); `cp`
            // resolves a per-solver override pool from its --params (var-/val-selector, luby, …). A
            // single resolved arm runs as a one-arm pool.
            Engine.CP, Engine.LS, Engine.MIXED, Engine.ALNS ->
                runPortfolio(solvable, common, output, cores, requireNotNull(engine.mix), cancel)
        }
    }

    /** Reject `-p N>1` for a single-core engine; [alt], when given, is the parallel engine to suggest. */
    private fun rejectParallel(engine: Engine, cores: Int, alt: Engine?) {
        if (cores <= 1) return
        val hint = alt?.let { "; use '${it.id}' for a parallel pool" } ?: " (FD track); drop -p"
        usageError("engine '${engine.id}' is single-core$hint")
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

    /** Naked single backtrack solve for the `fixed`/FD engine: follows the model's `solve :: *_search`
     *  annotation. Per-solver `var-selector`/`val-selector` --params are rejected (the annotation
     *  decides the heuristic); free-search heuristic A/B lives on `-e cp` instead. */
    private fun runBacktrack(
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        cancel: Cancellation,
        deadline: Long?,
    ) {
        // XCSP/SMT carry no annotation (null), so the naked engine falls back to the conflict-driven
        // preset base. Seed remains unset unless `-r` (or `--param seed=`) pins one.
        val base = solvable.annotatedBacktrackParams ?: BacktrackPresets.conflictDriven()
        // `--lp CEILING` selects the LP emphasis for the naked engine too (it powers the single-engine
        // LP-success measurement under `-s`); the flag wins, then the `klause.lp` env default, else the
        // base config (LP off for naked CP).
        val lpConfig = (common.lp ?: defaultLp())?.let {
            runCatching { LpConfig.parse(it) }.getOrElse { e -> usageError("--lp: ${e.message}") }
        } ?: base.lpConfig
        val engineParams = EngineParams(common.engineParams)
        // Consume `dry-run-solver` before applyBacktrackParams validates the leftover keys, so it isn't
        // rejected as unknown; it's an engine-agnostic mode, handled below.
        val dryRunSolver = engineParams.bool("dry-run-solver") ?: false
        val params = applyBacktrackParams(
            base.copy(
                randomSeed = common.randomSeed ?: base.randomSeed,
                cancellation = cancel,
                // Advisory total budget so the LP subsystem sizes its wall-clock caps against the real
                // `-t` deadline on the FD track, not the absolute root-LP ceiling.
                solveBudgetMillis = common.timeLimitMs,
                // Under a wall clock a fixpoint must be interruptible. The default floor only polls after
                // a fire count no atom-allocating fixpoint reaches, so `-t` was overshot 3x. That floor
                // is for a slice budget, where abandoning a fixpoint mid-way would change the resumed
                // result; a deadline carries no such obligation — when the time is gone it is gone.
                propagationCancelFloor = if (common.timeLimitMs != null) 0 else base.propagationCancelFloor,
                onEvent = verboseListener(common.verbose),
                lpConfig = lpConfig,
            ),
            engineParams,
        )
        cliLogger(common.verbose).v {
            "engine cp: seed=${params.randomSeed} luby=${params.lubyRestartBase} maxLearned=${params.maxLearnedClauses}"
        }
        val solver = BacktrackSolver(solvable.problem.bake())
        if (dryRunSolver) {
            errPrintln("solver dry-run:")
            errPrintln(solver.describe(params))
            return
        }
        runGeneric(solver, params, solvable, common, output, complete = true, deadline)
    }

    /** Print the resolved LS arm pool (`dry-run`), one line per arm, to stderr so the solution
     *  protocol on stdout stays clean. A null pool prints the curated catalog. */
    private fun printLsPool(pool: List<() -> LocalSearchRecipe>?) {
        val recipes = pool?.map { it() } ?: LocalSearchCatalog.ranked(Kind.COP)
        errPrintln("ls dry-run: ${recipes.size} arm(s)")
        for (r in recipes) {
            val sources = r.strategy.sources.joinToString(",") { it.source.id.label }
            val restart = r.strategy.schedule.restart?.let { it::class.simpleName } ?: "default"
            val temperature = r.strategy.schedule.temperature?.let { it::class.simpleName } ?: "none"
            errPrintln(
                "  ${r.label}: sources=[$sources] scoring=${r.strategy.scoring} " +
                    "acceptance=${r.strategy.acceptance} restart=$restart temperature=$temperature",
            )
        }
    }

    /** Print the resolved backtrack arm pool (`dry-run-solver`) to stderr, one `describe` block per
     *  arm. A null pool prints the credit-ordered curated catalog for [kind]. */
    private fun printBtPool(problem: Problem, pool: List<() -> BacktrackRecipe>?, kind: Kind) {
        val recipes = pool?.map { it() } ?: BacktrackCatalog.ranked(kind)
        errPrintln("solver dry-run: ${recipes.size} backtrack arm(s)")
        val solver = BacktrackSolver(problem.bake())
        for (r in recipes) {
            errPrintln("  ${r.label}:")
            for (line in solver.describe(r.build(0L, null)).lines()) errPrintln("    $line")
        }
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
        // deferred to the pipeline (stats.bakeElapsed) for a front-end problem, or ran at construction
        // (original.bakeElapsed) otherwise — only one is non-zero. `elapsed` is the whole presolve phase,
        // which for a deferred problem includes the step-0 bake, so the passes get the remainder.
        val stepZeroBake = stats?.bakeElapsed ?: Duration.ZERO
        val bake = original.bakeElapsed + stepZeroBake
        loadElapsedMs?.let { load ->
            errPrintln("  parse: ${(load - original.bakeElapsed.inWholeMilliseconds).coerceAtLeast(0)}ms")
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
        for (d in problem.intDomains) {
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
     * not load-bearing: real models put their columns either at the clamp or well under a million, so
     * nothing observed lands near it.
     */
    private fun openText(problem: Problem): String {
        var open = 0
        for (d in problem.intDomains) {
            val width = d.max - d.min
            if (width < 0L || width > OPEN_WIDTH) open++
        }
        return "$open of ${problem.intDomains.size}"
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
        mix: EngineMix,
        cancel: Cancellation,
    ) {
        // Default arm-pool size: an env override, else auto-tuned from the core count.
        val defaultArms = cliProp(CliKnobs.portfolioArms)?.toIntOrNull() ?: autoArms(cores)
        // `--lp CEILING` caps the portfolio's LP emphasis; the flag wins, then the `klause.lp`
        // env default, else AGGRESSIVE (uncapped — the pool spreads the LP intensity itself); `off`
        // disables LP across the pool.
        val lpCeiling = (common.lp ?: defaultLp())?.let {
            runCatching { LpConfig.parse(it) }.getOrElse { e -> usageError("--lp: ${e.message}") }
        } ?: LpConfig.AGGRESSIVE
        // For an LS-bearing pool, resolve the four-axis arm overrides (a `strategy=` base + per-axis
        // edits) before consuming the portfolio knobs from the same params. A null pool keeps the
        // curated catalog. `dry-run-solver` lists the resolved pool and exits without solving.
        val params = EngineParams(common.engineParams)
        val lsResolution = if (mix != EngineMix.BACKTRACK) {
            resolveLocalSearchRecipes(
                params,
            )
        } else {
            LsResolution(null, false)
        }
        if (lsResolution.dryRunSolver) {
            printLsPool(lsResolution.pool)
            return
        }
        val kind = if (solvable.optimize) Kind.COP else Kind.CSP
        // `--param bt-arm=label,label` pins a named backtrack arm pool, or the per-solver override
        // --params (var-/val-selector, luby, …) resolve a one-arm pool (a no-op for a pure-LS pool).
        val btPool = if (mix != EngineMix.LOCAL_SEARCH) resolveBtRecipes(params, kind) else null
        // A backtrack-only pool has no LS resolution to carry its dry-run flag, so consume it here.
        if (mix == EngineMix.BACKTRACK && params.bool("dry-run-solver") == true) {
            printBtPool(solvable.problem, btPool, kind)
            return
        }
        val scenario = buildPortfolioScenario(
            params,
            common.randomSeed,
            cores = cores,
            kind = kind,
            defaultEngine = mix,
            // `strategy=sweep` pins the worker count to the full recipe cross-product so the bandit
            // schedules every recipe; otherwise the env override or the auto-tuned default.
            defaultArms = lsResolution.forceArms ?: defaultArms,
            lpCeiling = lpCeiling,
            lsPool = lsResolution.pool,
            btPool = btPool,
            // Include the model's search-annotation arm in the backtrack pool when the model
            // carries one (a no-op for a pure-LS pool, which has no backtrack slot).
            annotationArm = solvable.annotatedBacktrackParams,
        )
        // Only a backtrack worker can prove UNSAT / optimality; a pure-LS pool reports UNKNOWN.
        val complete = scenario.engine != EngineMix.LOCAL_SEARCH
        val workers = PortfolioBuilder.build(
            solvable.problem.bake(),
            scenario,
            objective = solvable.linearObjective,
            lsObjective = solvable.lsObjective,
            definitionalSweep = solvable.definitionalSweep,
            onEvent = portfolioVerboseListener(common.verbose),
        )
        val t0 = nowMillis()
        // cores == 1 → the single-core bandit-scheduled SequentialPortfolio (it persists/shares learned
        // clauses across its segments and bandit-schedules the whole arm pool on one core); cores > 1 →
        // the concurrent Portfolio. Both are blocking (coroutine-free) and yield the same result types.
        val executor: PortfolioExecutor =
            if (scenario.cores == 1) SequentialPortfolio.exp3(workers) else parallelPortfolio(workers)
        executor.use {
            if (solvable.optimize) {
                // Stream every improving incumbent live (the MiniZinc `-i` contract): emit its solution
                // block the moment it is found, so a competition judge timestamps time-to-best from the
                // stream instead of seeing the best only at the terminating flush. Under `-s` also
                // attribute the improvement to its arm (`%%%klause-arm:`). The executor serialises this
                // callback, so the interleaved output stays ordered.
                var streamed = 0
                val onImprovement: (AttributedImprovement) -> Unit = { imp ->
                    val r = imp.result as MinimizeResult.WithSample
                    emit(output, solvable, r.sample)
                    streamed++
                    if (common.statistics) {
                        val obj = solvable.objectiveValue?.invoke(r.sample) ?: r.objectiveValue.toLong()
                        output.onImprovement(imp.workerLabel, obj, imp.elapsed.inWholeMilliseconds)
                    }
                }
                val result = it.minimize(cancel, onImprovement)
                emitMinimize(result, solvable, common, output, complete, t0, streamedCount = streamed)
            } else {
                emitSolve(it.solve(cancel), solvable, common, output, t0)
            }
        }
    }

    /** Emit a satisfaction verdict + the sole model (if any) + stats. */
    private fun emitSolve(r: SolveResult, solvable: Solvable, common: CommonOptions, output: OutputProtocol, t0: Long) {
        var produced = 0L
        when (r) {
            is SolveResult.Sat -> {
                emit(output, solvable, r.assignment)
                produced = 1L
                output.onComplete(Verdict.SATISFIABLE)
            }

            is SolveResult.Unsat -> output.onComplete(Verdict.UNSATISFIABLE)

            is SolveResult.Unknown -> output.onComplete(Verdict.UNKNOWN)
        }
        stats(common, output, solvable, r.stats, nowMillis() - t0, produced)
    }

    /** Emit an optimization verdict + the best model (if any) + stats. [complete] gates whether an
     *  Infeasible result reports UNSATISFIABLE (a complete pool proved it) or UNKNOWN (LS only). */
    private fun emitMinimize(
        r: MinimizeResult,
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        complete: Boolean,
        t0: Long,
        streamedCount: Int,
    ) {
        val alreadyStreamed = streamedCount > 0
        var produced = 0L
        var best: Sample? = null
        // Improving incumbents were streamed live (see the optimize branch above), so the best is
        // already on the stream; re-emit it only if nothing streamed (a defensive guard — the first
        // feasible solution is itself an improvement, so this should not happen when a sample exists).
        // [produced] carries the true streamed count into the `solutions=` statistic.
        when (r) {
            is MinimizeResult.Optimal -> {
                if (!alreadyStreamed) emit(output, solvable, r.sample)
                best = r.sample
                produced = maxOf(streamedCount.toLong(), 1L)
                output.onComplete(Verdict.OPTIMAL)
            }

            is MinimizeResult.BestFound -> {
                if (!alreadyStreamed) emit(output, solvable, r.sample)
                best = r.sample
                produced = maxOf(streamedCount.toLong(), 1L)
                output.onComplete(Verdict.BEST_FOUND)
            }

            is MinimizeResult.Infeasible ->
                output.onComplete(if (complete) Verdict.UNSATISFIABLE else Verdict.UNKNOWN)

            is MinimizeResult.Unknown -> output.onComplete(Verdict.UNKNOWN)
        }
        stats(common, output, solvable, withModelObjective(r.stats, solvable, best), nowMillis() - t0, produced)
    }

    private fun <P : SolverParams> runGeneric(
        solver: Solver<P>,
        params: P,
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        complete: Boolean,
        deadline: Long?,
    ) {
        if (solvable.optimize) {
            runOptimize(solver, params, solvable, common, output, complete, deadline)
        } else {
            runSatisfy(solver, params, solvable, common, output, complete, deadline)
        }
    }

    private fun <P : SolverParams> runSatisfy(
        solver: Solver<P>,
        params: P,
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        complete: Boolean,
        deadline: Long?,
    ) {
        val t0 = nowMillis()
        val limit = if (common.allSolutions) common.solutionCap ?: Long.MAX_VALUE else 1L

        // Single-solution satisfy (the standard invocation) goes through `solve`, whose result
        // carries the engine's SolveStats for `-s`; enumeration is only needed under `-a`/`-n`.
        if (limit == 1L) {
            when (val r = solver.solve(params)) {
                is SolveResult.Sat -> {
                    emit(output, solvable, r.assignment)
                    output.onComplete(Verdict.SATISFIABLE)
                    stats(common, output, solvable, r.stats, nowMillis() - t0, 1L)
                }

                is SolveResult.Unsat -> {
                    output.onComplete(if (complete) Verdict.UNSATISFIABLE else Verdict.UNKNOWN)
                    stats(common, output, solvable, r.stats, nowMillis() - t0, 0L)
                }

                is SolveResult.Unknown -> {
                    output.onComplete(Verdict.UNKNOWN)
                    stats(common, output, solvable, r.stats, nowMillis() - t0, 0L)
                }
            }
            return
        }

        var produced = 0L
        var timedOut = false
        for (sample in solver.enumerate(params)) {
            if (deadline != null && nowMillis() > deadline) {
                timedOut = true
                break
            }
            emit(output, solvable, sample)
            produced++
            if (produced >= limit) break
        }

        val verdict = when {
            timedOut && produced == 0L -> Verdict.UNKNOWN
            produced == 0L -> if (complete) Verdict.UNSATISFIABLE else Verdict.UNKNOWN
            else -> Verdict.SATISFIABLE
        }
        output.onComplete(verdict)
        stats(common, output, solvable, SolveStats.EMPTY, nowMillis() - t0, produced)
    }

    private fun <P : SolverParams> runOptimize(
        solver: Solver<P>,
        params: P,
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        complete: Boolean,
        deadline: Long?,
    ) {
        val optimizer = solver as? Optimizer<P>
        if (optimizer == null) {
            runOptimizeViaEnumerate(solver, params, solvable, common, output, complete, deadline)
            return
        }
        // Every backend minimises the canonical linear objective; the LS engine additionally
        // descends the model's per-move gradient view, threaded through its params by the caller.
        val objective: LinearObjective = requireNotNull(solvable.linearObjective)
        var produced = 0
        val t0 = nowMillis()
        var lastStats = SolveStats.EMPTY
        var bestSample: Sample? = null
        for (step in optimizer.improvements(objective, params)) {
            lastStats = step.stats
            when (step) {
                is MinimizeResult.WithSample -> {
                    emit(output, solvable, step.sample)
                    bestSample = step.sample
                    produced++
                    if (step is MinimizeResult.Optimal) {
                        output.onComplete(Verdict.OPTIMAL)
                        val oriented = withModelObjective(step.stats, solvable, step.sample)
                        stats(common, output, solvable, oriented, nowMillis() - t0, produced.toLong())
                        return
                    }
                }

                is MinimizeResult.Infeasible -> {
                    output.onComplete(Verdict.UNSATISFIABLE)
                    stats(common, output, solvable, step.stats, nowMillis() - t0, produced.toLong())
                    return
                }

                is MinimizeResult.Unknown -> {
                    output.onComplete(Verdict.UNKNOWN)
                    stats(common, output, solvable, step.stats, nowMillis() - t0, produced.toLong())
                    return
                }
            }
        }
        // Sequence ended without an Optimal verdict: optimality was NOT proven. Best-found
        // incumbents were already streamed; report BEST_FOUND (or UNKNOWN if nothing feasible).
        output.onComplete(if (produced == 0) Verdict.UNKNOWN else Verdict.BEST_FOUND)
        val oriented = withModelObjective(lastStats, solvable, bestSample)
        stats(common, output, solvable, oriented, nowMillis() - t0, produced.toLong())
    }

    private fun <P : SolverParams> runOptimizeViaEnumerate(
        solver: Solver<P>,
        params: P,
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        complete: Boolean,
        deadline: Long?,
    ) {
        val objVarId = solvable.objVarId
        if (objVarId == null) {
            // No single objective var to linear-search over and no native optimizer — cannot
            // optimise this instance with this engine.
            output.onComplete(Verdict.UNKNOWN)
            return
        }
        val t0 = nowMillis()
        var best: Sample? = null
        var bestObj = if (solvable.maximize) Long.MIN_VALUE else Long.MAX_VALUE
        for (sample in solver.enumerate(params)) {
            if (deadline != null && nowMillis() > deadline) break
            val v = sample.ints[objVarId]
            val improved = if (solvable.maximize) v > bestObj else v < bestObj
            if (improved) {
                bestObj = v
                best = sample
                emit(output, solvable, sample)
            }
        }
        val verdict = if (best == null) {
            val timedOut = deadline != null && nowMillis() > deadline
            if (complete && !timedOut) Verdict.UNSATISFIABLE else Verdict.UNKNOWN
        } else {
            Verdict.BEST_FOUND
        }
        output.onComplete(verdict)
        stats(common, output, solvable, SolveStats.EMPTY, nowMillis() - t0, if (best == null) 0L else 1L)
    }

    private fun emit(output: OutputProtocol, solvable: Solvable, sample: Sample) {
        output.onSolution(solvable.render(sample), solvable.objectiveValue?.invoke(sample))
    }

    private fun stats(
        common: CommonOptions,
        output: OutputProtocol,
        solvable: Solvable,
        s: SolveStats,
        ms: Long,
        solutions: Long,
    ) {
        // Fold the CLI-computed presolve summary into the solver's stats so `-s` reports it uniformly.
        if (common.statistics) output.onStatistics(s.copy(presolve = solvable.presolve), ms, solutions)
    }

    /** Re-express the LS incumbent objective in the model's orientation, reusing the same sign-corrected
     *  [Solvable.objectiveValue] that renders solutions and arm attribution. The engine records the
     *  incumbent in its internal "lower is better" frame (maximisation via a negated coefficient); the
     *  objective lambda reads the canonical objective variable in original units, which also reconciles
     *  the LS functional gradient view back to the linear objective. No-op for satisfy / infeasible
     *  runs (no incumbent) and non-MiniZinc modes without an objective lambda. */
    private fun withModelObjective(s: SolveStats, solvable: Solvable, sample: Sample?): SolveStats {
        if (sample == null || s.ls.incumbentObjective.isNaN()) return s
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
