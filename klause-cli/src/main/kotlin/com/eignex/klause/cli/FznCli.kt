package com.eignex.klause.cli

import com.eignex.klause.formats.flatzinc.FlatZincProgram
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.writeFlatZincSolution
import com.eignex.klause.formats.minizinc.OznApplier
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.maximizeInt
import com.eignex.klause.solver.minimizeInt
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import java.io.File
import kotlin.system.exitProcess

/**
 * Minimal MiniZinc-compatible FlatZinc path of the unified klause CLI.
 *
 * Invocation: `klause-cli [flags] file.fzn`. Reads the FlatZinc model, hands it to the
 * requested klause backend, prints solutions in MiniZinc's standard FZN output format:
 * each solution terminated by `----------`, the entire stream terminated by `==========`
 * for completed search, `=====UNSATISFIABLE=====` when no solution exists, or
 * `=====UNKNOWN=====` when the budget runs out before either is proven.
 *
 * Backend is chosen with `--engine NAME` (or `-e NAME`), or via the `klause.fzn.engine`
 * system property. Recognised names: `backtrack` (default), `ls` / `localsearch`,
 * `portfolio`. Each backend honors `-t` (time limit), `-r` (seed), and repeatable
 * `--param key=value` engine params (see [EngineParams]); the `-a` / `-n` flags apply to
 * the satisfy path. The MiniZinc-standard `-p N` (parallelism) routes to the portfolio
 * sized to N workers, with an explicitly chosen engine as the worker palette.
 */
internal fun runFzn(args: Array<String>) {
    // Translate env vars / system properties into the central config once, up front, and
    // install it as the process-wide ambient config so the compiler picks it up.
    val config = com.eignex.klause.config.installKlauseConfigFromEnv()
    val opts = parseFznArgs(args)
    val source = File(opts.fznPath).readText()
    // Unbounded `var int` declarations get a default domain. Resolution order:
    //   CLI flag → KlauseConfig (env var / system property) → built-in default.
    // Built-in defaults match Gecode/Chuffed.
    val unboundedLo = opts.unboundedIntLo ?: config.unboundedIntLo
    val unboundedHi = opts.unboundedIntHi ?: config.unboundedIntHi
    val program = parseFlatZinc(
        source = source,
        unboundedIntLo = unboundedLo,
        unboundedIntHi = unboundedHi,
    )
    val engine = opts.engine ?: System.getProperty("klause.fzn.engine") ?: "backtrack"
    dispatch(engine.lowercase(), program, opts)
}

/** Lazily-loaded .ozn applier when [FznOptions.oznPath] is set; lets klause render the
 *  human-readable MZN output natively (drop-in for MiniZinc's `solns2out`). */
private fun loadOznApplier(opts: FznOptions): OznApplier? =
    opts.oznPath?.let { OznApplier(File(it).readText()) }

/** Render one solution: prefer the .ozn applier when supplied; otherwise fall back to
 *  the standard FZN solution writer (the `--no-ozn` / `needsSolns2Out: true` path). */
private fun renderSolution(
    applier: OznApplier?, program: FlatZincProgram, sample: com.eignex.klause.solver.Sample,
): String = applier?.render(program, sample) ?: writeFlatZincSolution(program, sample)

private fun dispatch(engine: String, program: FlatZincProgram, opts: FznOptions) {
    // MiniZinc-standard `-p N` (parallelism, declared in klause.msc): N > 1 means a
    // portfolio of N workers. An explicitly chosen engine picks the worker palette —
    // `-e ls -p N` is a pure-LS pool (no CP dependency), `-e backtrack -p N` is N
    // complete workers sharing the objective bound — and otherwise the default mixed
    // pool is sized to N (≈2:1 LS:backtrack, at least one backtrack worker so UNSAT /
    // optimality stay provable). Explicit `--param ls=/bt=` still win over the split.
    val threads = opts.parallel ?: 1
    if (threads > 1) {
        val (ls, bt) = when (engine) {
            "ls", "localsearch", "local-search" -> threads to 0
            "backtrack", "bt" -> 0 to threads
            else -> {
                val b = maxOf(1, threads / 3)
                (threads - b) to b
            }
        }
        runWithPortfolio(program, opts, defaultLs = ls, defaultBt = bt)
        return
    }
    when (engine) {
        "backtrack", "bt" -> runWithBacktrack(program, opts)
        "ls", "localsearch", "local-search" -> runWithLocalSearch(program, opts)
        "portfolio", "pf" -> runWithPortfolio(program, opts)
        else -> {
            System.err.println("klause-cli: unknown engine `$engine`; expected one of " +
                "backtrack, ls, portfolio")
            exitProcess(2)
        }
    }
}

