package com.eignex.klause.fzn

import com.eignex.klause.formats.flatzinc.FlatZincProgram
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.writeFlatZincSolution
import com.eignex.klause.formats.minizinc.OznApplier
import com.eignex.klause.logicng.LogicNGParams
import com.eignex.klause.logicng.LogicNGSolver
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.maximizeInt
import com.eignex.klause.solver.minimizeInt
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.brute.BruteForceParams
import com.eignex.klause.solver.brute.BruteForceSolver
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import java.io.File
import kotlin.system.exitProcess

/**
 * Minimal MiniZinc-compatible FlatZinc CLI.
 *
 * Invocation: `klause-fzn [flags] file.fzn`. Reads the FlatZinc model, hands it to the
 * requested klause backend, prints solutions in MiniZinc's standard FZN output format:
 * each solution terminated by `----------`, the entire stream terminated by `==========`
 * for completed search, `=====UNSATISFIABLE=====` when no solution exists, or
 * `=====UNKNOWN=====` when the budget runs out before either is proven.
 *
 * Backend is chosen with `--engine NAME` (or `-e NAME`), or via the `klause.fzn.engine`
 * system property. Recognised names: `backtrack` (default), `ls` / `localsearch`,
 * `logicng`, `brute`. Each backend honors `-t` (time limit) and `-r` (seed); the
 * `-a` / `-n` flags apply to the satisfy path.
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)
    val source = File(opts.fznPath).readText()
    // Unbounded `var int` declarations get a default domain. Resolution order:
    //   CLI flag → env var → built-in default.
    // The env-var names mirror the CLI flag names so a `KLAUSE_FZN_UNBOUNDED_INT_LO`
    // export reaches in-process callers too. Built-in defaults match Gecode/Chuffed.
    val unboundedLo = opts.unboundedIntLo
        ?: System.getenv("KLAUSE_FZN_UNBOUNDED_INT_LO")?.toIntOrNull()
        ?: com.eignex.klause.formats.flatzinc.DEFAULT_UNBOUNDED_INT_LO
    val unboundedHi = opts.unboundedIntHi
        ?: System.getenv("KLAUSE_FZN_UNBOUNDED_INT_HI")?.toIntOrNull()
        ?: com.eignex.klause.formats.flatzinc.DEFAULT_UNBOUNDED_INT_HI
    val program = parseFlatZinc(
        source = source,
        unboundedIntLo = unboundedLo,
        unboundedIntHi = unboundedHi,
    )
    val engine = opts.engine ?: System.getProperty("klause.fzn.engine") ?: "backtrack"
    dispatch(engine.lowercase(), program, opts)
}

/** Lazily-loaded .ozn applier when [Options.oznPath] is set; lets klause render the
 *  human-readable MZN output natively (drop-in for MiniZinc's `solns2out`). */
private fun loadOznApplier(opts: Options): OznApplier? =
    opts.oznPath?.let { OznApplier(File(it).readText()) }

/** Render one solution: prefer the .ozn applier when supplied; otherwise fall back to
 *  the standard FZN solution writer (the `--no-ozn` / `needsSolns2Out: true` path). */
private fun renderSolution(
    applier: OznApplier?, program: FlatZincProgram, sample: com.eignex.klause.solver.Sample,
): String = applier?.render(program, sample) ?: writeFlatZincSolution(program, sample)

private fun dispatch(engine: String, program: FlatZincProgram, opts: Options) {
    when (engine) {
        "backtrack", "bt" -> runWithBacktrack(program, opts)
        "ls", "localsearch", "local-search" -> runWithLocalSearch(program, opts)
        "logicng" -> runWithLogicNG(program, opts)
        "brute", "bruteforce", "brute-force" -> runWithBrute(program, opts)
        else -> {
            System.err.println("klause-fzn: unknown engine `$engine`; expected one of " +
                "backtrack, ls, logicng, brute")
            exitProcess(2)
        }
    }
}

private fun runWithBacktrack(program: FlatZincProgram, opts: Options) {
    val base = program.defaultBacktrackParams ?: BacktrackParams()
    val params = base.copy(randomSeed = opts.randomSeed ?: base.randomSeed)
    runGeneric(BacktrackSolver(program.problem), params, program, opts)
}

