package com.eignex.klause.cli

import com.eignex.klause.formats.FormatException
import com.eignex.klause.formats.mps.MpsLoweringException
import com.eignex.klause.formats.opb.OpbLoweringException
import com.eignex.klause.solver.pipeline.EngineParams
import com.eignex.klause.solver.pipeline.PipelineConfigException
import com.eignex.klause.solver.result.UnsoundnessException
import com.eignex.util.process.exitProcess
import com.eignex.util.process.printErrorLine
import com.eignex.util.process.processProperty
import com.eignex.util.runtime.diagnostics.startHeapPeakSampler

// Matched in order by `--format` name or file extension. MiniZinc is the fallback because MiniZinc
// invokes this binary with `.fzn` files and standard flags.
internal val MODES: List<CliMode> = listOf(MiniZincMode, Xcsp3Mode, SmtLibMode, DimacsMode, OpbMode, WcnfMode, MpsMode)

/** Solves one instance; the front-end is chosen by `--format`, else by file extension, else MiniZinc. */
fun main(args: Array<String>) {
    val code = runCli(args)
    if (code != EXIT_OK) exitProcess(code)
}

// A verdict the solver itself refuted gets its own code so no harness mistakes it for a bad input.
private const val EXIT_OK = 0
private const val EXIT_ERROR = 2
private const val EXIT_UNSOUND = 3

// Returns the exit code instead of exiting, so the error boundary is testable.
internal fun runCli(args: Array<String>): Int = try {
    run(args)
    EXIT_OK
} catch (e: CliUsageException) {
    printErrorLine("klause-cli: ${e.message}")
    EXIT_ERROR
} catch (e: PipelineConfigException) {
    printErrorLine("klause-cli: ${e.message}")
    EXIT_ERROR
} catch (e: FormatException) {
    // A user error, not a crash; the message already carries its `klause <format>:` prefix.
    printErrorLine(e.message.orEmpty())
    EXIT_ERROR
} catch (e: MpsLoweringException) {
    printErrorLine("klause ${e.message.orEmpty()}")
    EXIT_ERROR
} catch (e: OpbLoweringException) {
    printErrorLine("klause ${e.message.orEmpty()}")
    EXIT_ERROR
} catch (e: UnsoundnessException) {
    printErrorLine("klause-cli: UNSOUND: ${e.message}")
    printErrorLine(e.stackTraceToString())
    EXIT_UNSOUND
} catch (e: IllegalArgumentException) {
    printErrorLine("klause-cli: ${e.message}")
    EXIT_ERROR
}

private fun run(args: Array<String>) {
    val common = CommonOptions()
    // One parse over every mode's flags, each mutating its own session, so mode flag names must be unique
    // across modes.
    val sessions = MODES.associateWith { it.newSession() }
    val specs = commonFlagSpecs(common) + sessions.values.flatMap { it.flags() }
    parseArgs(args, specs) { positional ->
        if (common.inputPath != null) usageError("multiple input paths: ${common.inputPath}, $positional")
        common.inputPath = positional
    }

    // `--help` wins over `--version` (GNU convention); neither needs an input file.
    if (common.showHelp) {
        println(helpText(specs))
        return
    }
    if (common.showVersion) {
        println(BuildInfo.versionLine())
        return
    }

    val path = common.inputPath ?: usageError("no input file given\n$USAGE")
    // Installed once, before any front-end loads, so every front-end sees the same env overrides.
    common.config = try {
        installCliConfig()
    } catch (error: IllegalArgumentException) {
        usageError(error.message.orEmpty())
    }
    // Anchored before parsing so the bake and the solve share one deadline.
    common.deadlineAtMs = common.timeLimitMs?.let { nowMillis() + it }
    common.startDebugStatistics(processProperty("klause.debug") == "true")
    val mode = pickMode(common, path)
    val session = sessions.getValue(mode)
    // The sampler cannot see backwards, so it starts before the parse; only for the dry-run diagnostic.
    if (EngineParams(common.engineParams).bool("dry-run-presolve") == true) startHeapPeakSampler()
    common.resetInvocationStatistics()
    if (session.runCommands(path, common)) return
    val loadStart = nowMillis()
    val solvable = session.load(path, common)
    common.loadElapsedMs = nowMillis() - loadStart
    val comment = when (mode) {
        MiniZincMode -> "%"
        SmtLibMode -> ";"
        else -> "c"
    }
    SolveCore.solve(solvable, common, session.output(common).sourceQualified(common.sourceSemantics, comment))
}

private const val USAGE =
    "usage: klause-cli [options] <file>\n" +
        "Try 'klause-cli --help' for the full option list."

private fun helpText(specs: List<FlagSpec>): String {
    val byExtension = MODES.joinToString(", ") { m -> ".${m.extensions.first()} → ${m.names.first()}" }
    return """
        |${BuildInfo.versionLine()} — SMT-flavored finite-domain constraint solver
        |
        |usage: klause-cli [options] <file>
        |
        |Solves a single FlatZinc / XCSP3 / SMT-LIB instance. The front-end is chosen by
        |--format, else by the file extension ($byExtension), else MiniZinc/FlatZinc.
        |
        |${renderOptions(specs)}
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

// A names column reaching past it wraps onto its own line.
private const val HELP_DESC_COLUMN = 24

private fun renderOptions(specs: List<FlagSpec>): String {
    val visible = specs.filter { it.help != null }
    return FlagGroup.entries.mapNotNull { group ->
        val rows = visible.filter { it.group == group }
        if (rows.isEmpty()) null else "${group.title}:\n" + rows.joinToString("\n") { renderOptionRow(it) }
    }.joinToString("\n\n")
}

private fun renderOptionRow(spec: FlagSpec): String {
    val left = "  " + spec.names.joinToString(", ") + spec.valueLabel?.let { " <$it>" }.orEmpty()
    val pad = HELP_DESC_COLUMN - left.length
    val gap = if (pad > 0) " ".repeat(pad) else "\n" + " ".repeat(HELP_DESC_COLUMN)
    val default = spec.default?.let { " (default: $it)" }.orEmpty()
    return left + gap + spec.help + default
}

private fun pickMode(common: CommonOptions, path: String): CliMode {
    common.formatOverride?.let { fmt ->
        val f = fmt.lowercase()
        return MODES.firstOrNull { f in it.names } ?: usageError("unknown format `$fmt`")
    }
    val ext = fileExtension(path).lowercase()
    return MODES.firstOrNull { ext in it.extensions } ?: MiniZincMode
}