private fun runWithBacktrack(program: FlatZincProgram, opts: FznOptions) {
    // Default complete-search config when the model carries no `solve :: *_search(...)`: the
    // full CDCL setup — VSIDS + phase-saving + Luby restarts + LBD clause learning — under a
    // FIXED seed so the CLI is deterministic (a null seed makes optimality proofs flakily blow
    // the budget); `-r/--random-seed` still overrides. Applied to satisfaction *and*
    // optimization alike: the VSIDS/Luby × branch-and-bound regression (#47) is fixed in the
    // core ([BacktrackSolver.improvements] suppresses Luby restarts on the optimization path),
    // so one config serves both goals with no satisfy/optimize split.
    // `-f` (free search): ignore the model's search annotations and use the CLI default.
    val annotated = if (opts.freeSearch) null else program.defaultBacktrackParams
    val base = annotated ?: BacktrackParams(
        randomSeed = 1L,
        variableHeuristic = com.eignex.klause.solver.backtrack.Vsids(),
        phaseSaving = true,
        lubyRestartBase = 100L,
        maxLearnedClauses = 20_000,
    )
    // Honor `-t` inside the engine, not just between yielded solutions: without a
    // cancellation a backtrack run that never yields (hard UNSAT proof, stuck optimality
    // proof) would ignore the time limit entirely.
    val deadline = opts.timeLimitMs?.let { System.currentTimeMillis() + it }
    val cancellation = if (deadline != null)
        com.eignex.klause.solver.Cancellation { System.currentTimeMillis() > deadline }
    else com.eignex.klause.solver.Cancellation.Never
    val params = applyBacktrackParams(
        base.copy(randomSeed = opts.randomSeed ?: base.randomSeed, cancellation = cancellation),
        EngineParams(opts.engineParams),
    )
    runGeneric(BacktrackSolver(program.problem), params, program, opts, complete = true)
}

private fun runWithLocalSearch(program: FlatZincProgram, opts: FznOptions) {
    val (params, setup) = applyLsParams(LocalSearchParams(randomSeed = opts.randomSeed), EngineParams(opts.engineParams))
    val tabu = com.eignex.klause.solver.localsearch.strategy.TabuFilter(
        tenure = setup.tabuTenure,
        aspiration = com.eignex.klause.solver.localsearch.strategy.AspirationCriterion.OrImproving,
    )
    // CBLS throughout — both the satisfy fight (`strategy`) and the objective descent
    // (`optimizeStrategy`, unified). A bench sweep (FocusedLs/probSAT vs CBLS over planted
    // random 3-SAT and CP-shaped satisfaction instances) found CBLS at least as good as
    // probSAT on solve-rate everywhere, including pure clausal SAT — and far more robust at
    // scale — so there is no problem shape where routing satisfy to probSAT helps. The
    // library default leaves these alone for backward-compat; the CLI is one-shot per
    // invocation, so it picks the across-the-board winner. (FocusedLs/probSAT/SA remain in
    // the multi-core portfolio for trajectory diversity, just not as the single default.)
    val solver = com.eignex.klause.solver.localsearch.LocalSearchSolver(
        program.problem,
        strategy = com.eignex.klause.solver.localsearch.strategy.Cbls(tabu = tabu),
        optimizeStrategy = com.eignex.klause.solver.localsearch.strategy.Cbls(tabu = tabu),
        pairSwapBudget = setup.pairSwapBudget,
    )
    // CBLS scores moves by `Σ weight·Δviolated + λ·Δobjective`. Without a non-zero λ at
    // the params level the objective contribution is zero and the strategy never feels
    // pressure to descend. Linear shaping with λ=1.0 lets the constraint and objective
    // gradients meet at comparable magnitudes; tune in problem-specific harness if needed.
    // Honor -t for the LS backend by installing a deadline cancellation into the params.
    // The native optimize (streaming branch-and-bound) path doesn't poll the wall clock
    // itself, so without this it would run unbounded on instances it can't close; the LS
    // engine checks `cancellation` inside its flip loop, so this stops both the satisfy
    // fight and the objective descent mid-search at the budget.
    val deadline = opts.timeLimitMs?.let { System.currentTimeMillis() + it }
    val cancellation = if (deadline != null)
        com.eignex.klause.solver.Cancellation { System.currentTimeMillis() > deadline }
    else com.eignex.klause.solver.Cancellation.Never
    // CP-seeding (#65): OFF unless `--cp-seed` is passed. When on, a short backtrack solve finds
    // a feasible point that warm-starts LS (the #54 misses reach feasibility trivially under CP
    // but never under cold LS). EXPLICIT OPT-IN: the default path leaves initialAssignment null,
    // so the shipped pure-LS entry point stays free of any CP dependency.
    val initial = if (opts.cpSeed) cpFeasibleSeed(program, deadline) else null
    val cblsParams = params.copy(
        costShaping = com.eignex.klause.solver.localsearch.CostShaping.Linear(lambda = setup.lambda),
        cancellation = cancellation,
        initialAssignment = initial,
    )
    runGeneric(solver, cblsParams, program, opts, complete = false)
}

