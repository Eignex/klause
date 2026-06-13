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

    // `--help` / `--version` are informational: they print to stdout and return (exit 0) without
    // needing an input file, and `--help` wins if both are present (GNU convention).
    if (common.showHelp) {
        println(helpText())
        return
    }
    if (common.showVersion) {
        println(BuildInfo.versionLine())
        return
    }

    val path = common.inputPath ?: usageError("no input file given\n$USAGE")
    val mode = pickMode(common, path)
    val session = sessions.getValue(mode)
    val solvable = session.load(path, common)
    SolveCore.solve(solvable, common, session.output(common))
}

private const val USAGE =
    "usage: klause-cli [options] <file>\n" +
        "Try 'klause-cli --help' for the full option list."

/** Long `--help` output (stdout). Formats are listed from the live [MODES] registry so a new
 *  front-end shows up here automatically. */
private fun helpText(): String {
    val formats = MODES.joinToString(" | ") { it.names.first() }
    val byExtension = MODES.joinToString(", ") { m ->
        ".${m.extensions.first()} → ${m.names.first()}"
    }
    return """
        |${BuildInfo.versionLine()} — SMT-flavored finite-domain constraint solver
        |
        |usage: klause-cli [options] <file>
        |
        |Solves a single FlatZinc / XCSP3 / SMT-LIB instance. The front-end is chosen by
        |--format, else by the file extension ($byExtension), else MiniZinc/FlatZinc.
        |
        |Standard FlatZinc options (MiniZinc fzn-spec):
        |  -a, --all-solutions    report all solutions (satisfy) / all improving incumbents (optimize)
        |  -i, --intermediate     print intermediate solutions of increasing quality (optimize)
        |  -n <i>                 stop after reporting i solutions (satisfy)
        |  -f, --free-search      ignore the model's search annotations (alias for -e cp)
        |  -s, --statistics       print solving statistics (%%%mzn-stat lines for FlatZinc)
        |  -v, --verbose          log progress to stderr (as % comment lines)
        |  -t, --time-limit <ms>  wall-clock time limit in milliseconds
        |  -r, --random-seed <i>  random seed
        |  -p, --parallel <i>     solve with i parallel cores (portfolio engines only)
        |
        |Engine selection:
        |  -e, --engine <name>    fixed | cp | ls | mixed | cp-single | ls-single
        |                         (default: fixed = follow the model annotation; -f selects cp)
        |  --param <key=value>    repeatable engine tuning knob (e.g. arms=8, val-selector=max)
        |
        |klause options:
        |  --format, --mode <m>   force a front-end: $formats
        |  --presolve <spec>      presolve passes: none | default | all | comma-list of pass ids
        |  --ozn <file>           MiniZinc output model for solution reconstruction (FlatZinc mode)
        |  -h, --help             show this help and exit
        |  --version              print the solver version and exit
        |
        |Unknown -flags are ignored (with a stderr note) for forward compatibility with MiniZinc.
        |
        |Examples:
        |  klause-cli model.fzn
        |  klause-cli -a -s model.fzn
        |  klause-cli -f -p 4 -t 60000 model.fzn
        |  klause-cli --format xcsp3 instance.xml
    """.trimMargin()
}

/** Select the mode: an explicit `--format` wins, else the file extension, else MiniZinc. */
private fun pickMode(common: CommonOptions, path: String): CliMode {
    common.formatOverride?.let { fmt ->
        val f = fmt.lowercase()
        return MODES.firstOrNull { f in it.names } ?: usageError("unknown format `$fmt`")
    }
    val ext = fileExtension(path).lowercase()
    return MODES.firstOrNull { ext in it.extensions } ?: MiniZincMode
}
