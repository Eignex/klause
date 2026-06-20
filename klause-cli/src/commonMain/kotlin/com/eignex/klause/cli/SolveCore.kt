package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.portfolio.AttributedImprovement
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.SequentialPortfolio
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.backtrack.BacktrackPresets
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.lp.LpConfig
import com.eignex.klause.solver.localsearch.strategy.LsCatalog
import com.eignex.klause.solver.localsearch.strategy.LsRecipe
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.presolve.PresolveConfig
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats

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
        val config = if (engine.pureLs && common.presolve == null) base.forLocalSearch() else base
        // Symmetry breaking collapses symmetric solutions, so disable it (via auto resolution) when
        // the run wants the full solution set: enumeration (`-a`) or a multi-solution cap (`-n N`),
        // unless we're optimizing (a single optimum, where symmetry breaking is sound).
        val solutionSetSensitive = !rawSolvable.optimize && (common.allSolutions || (common.solutionCap ?: 1L) > 1L)
        // The `-t` wall-clock deadline is captured once, here, and shared by every phase: presolve, the
        // LP root build/solve inside the engine, and the search loop. Building it per-phase would let
        // each phase restart the clock and blow the limit cumulatively.
        val (deadline, cancel) = deadlineCancellation(common)
        val solvable = rawSolvable.presolved(config, solutionSetSensitive, cancel)
        cliLogger(common.verbose).v {
            val p0 = rawSolvable.problem
            val p1 = solvable.problem
            "presolve [${engine.id}]: factors ${p0.numFactors}→${p1.numFactors}, " +
                "ints ${p0.numIntVars}→${p1.numIntVars}, bools ${p0.numBoolVars}→${p1.numBoolVars}"
        }
        output.begin(solvable.optimize, solvable.maximize)

        // `-p N` is MiniZinc-standard parallelism = the **core** count (#406). The portfolio engines
        // (cp/mixed/ls) run sequentially at `-p1` and as a parallel pool at `-pN`. The two naked
        // engines (fixed, cp-single) are inherently single-core.
        val cores = common.parallel ?: 1
        when (engine) {
            // Naked single backtrack following the model's search annotation (FD track). The
            // annotation decides the heuristic, so per-solver selector --params are rejected.
            Engine.FIXED -> {
                rejectParallel(engine, cores, alt = null)
                runBacktrack(solvable, common, output, cancel, deadline, useAnnotation = true, allowSelectors = false)
            }

            // Naked single backtrack, free search — the only engine that takes var-selector/
            // val-selector --params (for single-solver heuristic A/B).
            Engine.CP_SINGLE -> {
                rejectParallel(engine, cores, alt = Engine.CP)
                runBacktrack(solvable, common, output, cancel, deadline, useAnnotation = false, allowSelectors = true)
            }

            // The parallel-capable portfolio engines: their mix is carried on the enum. `ls` resolves
            // a four-axis arm pool from its --params (a `strategy=` base plus per-axis edits); a single
            // resolved arm runs as a one-arm pool, subsuming the former naked single local search.
            Engine.CP, Engine.LS, Engine.MIXED ->
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
        val cancel = if (deadline != null) Cancellation { nowMillis() > deadline } else Cancellation.Never
        return deadline to cancel
    }

    // --- single-engine paths ---

    /** Naked single backtrack solve. [useAnnotation] follows the model's `solve :: *_search`
     *  annotation (the `fixed`/FD engine); otherwise a default free CDCL config. [allowSelectors]
     *  lets `var-selector`/`val-selector` --params through (only the `cp-single` engine). */
    private fun runBacktrack(
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        cancel: Cancellation,
        deadline: Long?,
        useAnnotation: Boolean,
        allowSelectors: Boolean,
    ) {
        // XCSP/SMT carry no annotation (null), so both naked backtrack engines fall back to
        // the conflict-driven preset base.
        // Seed remains unset unless `-r` (or `--param seed=`) pins one.
        val annotated = if (useAnnotation) solvable.annotatedBacktrackParams else null
        val base = annotated ?: BacktrackPresets.conflictDriven()
        // `--lp CEILING` selects the LP emphasis for the naked engine too (it powers the single-engine
        // LP-success measurement under `-s`); absent ⇒ keep the base config (LP off for naked CP).
        val lpConfig = common.lp?.let {
            runCatching { LpConfig.parse(it) }.getOrElse { e -> usageError("--lp: ${e.message}") }
        } ?: base.lpConfig
        val params = applyBacktrackParams(
            base.copy(
                randomSeed = common.randomSeed ?: base.randomSeed,
                cancellation = cancel,
                onEvent = verboseListener(common.verbose),
                lpConfig = lpConfig,
            ),
            EngineParams(common.engineParams),
            allowSelectors = allowSelectors,
        )
        cliLogger(common.verbose).v {
            "engine cp: seed=${params.randomSeed} luby=${params.lubyRestartBase} maxLearned=${params.maxLearnedClauses}"
        }
        runGeneric(BacktrackSolver(solvable.problem), params, solvable, common, output, complete = true, deadline)
    }

    /** Print the resolved LS arm pool (`dry-run`), one line per arm, to stderr so the solution
     *  protocol on stdout stays clean. A null pool prints the curated catalog. */
    private fun printLsPool(pool: List<() -> LsRecipe>?) {
        val recipes = pool?.map { it() } ?: LsCatalog.auto()
        errPrintln("ls dry-run: ${recipes.size} arm(s)")
        for (r in recipes) {
            val sources = r.strategy.sources.joinToString(",") { it.source.id.label }
            val restart = r.strategy.schedule.restart?.let { it::class.simpleName } ?: "default"
            errPrintln(
                "  ${r.label}: sources=[$sources] scoring=${r.strategy.scoring} " +
                    "acceptance=${r.strategy.acceptance} restart=$restart",
            )
        }
    }

    private fun runPortfolio(
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        cores: Int,
        mix: EngineMix,
        cancel: Cancellation,
    ) {
        // Default arm-pool size: an env override, else auto-tuned from the core count (#406).
        val defaultArms = cliProp(CliKnobs.portfolioArms)?.toIntOrNull() ?: autoArms(cores)
        // #429: `--lp CEILING` caps the portfolio's LP emphasis; absent ⇒ AGGRESSIVE (uncapped — the
        // pool spreads the LP intensity itself), `off` disables LP across the pool.
        val lpCeiling = common.lp?.let {
            runCatching { LpConfig.parse(it) }.getOrElse { e -> usageError("--lp: ${e.message}") }
        } ?: LpConfig.AGGRESSIVE
        // For an LS-bearing pool, resolve the four-axis arm overrides (a `strategy=` base + per-axis
        // edits) before consuming the portfolio knobs from the same params. A null pool keeps the
        // curated catalog. `dry-run` lists the resolved pool and exits without solving.
        val params = EngineParams(common.engineParams)
        val lsResolution = if (mix != EngineMix.BACKTRACK) resolveLsRecipes(params) else LsResolution(null, false)
        if (lsResolution.dryRun) {
            printLsPool(lsResolution.pool)
            return
        }
        val scenario = buildPortfolioScenario(
            params,
            common.randomSeed,
            cores = cores,
            kind = if (solvable.optimize) Kind.COP else Kind.CSP,
            defaultEngine = mix,
            defaultArms = defaultArms,
            lpCeiling = lpCeiling,
            lsPool = lsResolution.pool,
        )
        // Only a backtrack worker can prove UNSAT / optimality; a pure-LS pool reports UNKNOWN.
        val complete = scenario.engine != EngineMix.LOCAL_SEARCH
        val workers = PortfolioBuilder.build(
            solvable.problem,
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
                // Under `-s`, attribute each strict global improvement to its arm (a `%%%klause-arm:`
                // comment). Null otherwise, so the executor skips the serialising lock entirely.
                val onImprovement = if (common.statistics) attributionSink(solvable, output) else null
                emitMinimize(it.minimize(cancel, onImprovement), solvable, common, output, complete, t0)
            } else {
                emitSolve(it.solve(cancel), solvable, common, output, t0)
            }
        }
    }

    /** Build the `onImprovement` callback that renders one attribution line per incumbent, in the
     *  model's objective orientation. */
    private fun attributionSink(solvable: Solvable, output: OutputProtocol): (AttributedImprovement) -> Unit = { imp ->
        val r = imp.result as MinimizeResult.WithSample
        val obj = solvable.objectiveValue?.invoke(r.sample) ?: r.objectiveValue.toLong()
        output.onImprovement(imp.workerLabel, obj, imp.elapsed.inWholeMilliseconds)
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
        stats(common, output, r.stats, nowMillis() - t0, produced)
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
    ) {
        var produced = 0L
        var best: Sample? = null
        when (r) {
            is MinimizeResult.Optimal -> {
                emit(output, solvable, r.sample)
                best = r.sample
                produced = 1L
                output.onComplete(Verdict.OPTIMAL)
            }

            is MinimizeResult.BestFound -> {
                emit(output, solvable, r.sample)
                best = r.sample
                produced = 1L
                output.onComplete(Verdict.BEST_FOUND)
            }

            is MinimizeResult.Infeasible ->
                output.onComplete(if (complete) Verdict.UNSATISFIABLE else Verdict.UNKNOWN)

            is MinimizeResult.Unknown -> output.onComplete(Verdict.UNKNOWN)
        }
        stats(common, output, withModelObjective(r.stats, solvable, best), nowMillis() - t0, produced)
    }

    // --- generic per-engine satisfy / optimize ---

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
                    stats(common, output, r.stats, nowMillis() - t0, 1L)
                }

                is SolveResult.Unsat -> {
                    output.onComplete(if (complete) Verdict.UNSATISFIABLE else Verdict.UNKNOWN)
                    stats(common, output, r.stats, nowMillis() - t0, 0L)
                }

                is SolveResult.Unknown -> {
                    output.onComplete(Verdict.UNKNOWN)
                    stats(common, output, r.stats, nowMillis() - t0, 0L)
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
        stats(common, output, SolveStats.EMPTY, nowMillis() - t0, produced)
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
                        stats(common, output, oriented, nowMillis() - t0, produced.toLong())
                        return
                    }
                }

                is MinimizeResult.Infeasible -> {
                    output.onComplete(Verdict.UNSATISFIABLE)
                    stats(common, output, step.stats, nowMillis() - t0, produced.toLong())
                    return
                }

                is MinimizeResult.Unknown -> {
                    output.onComplete(Verdict.UNKNOWN)
                    stats(common, output, step.stats, nowMillis() - t0, produced.toLong())
                    return
                }
            }
        }
        // Sequence ended without an Optimal verdict: optimality was NOT proven. Best-found
        // incumbents were already streamed; report BEST_FOUND (or UNKNOWN if nothing feasible).
        output.onComplete(if (produced == 0) Verdict.UNKNOWN else Verdict.BEST_FOUND)
        val oriented = withModelObjective(lastStats, solvable, bestSample)
        stats(common, output, oriented, nowMillis() - t0, produced.toLong())
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
        var bestObj = if (solvable.maximize) Int.MIN_VALUE else Int.MAX_VALUE
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
        stats(common, output, SolveStats.EMPTY, nowMillis() - t0, if (best == null) 0L else 1L)
    }

    // --- helpers ---

    private fun emit(output: OutputProtocol, solvable: Solvable, sample: Sample) {
        output.onSolution(solvable.render(sample), solvable.objectiveValue?.invoke(sample))
    }

    private fun stats(common: CommonOptions, output: OutputProtocol, s: SolveStats, ms: Long, solutions: Long) {
        if (common.statistics) output.onStatistics(s, ms, solutions)
    }

    /** Re-express the LS incumbent objective in the model's orientation, reusing the same sign-corrected
     *  [Solvable.objectiveValue] that renders solutions and arm attribution. The engine records the
     *  incumbent in its internal "lower is better" frame (maximisation via a negated coefficient); the
     *  objective lambda reads the canonical objective variable in original units, which also reconciles
     *  the LS functional gradient view back to the linear objective. No-op for satisfy / infeasible
     *  runs (no incumbent) and non-MiniZinc modes without an objective lambda. */
    private fun withModelObjective(s: SolveStats, solvable: Solvable, sample: Sample?): SolveStats {
        if (sample == null || s.incumbentObjective.isNaN()) return s
        val objectiveValue = solvable.objectiveValue ?: return s
        return s.copy(incumbentObjective = objectiveValue(sample).toDouble())
    }
}

// --- verbose listeners (shared by every mode) ---

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