/**
 * CP-seeding helper for the `--cp-seed` opt-in (#65): run the backtrack solver for up to
 * `-Dklause.fzn.cpseed.ms` (default 2000ms, capped by any `-t` deadline) to find a *feasible*
 * assignment, returned as an LS warm-start. Null when CP doesn't reach feasibility in its slice
 * (LS then runs cold). Only reached when the explicit `--cp-seed` flag is set.
 */
private fun cpFeasibleSeed(program: FlatZincProgram, overallDeadline: Long?): Sample? {
    val cpMs = System.getProperty("klause.fzn.cpseed.ms")?.toLong() ?: 2000L
    var cpDeadline = System.currentTimeMillis() + cpMs
    if (overallDeadline != null) cpDeadline = minOf(cpDeadline, overallDeadline)
    val r = BacktrackSolver(program.problem).solve(
        BacktrackParams(
            randomSeed = 1L,
            cancellation = com.eignex.klause.solver.Cancellation { System.currentTimeMillis() > cpDeadline },
        ),
    )
    return (r as? com.eignex.klause.solver.SolveResult.Sat)?.assignment
}

/**
 * Unified per-engine entry: dispatches between satisfy and optimize on the solve goal.
 *
 * [complete] is true only for solvers that exhaustively search (here: backtrack):
 * for them, an enumeration that ends without a solution *proves* unsatisfiability. The local-
 * search backend is incomplete — it exhausts a flip/restart budget, never the solution space —
 * so a fruitless run is `UNKNOWN`, never `UNSATISFIABLE`.
 */
/**
 * Multi-core portfolio engine: a [com.eignex.klause.portfolio.Portfolio] of diverse local-search
 * workers and/or backtrack workers, racing on satisfaction and sharing the objective bound on
 * optimisation. Worker counts come from `-Dklause.fzn.portfolio.ls` / `.bt` (defaults 4 / 2).
 *
 * A pure-LS pool (`-Dklause.fzn.portfolio.bt=0`) involves no CP and never seeds the LS — a
 * self-contained local-search pool with no CP dependency. The hybrid default (bt>0) is for
 * general use.
 */
/**
 * Build the two per-worker objective representations for a portfolio (#63): the functional
 * (gradient-bearing) objective the LS workers descend, and the [com.eignex.klause.solver.LinearObjective]
 * the backtrack workers bound-prune on. Returns `(null, null)` for satisfaction models. They stay
 * comparable because both score the same FlatZinc objective var.
 */
private fun portfolioObjectives(
    program: FlatZincProgram,
): Pair<com.eignex.klause.solver.Objective?, com.eignex.klause.solver.Objective?> {
    val (objName, maximize) = when (val solve = program.solve) {
        is SolveDirective.Minimize -> solve.objVar to false
        is SolveDirective.Maximize -> solve.objVar to true
        is SolveDirective.Satisfy -> return null to null
    }
    val objVarId = program.intVarsByName[objName]
        ?: error("objective variable '$objName' not found in int var map")
    val linear = if (maximize) program.problem.maximizeInt(objVarId)
                 else program.problem.minimizeInt(objVarId)
    return (program.lsObjective ?: linear) to linear
}

