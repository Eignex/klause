package com.eignex.klause.cli

import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchEvent
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.SolveStats
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.Vsids
import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.Cbls
import com.eignex.klause.solver.localsearch.strategy.TabuFilter

/**
 * The unified, mode-agnostic solve driver. Every CLI mode (MiniZinc, XCSP3, SMT-LIB) feeds a
 * [Solvable] + [CommonOptions] in, and all terminal reporting goes out through the mode's
 * [OutputProtocol] — so engine selection, the `-p N` worker split, deadline/cancellation,
 * `--param` application, CP-seeding, the satisfy/`-a`/`-n` enumeration and optimize streaming
 * loops live here exactly once instead of being duplicated per front-end.
 */
internal object SolveCore {

    fun solve(solvable: Solvable, common: CommonOptions, output: OutputProtocol) {
        val engine = (common.engine ?: cliProp("klause.fzn.engine") ?: "cp").lowercase()
        output.begin(solvable.optimize, solvable.maximize)

        // MiniZinc-standard `-p N` (parallelism): N > 1 means a portfolio of N workers. An
        // explicitly chosen engine picks the worker palette — `-e ls -p N` is a pure-LS pool,
        // `-e cp -p N` is N complete workers — otherwise the default mixed pool (~2:1 LS:bt,
        // at least one bt worker so UNSAT / optimality stay provable). `--param ls=/bt=` win.
        val threads = common.parallel ?: 1
        if (threads > 1) {
            val (ls, bt) = when (engine) {
                "ls", "localsearch", "local-search" -> threads to 0

                "cp", "backtrack", "bt" -> 0 to threads

                else -> {
                    val b = maxOf(1, threads / 3)
                    (threads - b) to b
                }
            }
            runPortfolio(solvable, common, output, defaultLs = ls, defaultBt = bt)
            return
        }
        when (engine) {
            "cp", "backtrack", "bt" -> runBacktrack(solvable, common, output)
            "ls", "localsearch", "local-search" -> runLocalSearch(solvable, common, output)
            "portfolio", "pf" -> runPortfolio(solvable, common, output)
            else -> usageError("unknown engine `$engine`; expected one of cp, ls, portfolio")
        }
    }

    private fun deadlineCancellation(common: CommonOptions): Pair<Long?, Cancellation> {
        val deadline = common.timeLimitMs?.let { nowMillis() + it }
        val cancel = if (deadline != null) Cancellation { nowMillis() > deadline } else Cancellation.Never
        return deadline to cancel
    }

    // --- single-engine paths ---

    private fun runBacktrack(solvable: Solvable, common: CommonOptions, output: OutputProtocol) {
        // Full CDCL setup under a FIXED seed for determinism (a null seed makes optimality
        // proofs flakily blow the budget); `-r` overrides. Honor the model's annotated search
        // (FlatZinc `solve :: *_search`) unless `-f` (free search) is set; XCSP/SMT carry no
        // annotations (null), so they always get this default CDCL config.
        val annotated = if (common.freeSearch) null else solvable.annotatedBacktrackParams
        val base = annotated ?: BacktrackParams(
            randomSeed = 1L,
            variableHeuristic = Vsids(),
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
        )
        cliLogger(common.verbose).v {
            "engine cp: seed=${params.randomSeed} luby=${params.lubyRestartBase} maxLearned=${params.maxLearnedClauses}"
        }
        runGeneric(BacktrackSolver(solvable.problem), params, solvable, common, output, complete = true)
    }

    private fun runLocalSearch(solvable: Solvable, common: CommonOptions, output: OutputProtocol) {
        val (params, setup) = applyLsParams(
            LocalSearchParams(randomSeed = common.randomSeed),
            EngineParams(common.engineParams),
        )
        val tabu = TabuFilter(tenure = setup.tabuTenure, aspiration = AspirationCriterion.OrImproving)
        val solver = LocalSearchSolver(
            solvable.problem,
            strategy = Cbls(tabu = tabu),
            optimizeStrategy = Cbls(tabu = tabu),
            pairSwapBudget = setup.pairSwapBudget,
            definitionalSweep = solvable.definitionalSweep,
            perMoveInvariants = true,
        )
        val (deadline, cancel) = deadlineCancellation(common)
        // CP-seeding (#65): OFF unless `--cp-seed`. A short backtrack solve warm-starts LS.
        val initial = if (common.cpSeed) cpFeasibleSeed(solvable.problem, deadline) else null
        val cblsParams = params.copy(
            costShaping = CostShaping.Linear(lambda = setup.lambda),
            cancellation = cancel,
            initialAssignment = initial,
            onEvent = verboseListener(common.verbose),
        )
        cliLogger(common.verbose).v {
            "engine ls: seed=${cblsParams.randomSeed} tabu-tenure=${setup.tabuTenure} lambda=${setup.lambda}"
        }
        runGeneric(solver, cblsParams, solvable, common, output, complete = false)
    }

    /** CP-seeding helper for `--cp-seed`: a short backtrack solve (≤ `klause.fzn.cpseed.ms`,
     *  default 2000ms, capped by `-t`) to find a feasible LS warm-start; null if none found. */
    private fun cpFeasibleSeed(problem: Problem, overallDeadline: Long?): Sample? {
        val cpMs = cliProp("klause.fzn.cpseed.ms")?.toLong() ?: 2000L
        var cpDeadline = nowMillis() + cpMs
        if (overallDeadline != null) cpDeadline = minOf(cpDeadline, overallDeadline)
        val r = BacktrackSolver(problem).solve(
            BacktrackParams(randomSeed = 1L, cancellation = Cancellation { nowMillis() > cpDeadline }),
        )
        return (r as? SolveResult.Sat)?.assignment
    }

    private fun runPortfolio(
        solvable: Solvable,
        common: CommonOptions,
        output: OutputProtocol,
        defaultLs: Int = cliProp("klause.fzn.portfolio.ls")?.toIntOrNull() ?: 4,
        defaultBt: Int = cliProp("klause.fzn.portfolio.bt")?.toIntOrNull() ?: 2,
    ) {
        val spec = buildPortfolioSpec(EngineParams(common.engineParams), common.randomSeed, defaultLs, defaultBt)
        val (_, cancel) = deadlineCancellation(common)
        // Only a backtrack worker can prove UNSAT / optimality; a pure-LS pool reports UNKNOWN.
        val complete = spec.backtrackWorkers > 0
        val portfolio = PortfolioBuilder.build(
            solvable.problem,
            spec,
            lsObjective = solvable.lsObjective,
            linearObjective = solvable.linearObjective,
            definitionalSweep = solvable.definitionalSweep,
            onEvent = portfolioVerboseListener(common.verbose),
        )
        val t0 = nowMillis()
        try {
            if (!solvable.optimize) {
                val r = runBlockingBridge { portfolio.solve(cancel) }
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
            } else {
                val r = runBlockingBridge { portfolio.minimize(cancel) }
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
        } finally {
            portfolio.close()
        }
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
        // LS descends a decomposed objective with a per-move gradient; complete backends keep
        // the linear form for bounding.
        val objective: Objective = if (solver is LocalSearchSolver) {
            solvable.lsObjective ?: requireNotNull(solvable.linearObjective)
        } else {
            requireNotNull(solvable.linearObjective)
        }
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
