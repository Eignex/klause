package com.eignex.klause.cli

import com.eignex.klause.formats.smtlib.UnsupportedSmtException
import com.eignex.klause.formats.xcsp3.UnsupportedXcsp3Exception
import com.eignex.klause.solver.SolveStats

/*
 * Unified klause CLI entry point. The CLI is a registry of CliMode front-ends
 * (MiniZinc / XCSP3 / SMT-LIB); adding a competition front-end is a new CliMode + its
 * OutputProtocol, nothing here.
 *
 * Flow: parse the whole argument vector once against the common solver-control flags plus
 * every mode's own flags -> pick the mode (explicit --format, else file extension, else the
 * default MiniZinc) -> load the instance into a mode-neutral Solvable -> hand it to the
 * shared SolveCore with the mode's output protocol. --coverage instead walks a corpus
 * directory, routing each file to the mode that recognises its extension.
 */

/** Front-end registry. Order matters only for the default fallback (MiniZinc is the default
 *  because MiniZinc invokes this binary with `.fzn` files and standard flags). */
internal val MODES: List<CliMode> = listOf(MiniZincMode, Xcsp3Mode, SmtLibMode)

/** Parse args once, select the front-end mode, load the instance and run the shared driver
 *  (or walk a corpus under `--coverage`). See the file header for the full flow. */
fun main(args: Array<String>) {
    val common = CommonOptions()
    // One parse over the union of common + every mode's flags. Each mode's flags mutate that
    // mode's own session; we keep every session and use only the one finally selected. (Mode
    // flag names must be unique across modes — they are: only MiniZinc adds `--ozn` etc.)
    val sessions = MODES.associateWith { it.newSession() }
    val specs = commonFlagSpecs(common) + sessions.values.flatMap { it.flags() }
    parseArgs(args, specs) { positional ->
        if (common.inputPath != null) usageError("multiple input paths: ${common.inputPath}, $positional")
        common.inputPath = positional
    }

    if (common.coverage) {
        coverage(common, sessions)
        return
    }

    val path = common.inputPath ?: usageError("no input file given\n" + USAGE)
    val mode = pickMode(common, path)
    val session = sessions.getValue(mode)
    val solvable = session.load(path, common)
    SolveCore.solve(solvable, common, session.output(common))
}

private const val USAGE =
    "usage: klause-cli [--format minizinc|xcsp3|smtlib] [-e cp|ls|portfolio] [-a] [-n N] " +
        "[-t ms] [-r seed] [-p threads] [-s] [-v] [-f] [--param key=value ...] [--coverage] <file|dir>"

/** Select the mode: an explicit `--format` wins, else the file extension, else MiniZinc. */
private fun pickMode(common: CommonOptions, path: String): CliMode {
    common.formatOverride?.let { fmt ->
        val f = fmt.lowercase()
        return MODES.firstOrNull { f in it.names } ?: usageError("unknown format `$fmt`")
    }
    val ext = fileExtension(path).lowercase()
    return MODES.firstOrNull { ext in it.extensions } ?: MiniZincMode
}

// --- coverage: walk a corpus, route each file to its mode, tally parsed/solved/unsupported ---

/** Captures the terminal [Verdict] from a [SolveCore] run while discarding all solution and
 *  statistics output — lets the coverage walk reuse the real solve driver silently. */
private class CaptureOutput : OutputProtocol {
    var verdict: Verdict = Verdict.UNKNOWN
        private set

    override fun onSolution(rendered: String, objective: Long?) = Unit
    override fun onComplete(verdict: Verdict) {
        this.verdict = verdict
    }

    override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) = Unit
}

// corpus walk: any parse/solve crash is a per-file verdict, deliberately summarised not rethrown
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun coverage(common: CommonOptions, sessions: Map<CliMode, ModeSession>) {
    val root = common.inputPath ?: usageError("--coverage needs a directory")
    // When `--format` is set, restrict the walk to that mode; else any recognised extension.
    val forced = common.formatOverride?.lowercase()?.let { f -> MODES.firstOrNull { f in it.names } }
    fun modeFor(file: String): CliMode? {
        val ext = fileExtension(file).lowercase()
        val m = MODES.firstOrNull { ext in it.extensions } ?: return null
        return if (forced == null || forced == m) m else null
    }

    val files = walkFiles(root).filter { modeFor(it) != null }.sorted()
    if (files.isEmpty()) {
        errPrintln("no recognised instances under $root")
        exitCli(2)
    }

    var parsed = 0
    var solved = 0
    var unsupported = 0
    var failed = 0
    val reasons = mutableMapOf<String, Int>()
    for (f in files) {
        val mode = modeFor(f) ?: continue
        val solvable = try {
            sessions.getValue(mode).load(f, common)
        } catch (e: UnsupportedXcsp3Exception) {
            unsupported++
            tallyReason(reasons, e.message)
            println("UNSUPPORTED  ${fileName(f)}  — ${reason(e.message)}")
            continue
        } catch (e: UnsupportedSmtException) {
            unsupported++
            tallyReason(reasons, e.message)
            println("UNSUPPORTED  ${fileName(f)}  — ${reason(e.message)}")
            continue
        } catch (e: Exception) {
            failed++
            println("PARSE-ERROR  ${fileName(f)}  — ${e.message?.take(80)}")
            continue
        }
        parsed++
        val capture = CaptureOutput()
        val verdict = try {
            SolveCore.solve(solvable, common, capture)
            capture.verdict
        } catch (e: Exception) {
            Verdict.UNKNOWN
        }
        val tag = when (verdict) {
            Verdict.SATISFIABLE -> "SAT"
            Verdict.OPTIMAL -> "OPT"
            Verdict.BEST_FOUND -> "BEST"
            Verdict.UNSATISFIABLE -> "UNSAT"
            Verdict.UNKNOWN -> "UNKNOWN"
        }
        if (verdict != Verdict.UNKNOWN) solved++
        println("PARSED       ${fileName(f)}  — $tag")
    }

    println()
    println("=== klause coverage ===")
    println("total instances : ${files.size}")
    println("parsed          : $parsed")
    println("  solved        : $solved (sat/unsat/opt within budget)")
    println("  unknown       : ${parsed - solved} (budget exhausted)")
    println("unsupported     : $unsupported")
    println("parse-error     : $failed")
    if (reasons.isNotEmpty()) {
        println("--- unsupported constructs ---")
        reasons.entries.sortedByDescending { it.value }.forEach { (k, v) ->
            println("  ${v.toString().padStart(4)}  $k")
        }
    }
}

private fun tallyReason(reasons: MutableMap<String, Int>, msg: String?) {
    val r = reason(msg)
    reasons[r] = (reasons[r] ?: 0) + 1
}

/** Trim a parser message to its salient "unsupported X" tail for grouping. */
private fun reason(msg: String?): String = (msg ?: "unknown").substringAfter(": ").take(60)