private fun runWithPortfolio(
    program: FlatZincProgram,
    opts: FznOptions,
    defaultLs: Int = System.getProperty("klause.fzn.portfolio.ls")?.toIntOrNull() ?: 4,
    defaultBt: Int = System.getProperty("klause.fzn.portfolio.bt")?.toIntOrNull() ?: 2,
) {
    val spec = buildPortfolioSpec(EngineParams(opts.engineParams), opts.randomSeed, defaultLs, defaultBt)
    val deadline = opts.timeLimitMs?.let { System.currentTimeMillis() + it }
    val cancel = if (deadline != null)
        com.eignex.klause.solver.Cancellation { System.currentTimeMillis() > deadline }
    else com.eignex.klause.solver.Cancellation.Never
    val applier = loadOznApplier(opts)
    // Only a backtrack worker can *prove* UNSAT / optimality; a pure-LS pool reports UNKNOWN.
    val complete = spec.backtrackWorkers > 0
    // Build per-worker objectives up front (#63): the LS workers descend the functional/gradient
    // objective, the backtrack workers bound the linear one. For satisfy there is no objective —
    // both stay null and the portfolio is solve-only.
    val (lsObjective, linearObjective) = portfolioObjectives(program)
    val portfolio = com.eignex.klause.portfolio.PortfolioBuilder.build(
        program.problem, spec, lsObjective = lsObjective, linearObjective = linearObjective,
    )
    try {
        when (val solve = program.solve) {
            is SolveDirective.Satisfy -> {
                when (val r = kotlinx.coroutines.runBlocking { portfolio.solve(cancel) }) {
                    is com.eignex.klause.solver.SolveResult.Sat -> {
                        print(renderSolution(applier, program, r.assignment)); println("==========")
                    }
                    is com.eignex.klause.solver.SolveResult.Unsat -> println("=====UNSATISFIABLE=====")
                    else -> println("=====UNKNOWN=====")
                }
            }
            is SolveDirective.Minimize, is SolveDirective.Maximize -> {
                // Per-worker objectives were wired into the builder above (#63): each worker
                // streams against its own representation; the portfolio only shares the scalar
                // bound. No single objective is passed to minimize any more.
                when (val r = kotlinx.coroutines.runBlocking { portfolio.minimize(cancel) }) {
                    is MinimizeResult.Optimal -> {
                        print(renderSolution(applier, program, r.sample)); println("==========")
                    }
                    is MinimizeResult.BestFound -> print(renderSolution(applier, program, r.sample))
                    is MinimizeResult.Infeasible ->
                        println(if (complete) "=====UNSATISFIABLE=====" else "=====UNKNOWN=====")
                    is MinimizeResult.Unknown -> println("=====UNKNOWN=====")
                }
            }
        }
    } finally {
        portfolio.close()
    }
}

private fun <P : SolverParams> runGeneric(
    solver: Solver<P>,
    params: P,
    program: FlatZincProgram,
    opts: FznOptions,
    complete: Boolean,
) {
    when (val solve = program.solve) {
        is SolveDirective.Satisfy -> runSatisfy(solver, params, program, opts, complete)
        is SolveDirective.Minimize, is SolveDirective.Maximize ->
            runOptimize(solver, params, program, solve, opts, complete)
    }
}

private fun <P : SolverParams> runSatisfy(
    solver: Solver<P>,
    params: P,
    program: FlatZincProgram,
    opts: FznOptions,
    complete: Boolean,
) {
    val applier = loadOznApplier(opts)
    val limit = if (opts.allSolutions) opts.solutionCap ?: Long.MAX_VALUE else 1L
    var produced = 0L
    val deadline = opts.timeLimitMs?.let { System.currentTimeMillis() + it }

    for (sample in solver.enumerate(params)) {
        if (deadline != null && System.currentTimeMillis() > deadline) {
            println("=====UNKNOWN=====")
            return
        }
        print(renderSolution(applier, program, sample))
        produced++
        if (produced >= limit) break
    }

    if (produced == 0L) {
        // No solution found: only a complete search that ran to completion proves
        // unsatisfiability. An incomplete (local-search) budget exhaustion — or a `-t`
        // cancellation that emptied the enumeration — can only report UNKNOWN.
        val timedOut = deadline != null && System.currentTimeMillis() > deadline
        println(if (complete && !timedOut) "=====UNSATISFIABLE=====" else "=====UNKNOWN=====")
    } else {
        println("==========")
    }
}

