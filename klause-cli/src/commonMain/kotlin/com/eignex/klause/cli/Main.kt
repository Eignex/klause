package com.eignex.klause.cli

/*
 * Unified klause CLI entry point. The CLI is a registry of CliMode front-ends
 * (MiniZinc / XCSP3 / SMT-LIB); adding a competition front-end is a new CliMode + its
 * OutputProtocol, nothing here.
 *
 * Flow: parse the whole argument vector once against the common solver-control flags plus
 * every mode's own flags -> pick the mode (explicit --format, else file extension, else the
 * default MiniZinc) -> load the instance into a mode-neutral Solvable -> hand it to the
 * shared SolveCore with the mode's output protocol.
 *
 * Whole-corpus parse/solve coverage reporting is NOT here — that is klause-bench's
 * `coverage:xcsp3|smtlib` tool command, which is catalog-driven and fetches the external
 * libraries. The CLI solves exactly one instance per invocation.
 */

/** Front-end registry. Order matters only for the default fallback (MiniZinc is the default
 *  because MiniZinc invokes this binary with `.fzn` files and standard flags). */
internal val MODES: List<CliMode> = listOf(MiniZincMode, Xcsp3Mode, SmtLibMode)

/** Parse args once, select the front-end mode, load the instance and run the shared driver.
 *  See the file header for the full flow. */
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

    val path = common.inputPath ?: usageError("no input file given\n" + USAGE)
    val mode = pickMode(common, path)
    val session = sessions.getValue(mode)
    val solvable = session.load(path, common)
    SolveCore.solve(solvable, common, session.output(common))
}

private const val USAGE =
    "usage: klause-cli [--format minizinc|xcsp3|smtlib] [-e cp|ls|portfolio] [-a] [-n N] " +
        "[-t ms] [-r seed] [-p threads] [-s] [-v] [-f] [--param key=value ...] <file>"

/** Select the mode: an explicit `--format` wins, else the file extension, else MiniZinc. */
private fun pickMode(common: CommonOptions, path: String): CliMode {
    common.formatOverride?.let { fmt ->
        val f = fmt.lowercase()
        return MODES.firstOrNull { f in it.names } ?: usageError("unknown format `$fmt`")
    }
    val ext = fileExtension(path).lowercase()
    return MODES.firstOrNull { ext in it.extensions } ?: MiniZincMode
}
