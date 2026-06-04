package com.eignex.klause.cli

import com.eignex.klause.formats.smtlib.SmtLibQfLia
import com.eignex.klause.formats.smtlib.UnsupportedSmtException
import com.eignex.klause.formats.xcsp3.UnsupportedXcsp3Exception
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.brute.BruteForceParams
import com.eignex.klause.solver.brute.BruteForceSolver
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

/**
 * XCSP3 / SMT-LIB path of the unified klause CLI: **XCSP3** (`.xml`) and **SMT-LIB QF_LIA**
 * (`.smt2`). Hands the parsed klause [com.eignex.klause.solver.Problem] to a chosen backend.
 *
 * Usage:
 *   `klause-cli [flags] <file>`        — parse and solve one instance.
 *   `klause-cli --coverage [flags] <dir>` — walk a corpus and report parsed / solved /
 *                                            unsupported counts (the XCSP3 competition-library
 *                                            coverage report).
 *
 * Flags: `--format xcsp3|smtlib` (default: by extension), `--engine backtrack|ls|brute`
 * (default backtrack), `-t <ms>` time budget, `-r <seed>`.
 */
internal fun runXcsp(args: Array<String>) {
    val opts = parseXcspArgs(args)
    if (opts.coverage) coverage(opts) else solveOne(opts)
}

// --- single-instance solve ---

private fun solveOne(opts: XcspOptions) {
    val file = File(opts.path)
    val ing = try {
        ingest(file, opts.format)
    } catch (e: UnsupportedXcsp3Exception) {
        System.err.println(e.message); exitProcess(3)
    } catch (e: UnsupportedSmtException) {
        System.err.println(e.message); exitProcess(3)
    }
    val p = ing.problem
    println("parsed ${file.name}: bool=${p.numBoolVars} int=${p.numIntVars} factors=${p.numFactors}" +
        (if (ing.objective != null) " (optimization)" else " (satisfaction)"))

    when (val r = run(ing, opts)) {
        is Verdict.Sat -> { println("SAT"); printAssignment(r.ints, r.bools) }
        is Verdict.Optimal -> { println("OPTIMUM ${fmt(r.value)}"); printAssignment(r.ints, r.bools) }
        is Verdict.BestFound -> { println("SUBOPTIMAL ${fmt(r.value)} (budget exhausted)"); printAssignment(r.ints, r.bools) }
        Verdict.Unsat -> println("UNSATISFIABLE")
        Verdict.Unknown -> println("UNKNOWN")
    }
}

private fun printAssignment(ints: IntArray, bools: BooleanArray) {
    if (!System.getProperty("klause.xcsp.printSolution").toBoolean()) return
    if (bools.isNotEmpty()) println("bools: " + bools.joinToString(" "))
    if (ints.isNotEmpty()) println("ints: " + ints.joinToString(" "))
}

// --- coverage report over a corpus ---

private fun coverage(opts: XcspOptions) {
    val root = File(opts.path)
    val files = (if (root.isDirectory) root.walkTopDown().filter { it.isFile } else sequenceOf(root))
        .filter { detectFormat(it, opts.format) != null }
        .sortedBy { it.path }
        .toList()
    if (files.isEmpty()) { System.err.println("no XCSP3/SMT-LIB files under ${root.path}"); exitProcess(2) }

    var parsed = 0; var solved = 0; var unsupported = 0; var failed = 0
    val reasons = HashMap<String, Int>()
    for (f in files) {
        val ing = try {
            ingest(f, opts.format)
        } catch (e: UnsupportedXcsp3Exception) {
            unsupported++; reasons.merge(reason(e.message), 1, Int::plus); println("UNSUPPORTED  ${f.name}  — ${reason(e.message)}"); continue
        } catch (e: UnsupportedSmtException) {
            unsupported++; reasons.merge(reason(e.message), 1, Int::plus); println("UNSUPPORTED  ${f.name}  — ${reason(e.message)}"); continue
        } catch (e: Exception) {
            failed++; println("PARSE-ERROR  ${f.name}  — ${e.message?.take(80)}"); continue
        }
        parsed++
        val verdict = try { run(ing, opts) } catch (e: Exception) { Verdict.Unknown }
        val tag = when (verdict) {
            is Verdict.Sat -> "SAT"; is Verdict.Optimal -> "OPT ${fmt(verdict.value)}"
            is Verdict.BestFound -> "BEST ${fmt(verdict.value)}"; Verdict.Unsat -> "UNSAT"; Verdict.Unknown -> "UNKNOWN"
        }
        if (verdict != Verdict.Unknown) solved++
        println("PARSED       ${f.name}  — $tag")
    }

    println()
    println("=== XCSP3 / SMT-LIB coverage ===")
    println("total instances : ${files.size}")
    println("parsed          : $parsed")
    println("  solved        : $solved (sat/unsat/opt within budget)")
    println("  unknown       : ${parsed - solved} (budget exhausted)")
    println("unsupported     : $unsupported")
    println("parse-error     : $failed")
    if (reasons.isNotEmpty()) {
        println("--- unsupported constructs ---")
        reasons.entries.sortedByDescending { it.value }.forEach { (k, v) -> println("  %4d  %s".format(v, k)) }
    }
}