private fun <P : SolverParams> runOptimize(
    solver: Solver<P>,
    params: P,
    program: FlatZincProgram,
    solve: SolveDirective,
    opts: FznOptions,
    complete: Boolean,
) {
    val (objName, maximize) = when (solve) {
        is SolveDirective.Minimize -> solve.objVar to false
        is SolveDirective.Maximize -> solve.objVar to true
        else -> error("unreachable")
    }
    val objVarId = program.intVarsByName[objName]
        ?: error("objective variable '$objName' not found in int var map")

    val optimizer = solver as? Optimizer<P>
    if (optimizer == null) {
        // No native optimization: fall back to streaming linear-search-over-enumerate.
        runOptimizeViaEnumerate(solver, params, program, objVarId, maximize, opts, complete)
        return
    }
    val applier = loadOznApplier(opts)
    // Streaming branch-and-bound: yield each improving incumbent, then a terminal verdict.
    val linear = if (maximize) program.problem.maximizeInt(objVarId)
                 else program.problem.minimizeInt(objVarId)
    // Local search descends a *decomposed* objective only with a per-move gradient to the
    // decision vars; use the functional objective (cone of `defines_var` aux vars) when the
    // model provides one. Complete backends keep the LinearObjective (needed for bounding).
    val objective: com.eignex.klause.solver.Objective =
        if (solver is com.eignex.klause.solver.localsearch.LocalSearchSolver) (program.lsObjective ?: linear)
        else linear
    var produced = 0
    for (step in optimizer.improvements(objective, params)) {
        when (step) {
            is MinimizeResult.WithSample -> {
                print(renderSolution(applier, program, step.sample))
                produced++
                if (step is MinimizeResult.Optimal) {
                    println("==========")
                    return
                }
            }
            is MinimizeResult.Infeasible -> {
                println("=====UNSATISFIABLE=====")
                return
            }
            is MinimizeResult.Unknown -> {
                println("=====UNKNOWN=====")
                return
            }
        }
    }
    // Sequence ended without an Optimal verdict: optimality was NOT proven (the LS backend
    // never proves it; branch-and-bound only falls through here on budget/cancellation). The
    // best-found incumbents have already been streamed, each terminated by `----------`; we
    // must not print `==========`, which would falsely claim the last incumbent is optimal.
    // Only signal UNKNOWN when nothing feasible was found at all.
    if (produced == 0) println("=====UNKNOWN=====")
}

private fun <P : SolverParams> runOptimizeViaEnumerate(
    solver: Solver<P>,
    params: P,
    program: FlatZincProgram,
    objVarId: Int,
    maximize: Boolean,
    opts: FznOptions,
    complete: Boolean,
) {
    val applier = loadOznApplier(opts)
    var best: Sample? = null
    var bestObj = if (maximize) Int.MIN_VALUE else Int.MAX_VALUE
    val deadline = opts.timeLimitMs?.let { System.currentTimeMillis() + it }
    for (sample in solver.enumerate(params)) {
        if (deadline != null && System.currentTimeMillis() > deadline) break
        val v = sample.ints[objVarId]
        val improved = if (maximize) v > bestObj else v < bestObj
        if (improved) {
            bestObj = v
            best = sample
            print(renderSolution(applier, program, sample))
        }
    }
    if (best == null) {
        // Nothing feasible found. A complete search that ran to completion proves
        // unsatisfiability; a deadline hit or an incomplete (LS) budget exhaustion is UNKNOWN.
        val timedOut = deadline != null && System.currentTimeMillis() > deadline
        println(if (complete && !timedOut) "=====UNSATISFIABLE=====" else "=====UNKNOWN=====")
    } else {
        println("==========")
    }
}

