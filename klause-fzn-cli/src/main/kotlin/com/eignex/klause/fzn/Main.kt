package com.eignex.klause.fzn

import com.eignex.klause.formats.flatzinc.FlatZincProgram
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.writeFlatZincSolution
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import java.io.File
import kotlin.system.exitProcess

/**
 * Minimal MiniZinc-compatible FlatZinc CLI.
 *
 * Invocation: `klause-fzn [flags] file.fzn`. Reads the FlatZinc model, hands it to
 * BacktrackSolver, prints solutions in MiniZinc's standard FZN output format:
 * each solution terminated by `----------`, the entire stream terminated by `==========`
 * for completed search, `=====UNSATISFIABLE=====` when no solution exists, or
 * `=====UNKNOWN=====` when the budget runs out before either is proven.
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)
    val source = File(opts.fznPath).readText()
    val program = parseFlatZinc(source)

    val params = (program.defaultBacktrackParams ?: BacktrackParams()).copy(
        maxDecisions = opts.timeLimitMs?.let { Long.MAX_VALUE } ?: Long.MAX_VALUE,
        randomSeed = opts.randomSeed,
    )

    when (val solve = program.solve) {
        is SolveDirective.Satisfy -> runSatisfy(program, params, opts)
        is SolveDirective.Minimize, is SolveDirective.Maximize ->
            runOptimize(program, solve, params, opts)
    }
}

private fun runSatisfy(program: FlatZincProgram, params: BacktrackParams, opts: Options) {
    val solver = BacktrackSolver(program.problem)
    val limit = if (opts.allSolutions) opts.solutionCap ?: Long.MAX_VALUE else 1L
    var produced = 0L
    val deadline = opts.timeLimitMs?.let { System.currentTimeMillis() + it }

    for (sample in solver.enumerate(params)) {
        if (deadline != null && System.currentTimeMillis() > deadline) {
            println("=====UNKNOWN=====")
            return
        }
        print(writeFlatZincSolution(program, sample))
        produced++
        if (produced >= limit) break
    }

    if (produced == 0L) {
        // enumerate exhausted without finding a model.
        println("=====UNSATISFIABLE=====")
    } else {
        // Completed search or hit the requested cap; both report ==========.
        println("==========")
    }
}

private fun runOptimize(
    program: FlatZincProgram,
    solve: SolveDirective,
    params: BacktrackParams,
    opts: Options,
) {
    // Linear search: emit each feasible improvement as it's found. The current
    // BacktrackSolver.minimize enumerates internally, so it doesn't stream improving
    // solutions — for now, just produce the best found and report it.
    // TODO: switch to branch-and-bound (matches the optimizer TODO item).
    val solver = BacktrackSolver(program.problem)
    val objKind: SolveDirective.ObjKind
    val objName: String
    val maximize: Boolean
    when (solve) {
        is SolveDirective.Minimize -> { objName = solve.objVar; objKind = solve.kind; maximize = false }
        is SolveDirective.Maximize -> { objName = solve.objVar; objKind = solve.kind; maximize = true }
        else -> error("unreachable")
    }
    require(objKind == SolveDirective.ObjKind.Int) {
        "klause-fzn currently supports only int objectives; got $objKind for $objName"
    }
    val objVarId = program.intVarsByName[objName]
        ?: error("objective variable '$objName' not found in int var map")

    var best: com.eignex.klause.solver.Sample? = null
    var bestObj = if (maximize) Int.MIN_VALUE else Int.MAX_VALUE
    val deadline = opts.timeLimitMs?.let { System.currentTimeMillis() + it }
    for (sample in solver.enumerate(params)) {
        if (deadline != null && System.currentTimeMillis() > deadline) break
        val v = sample.ints[objVarId]
        val improved = if (maximize) v > bestObj else v < bestObj
        if (improved) {
            bestObj = v
            best = sample
            print(writeFlatZincSolution(program, sample))
            println("----------")
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
    val allSolutions: Boolean,
    val solutionCap: Long?,
    val timeLimitMs: Long?,
    val randomSeed: Long?,
    val verbose: Boolean,
    val statistics: Boolean,
)

/**
 * Parses the MiniZinc-standard FZN solver flags we claim in `klause.msc`: -a, -n, -s,
 * -v, -t, -r, plus the positional FZN path. Unknown flags are tolerated (printed to
 * stderr) to keep MiniZinc happy when it adds new ones we don't recognise.
 */
private fun parseArgs(args: Array<String>): Options {
    var allSolutions = false
    var solutionCap: Long? = null
    var timeLimitMs: Long? = null
    var randomSeed: Long? = null
    var verbose = false
    var statistics = false
    var fznPath: String? = null
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-a", "--all-solutions" -> { allSolutions = true; i++ }
            "-n" -> { solutionCap = args[++i].toLong(); i++ }
            "-s", "--statistics" -> { statistics = true; i++ }
            "-v", "--verbose" -> { verbose = true; i++ }
            "-t", "--time-limit" -> { timeLimitMs = args[++i].toLong(); i++ }
            "-r" -> { randomSeed = args[++i].toLong(); i++ }
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
        System.err.println("usage: klause-fzn [flags] file.fzn")
        exitProcess(2)
    }
    return Options(path, allSolutions, solutionCap, timeLimitMs, randomSeed, verbose, statistics)
}