private fun runWithLocalSearch(program: FlatZincProgram, opts: Options) {
    val params = LocalSearchParams(randomSeed = opts.randomSeed)
    // CLI-side defaults for the LS backend: keep adaptive probSAT for satisfy-mode (best on
    // pure-SAT shape) but switch to CBLS for minimize-mode (better on the
    // decomposed CP shape that MiniZinc-Challenge instances produce — many small linear
    // constraints with uneven difficulty). The library default leaves optimizeStrategy
    // null for backward-compat with sessions that share weights across calls; the CLI is
    // a one-shot per invocation so it picks the SOTA-for-CP default.
    val solver = com.eignex.klause.solver.localsearch.LocalSearchSolver(
        program.problem,
        optimizeStrategy = com.eignex.klause.solver.localsearch.strategy.Cbls(
            tabu = com.eignex.klause.solver.localsearch.strategy.TabuFilter(
                tenure = 10,
                aspiration = com.eignex.klause.solver.localsearch.strategy.AspirationCriterion.OrImproving,
            ),
        ),
        pairSwapBudget = 1024,
    )
    // CBLS scores moves by `Σ weight·Δviolated + λ·Δobjective`. Without a non-zero λ at
    // the params level the objective contribution is zero and the strategy never feels
    // pressure to descend. Linear shaping with λ=1.0 lets the constraint and objective
    // gradients meet at comparable magnitudes; tune in problem-specific harness if needed.
    val cblsParams = params.copy(costShaping = com.eignex.klause.solver.localsearch.CostShaping.Linear(lambda = 1.0))
    runGeneric(solver, cblsParams, program, opts)
}

private fun runWithLogicNG(program: FlatZincProgram, opts: Options) {
    val params = LogicNGParams(
        randomSeed = opts.randomSeed,
        timeoutMillis = opts.timeLimitMs,
    )
    runGeneric(LogicNGSolver(program.problem), params, program, opts)
}

private fun runWithBrute(program: FlatZincProgram, opts: Options) {
    val params = BruteForceParams(randomSeed = opts.randomSeed)
    runGeneric(BruteForceSolver(program.problem), params, program, opts)
}

/** Unified per-engine entry: dispatches between satisfy and optimize on the solve goal. */
private fun <P : SolverParams> runGeneric(
    solver: Solver<P>,
    params: P,
    program: FlatZincProgram,
    opts: Options,
) {
    when (val solve = program.solve) {
        is SolveDirective.Satisfy -> runSatisfy(solver, params, program, opts)
        is SolveDirective.Minimize, is SolveDirective.Maximize ->
            runOptimize(solver, params, program, solve, opts)
    }
}

private fun <P : SolverParams> runSatisfy(
    solver: Solver<P>,
    params: P,
    program: FlatZincProgram,
    opts: Options,
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
        println("=====UNSATISFIABLE=====")
    } else {
        println("==========")
    }
}

private fun <P : SolverParams> runOptimize(
    solver: Solver<P>,
    params: P,
    program: FlatZincProgram,
    solve: SolveDirective,
    opts: Options,
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
        runOptimizeViaEnumerate(solver, params, program, objVarId, maximize, opts)
        return
    }
    val applier = loadOznApplier(opts)
    // Streaming branch-and-bound: yield each improving incumbent, then a terminal verdict.
    val objective = if (maximize) program.problem.maximizeInt(objVarId)
                    else program.problem.minimizeInt(objVarId)
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
    // Sequence ended without a terminal verdict: best-found stands, no optimality proven.
    if (produced == 0) println("=====UNKNOWN=====") else println("==========")
}

private fun <P : SolverParams> runOptimizeViaEnumerate(
    solver: Solver<P>,
    params: P,
    program: FlatZincProgram,
    objVarId: Int,
    maximize: Boolean,
    opts: Options,
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
        if (deadline != null && System.currentTimeMillis() > deadline) println("=====UNKNOWN=====")
        else println("=====UNSATISFIABLE=====")
    } else {
        println("==========")
    }
}

private data class Options(
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
)

/**
 * Parses the MiniZinc-standard FZN solver flags we claim in `klause.msc` (-a, -n, -s, -v,
 * -t, -r) plus our `--engine` / `-e` selector. Unknown flags are tolerated (printed to
 * stderr) to stay forward-compatible with MiniZinc additions we don't recognise.
 */
private fun parseArgs(args: Array<String>): Options {
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
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-a", "--all-solutions" -> { allSolutions = true; i++ }
            "-n" -> { solutionCap = args[++i].toLong(); i++ }
            "-s", "--statistics" -> { statistics = true; i++ }
            "-v", "--verbose" -> { verbose = true; i++ }
            "-t", "--time-limit" -> { timeLimitMs = args[++i].toLong(); i++ }
            "-r" -> { randomSeed = args[++i].toLong(); i++ }
            "-e", "--engine" -> { engine = args[++i]; i++ }
            "--ozn" -> { oznPath = args[++i]; i++ }
            "--unbounded-int-lo" -> { unboundedIntLo = args[++i].toInt(); i++ }
            "--unbounded-int-hi" -> { unboundedIntHi = args[++i].toInt(); i++ }
            else -> {
                if (a.startsWith("-")) {
                    System.err.println("klause-fzn: ignoring unknown flag $a")
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
        System.err.println("usage: klause-fzn [-e engine] [flags] file.fzn")
        exitProcess(2)
    }
    return Options(path, engine, allSolutions, solutionCap, timeLimitMs, randomSeed, verbose, statistics, oznPath, unboundedIntLo, unboundedIntHi)
}