/** Trim a parser message to its salient "unsupported X" tail for grouping. */
private fun reason(msg: String?): String = (msg ?: "unknown").substringAfter(": ").take(60)

// --- ingest + solve plumbing ---

/** A parsed instance ready to solve. */
private class Parsed(val problem: Problem, val objective: Objective?)

private fun ingest(file: File, forced: Format?): Parsed = when (detectFormat(file, forced)
    ?: throw IllegalArgumentException("cannot detect format of ${file.name}; pass --format")) {
    Format.XCSP3 -> Xcsp3.parse(file.readText()).let { Parsed(it.problem, it.objective) }
    Format.SMTLIB -> SmtLibQfLia.parse(file.readText()).let { Parsed(it.problem, it.objective) }
}

private fun detectFormat(file: File, forced: Format?): Format? = forced ?: when (file.extension.lowercase()) {
    "xml", "xcsp", "xcsp3" -> Format.XCSP3
    "smt2", "smt" -> Format.SMTLIB
    else -> null
}

private sealed interface Verdict {
    data class Sat(val ints: IntArray, val bools: BooleanArray) : Verdict
    data class Optimal(val value: Double, val ints: IntArray, val bools: BooleanArray) : Verdict
    data class BestFound(val value: Double, val ints: IntArray, val bools: BooleanArray) : Verdict
    data object Unsat : Verdict
    data object Unknown : Verdict
}

private fun run(ing: Parsed, opts: XcspOptions): Verdict {
    val cancellation = opts.timeMs?.let { Cancellation.after(it.milliseconds) } ?: Cancellation.Never
    return when (opts.engine) {
        Engine.BACKTRACK -> runOn(BacktrackSolver(ing.problem), BacktrackParams(randomSeed = opts.seed ?: 0L, cancellation = cancellation), ing)
        Engine.LS -> runOn(LocalSearchSolver(ing.problem), LocalSearchParams(randomSeed = opts.seed, cancellation = cancellation), ing)
        Engine.BRUTE -> runOn(BruteForceSolver(ing.problem), BruteForceParams(randomSeed = opts.seed), ing)
    }
}

private fun <P : SolverParams> runOn(solver: Solver<P>, params: P, ing: Parsed): Verdict {
    val objective = ing.objective
    if (objective != null && solver is Optimizer<*>) {
        @Suppress("UNCHECKED_CAST")
        return when (val r = (solver as Optimizer<P>).minimize(objective as Objective, params)) {
            is MinimizeResult.Optimal -> Verdict.Optimal(r.objective, r.sample.ints, r.sample.bools)
            is MinimizeResult.BestFound -> Verdict.BestFound(r.objective, r.sample.ints, r.sample.bools)
            is MinimizeResult.Infeasible -> Verdict.Unsat
            is MinimizeResult.Unknown -> Verdict.Unknown
        }
    }
    return when (val r = solver.solve(params)) {
        is SolveResult.Sat -> Verdict.Sat(r.assignment.ints, r.assignment.bools)
        is SolveResult.Unsat -> Verdict.Unsat
        is SolveResult.Unknown -> Verdict.Unknown
    }
}

private fun fmt(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

// --- args ---

private enum class Format { XCSP3, SMTLIB }
private enum class Engine { BACKTRACK, LS, BRUTE }

private data class XcspOptions(
    val path: String,
    val format: Format?,
    val engine: Engine,
    val timeMs: Long?,
    val seed: Long?,
    val coverage: Boolean,
)

private fun parseXcspArgs(args: Array<String>): XcspOptions {
    var format: Format? = null
    var engine = Engine.BACKTRACK
    var timeMs: Long? = null
    var seed: Long? = null
    var coverage = false
    var path: String? = null
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--format" -> { format = when (args[++i].lowercase()) {
                "xcsp3", "xcsp" -> Format.XCSP3; "smtlib", "smt", "smt2" -> Format.SMTLIB
                else -> usage("unknown format ${args[i]}") }; i++ }
            "-e", "--engine" -> { engine = when (args[++i].lowercase()) {
                "backtrack", "bt" -> Engine.BACKTRACK; "ls", "localsearch" -> Engine.LS; "brute" -> Engine.BRUTE
                else -> usage("unknown engine ${args[i]}") }; i++ }
            "-t", "--time-limit" -> { timeMs = args[++i].toLong(); i++ }
            "-r" -> { seed = args[++i].toLong(); i++ }
            "--coverage" -> { coverage = true; i++ }
            else -> {
                if (a.startsWith("-")) { System.err.println("klause-cli: ignoring unknown flag $a"); i++ }
                else { if (path != null) usage("multiple paths: $path, $a"); path = a; i++ }
            }
        }
    }
    return XcspOptions(path ?: usage("no input file/dir given"), format, engine, timeMs, seed, coverage)
}

private fun usage(msg: String): Nothing {
    System.err.println("klause-cli: $msg")
    System.err.println("usage: klause-cli [--format xcsp3|smtlib] [-e backtrack|ls|brute] [-t ms] [-r seed] [--coverage] <file|dir>")
    exitProcess(2)
}
