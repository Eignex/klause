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
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.LpEmphasis
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.Cbls
import com.eignex.klause.solver.localsearch.strategy.TabuFilter
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
        // precedence) are dropped for a pure-LS engine (their ordering constraints hurt local
        // search); solutions are reconstructed at render time.
        val base = common.presolve?.let { PresolveConfig.parse(it) } ?: KlauseConfig.current.presolveConfig()
        val config = if (engine.pureLs) base.forLocalSearch() else base
        // Symmetry breaking collapses symmetric solutions, so disable it (via auto resolution) when
        // the run wants the full solution set: enumeration (`-a`) or a multi-solution cap (`-n N`),
        // unless we're optimizing (a single optimum, where symmetry breaking is sound).
        val solutionSetSensitive = !rawSolvable.optimize && (common.allSolutions || (common.solutionCap ?: 1L) > 1L)
        val solvable = rawSolvable.presolved(config, solutionSetSensitive)
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
                runBacktrack(solvable, common, output, useAnnotation = true, allowSelectors = false)
            }

            // Naked single backtrack, free search — the only engine that takes var-selector/
            // val-selector --params (for single-solver heuristic A/B).
            Engine.CP_SINGLE -> {
                rejectParallel(engine, cores, alt = Engine.CP)
                runBacktrack(solvable, common, output, useAnnotation = false, allowSelectors = true)
            }

            // Naked single local search — the only engine that takes the ls strategy --params
            // (tabu-tenure, pair-swap-budget, lambda, noise, max-flips).
            Engine.LS_SINGLE -> {
                rejectParallel(engine, cores, alt = Engine.LS)
                runLocalSearch(solvable, common, output)
            }

            // The parallel-capable portfolio engines: their mix is carried on the enum.
            Engine.CP, Engine.LS, Engine.MIXED ->
                runPortfolio(solvable, common, output, cores, requireNotNull(engine.mix))
        }
    }

    /** Reject `-p N>1` for a single-core engine; [alt], when given, is the parallel engine to suggest. */
    private fun rejectParallel(engine: Engine, cores: Int, alt: Engine?) {
        if (cores <= 1) return
        val hint = alt?.let { "; use '${it.id}' for a parallel pool" } ?: " (FD track); drop -p"
        usageError("engine '${engine.id}' is single-core$hint")
    }

    private fun deadlineCancellation(common: CommonOptions): Pair<Long?, Cancellation> {
        val deadline = common.timeLimitMs?.let { nowMillis() + it }
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
        useAnnotation: Boolean,
        allowSelectors: Boolean,
    ) {
        // Fixed seed for determinism (a null seed makes optimality proofs flakily blow the budget);
        // `-r` overrides. XCSP/SMT carry no annotation (null), so they fall back to this CDCL config.
        val annotated = if (useAnnotation) solvable.annotatedBacktrackParams else null
        val base = annotated ?: BacktrackParams(
            randomSeed = 1L,
            variableSelector = Vsids(),
            phaseSaving = true,
            lubyRestartBase = 100L,
            maxLearnedClauses = 20_000,
        )
        val (_, cancel) = deadlineCancellation(common)
        val params = applyBacktrackParams(
            base.copy(
                randomSeed = common.randomSeed ?: base.randomSeed,
                cancellation = cancel,
                onEvent = verboseListener(common.verbose),
            ),
            EngineParams(common.engineParams),
            allowSelectors = allowSelectors,
        )
        cliLogger(common.verbose).v {
            "engine cp: seed=${params.randomSeed} luby=${params.lubyRestartBase} maxLearned=${params.maxLearnedClauses}"
        }
        runGeneric(BacktrackSolver(solvable.problem), params, solvable, common, output, complete = true)
    }

    /** Naked single local search (the `ls-single` engine), configured by the ls strategy --params
     *  (tabu-tenure, pair-swap-budget, lambda, noise, max-flips). */
    private fun runLocalSearch(solvable: Solvable, common: CommonOptions, output: OutputProtocol) {
        val (params, setup) = applyLsParams(
            LocalSearchParams(randomSeed = common.randomSeed),
            EngineParams(common.engineParams),
        )
        val tabu = TabuFilter(tenure = setup.tabuTenure, aspiration = AspirationCriterion.OrImproving)
        val strategy = Cbls(noiseProbability = setup.noise, tabu = tabu)
        val solver = LocalSearchSolver(
            solvable.problem,
            strategy = strategy,
            optimizeStrategy = strategy,
            pairSwapBudget = setup.pairSwapBudget,
            definitionalSweep = solvable.definitionalSweep,
            perMoveInvariants = true,
        )
        val (_, cancel) = deadlineCancellation(common)
        val cblsParams = params.copy(
            costShaping = CostShaping.Linear(lambda = setup.lambda),
            cancellation = cancel,
            lsObjective = solvable.lsObjective,
            onEvent = verboseListener(common.verbose),
        )
        cliLogger(common.verbose).v {
            "engine ls-single: seed=${cblsParams.randomSeed} tabu=${setup.tabuTenure} noise=${setup.noise}"
        }
        runGeneric(solver, cblsParams, solvable, common, output, complete = false)
    }

    private fun runPortfolio(
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        cores: Int,
        mix: EngineMix,
    ) {
        // Default arm-pool size: an env override, else auto-tuned from the core count (#406).
        val defaultArms = cliProp("klause.fzn.portfolio.arms")?.toIntOrNull() ?: autoArms(cores)
        // #429: `--lp CEILING` caps the portfolio's LP emphasis; absent ⇒ AGGRESSIVE (uncapped — the
        // pool spreads the LP intensity itself), `off` disables LP across the pool.
        val lpCeiling = common.lp?.let {
            LpEmphasis.fromId(it) ?: usageError("--lp: off | conservative | default | aggressive")
        } ?: LpEmphasis.AGGRESSIVE
        val scenario = buildPortfolioScenario(
            EngineParams(common.engineParams),
            common.randomSeed,
            cores = cores,
            kind = if (solvable.optimize) Kind.COP else Kind.CSP,
            defaultEngine = mix,
            defaultArms = defaultArms,
            lpCeiling = lpCeiling,
        )
        val (_, cancel) = deadlineCancellation(common)
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
        when (r) {
            is MinimizeResult.Optimal -> {
                emit(output, solvable, r.sample)
                produced = 1L
                output.onComplete(Verdict.OPTIMAL)
            }

            is MinimizeResult.BestFound -> {
                emit(output, solvable, r.sample)
                produced = 1L
                output.onComplete(Verdict.BEST_FOUND)
            }

            is MinimizeResult.Infeasible ->
                output.onComplete(if (complete) Verdict.UNSATISFIABLE else Verdict.UNKNOWN)

            is MinimizeResult.Unknown -> output.onComplete(Verdict.UNKNOWN)
        }
        stats(common, output, r.stats, nowMillis() - t0, produced)
    }

    // --- generic per-engine satisfy / optimize ---

    private fun <P : SolverParams> runGeneric(
        solver: Solver<P>,
        params: P,
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        complete: Boolean,
    ) {
        if (solvable.optimize) {
            runOptimize(solver, params, solvable, common, output, complete)
        } else {
            runSatisfy(solver, params, solvable, common, output, complete)
        }
    }

    private fun <P : SolverParams> runSatisfy(
        solver: Solver<P>,
        params: P,
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        complete: Boolean,
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
        val deadline = common.timeLimitMs?.let { nowMillis() + it }
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
    ) {
        val optimizer = solver as? Optimizer<P>
        if (optimizer == null) {
            runOptimizeViaEnumerate(solver, params, solvable, common, output, complete)
            return
        }
        // Every backend minimises the canonical linear objective; the LS engine additionally
        // descends the model's per-move gradient view, threaded through its params by the caller.
        val objective: LinearObjective = requireNotNull(solvable.linearObjective)
        var produced = 0
        val t0 = nowMillis()
        var lastStats = SolveStats.EMPTY
        for (step in optimizer.improvements(objective, params)) {
            lastStats = step.stats
            when (step) {
                is MinimizeResult.WithSample -> {
                    emit(output, solvable, step.sample)
                    produced++
                    if (step is MinimizeResult.Optimal) {
                        output.onComplete(Verdict.OPTIMAL)
                        stats(common, output, step.stats, nowMillis() - t0, produced.toLong())
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
        stats(common, output, lastStats, nowMillis() - t0, produced.toLong())
    }

    private fun <P : SolverParams> runOptimizeViaEnumerate(
        solver: Solver<P>,
        params: P,
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        complete: Boolean,
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
        val deadline = common.timeLimitMs?.let { nowMillis() + it }
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