private data class FznOptions(
    val fznPath: String,
    val engine: String?,
    val allSolutions: Boolean,
    val solutionCap: Long?,
    val timeLimitMs: Long?,
    val randomSeed: Long?,
    val verbose: Boolean,
    val statistics: Boolean,
    /** Optional `.ozn` file rendered by klause's native applier in place of MiniZinc's
     *  `solns2out`. When `null`, the FZN-format `name = value;` output is emitted. */
    val oznPath: String?,
    /** Lower bound for unbounded `var int` declarations (FlatZinc auxiliaries without an
     *  explicit range). `null` falls back to the `KLAUSE_FZN_UNBOUNDED_INT_LO` env var
     *  and then the built-in default. CLI flag: `--unbounded-int-lo N`. */
    val unboundedIntLo: Int?,
    /** Upper bound counterpart to [unboundedIntLo]. Flag: `--unbounded-int-hi N`. */
    val unboundedIntHi: Int?,
    /** `--cp-seed`: hybrid CP-seeding for the `ls` engine (#65) — a short backtrack solve finds a
     *  feasible point that warm-starts LS. Default false — the default keeps the pure-LS path free
     *  of any CP dependency; CP-seeding is strictly an explicit opt-in. */
    val cpSeed: Boolean,
    /** Raw repeatable `--param key=value` engine params; interpreted per engine (see [EngineParams]). */
    val engineParams: List<String>,
    /** MiniZinc-standard `-p N`: number of parallel workers; N > 1 routes to the portfolio. */
    val parallel: Int?,
    /** MiniZinc-standard `-f`: ignore the model's `solve :: *_search(...)` strategy and use
     *  the engine's own default search. (Challenge FREE/PAR classes require accepting it.) */
    val freeSearch: Boolean,
)

/**
 * Parses the MiniZinc-standard FZN solver flags we claim in `klause.msc` (-a, -n, -s, -v,
 * -t, -r, -p, -i, -f) plus our `--engine` / `-e` selector and repeatable `--param key=value`
 * engine params. Unknown flags are tolerated (printed to
 * stderr) to stay forward-compatible with MiniZinc additions we don't recognise.
 */
private fun parseFznArgs(args: Array<String>): FznOptions {
    var engine: String? = null
    var allSolutions = false
    var solutionCap: Long? = null
    var timeLimitMs: Long? = null
    var randomSeed: Long? = null
    var verbose = false
    var statistics = false
    var fznPath: String? = null
    var oznPath: String? = null
    var unboundedIntLo: Int? = null
    var unboundedIntHi: Int? = null
    var cpSeed = false
    var freeSearch = false
    val engineParams = mutableListOf<String>()
    var parallel: Int? = null
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-a", "--all-solutions" -> { allSolutions = true; i++ }
            // Improving incumbents are streamed on the optimization path unconditionally,
            // which is exactly `-i` semantics — accept the flag as a no-op.
            "-i", "--intermediate", "--intermediate-solutions" -> i++
            "-f", "--free-search" -> { freeSearch = true; i++ }
            "-n" -> { solutionCap = args[++i].toLong(); i++ }
            "-s", "--statistics" -> { statistics = true; i++ }
            "-v", "--verbose" -> { verbose = true; i++ }
            "-t", "--time-limit" -> { timeLimitMs = args[++i].toLong(); i++ }
            "-r" -> { randomSeed = args[++i].toLong(); i++ }
            "-e", "--engine" -> { engine = args[++i]; i++ }
            "--ozn" -> { oznPath = args[++i]; i++ }
            "--unbounded-int-lo" -> { unboundedIntLo = args[++i].toInt(); i++ }
            "--unbounded-int-hi" -> { unboundedIntHi = args[++i].toInt(); i++ }
            "--cp-seed" -> { cpSeed = true; i++ }
            "--param" -> { engineParams.add(args[++i]); i++ }
            "-p" -> { parallel = args[++i].toInt(); i++ }
            else -> {
                if (a.startsWith("-")) {
                    System.err.println("klause-cli: ignoring unknown flag $a")
                    i++
                } else {
                    if (fznPath != null) error("multiple FZN paths supplied: $fznPath, $a")
                    fznPath = a
                    i++
                }
            }
        }
    }
    val path = fznPath ?: run {
        System.err.println("usage: klause-cli [-e engine] [flags] file.fzn")
        exitProcess(2)
    }
    return FznOptions(path, engine, allSolutions, solutionCap, timeLimitMs, randomSeed, verbose, statistics, oznPath, unboundedIntLo, unboundedIntHi, cpSeed, engineParams, parallel, freeSearch)
}
