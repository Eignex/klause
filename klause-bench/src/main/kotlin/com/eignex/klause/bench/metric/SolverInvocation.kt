package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Uniform subprocess solving: every solver is run as an external process emitting MiniZinc-format
 * output, then parsed identically. Two backends:
 *  - **klause** → the `klause-cli` binary on the model (the compiled `.fzn` for a MiniZinc instance,
 *    or the original file for FlatZinc / XCSP3 / SMT-LIB). klause-cli renders the model's objective,
 *    so a maximize objective is reported in the model's orientation (not klause's internal minimised
 *    form) — the comparison is sign-correct by construction.
 *  - **a reference** (`choco` / `gecode` / `yuck` / …) → `minizinc --solver <id>` end-to-end on the
 *    original `.mzn`, so the solver compiles with its own globals library (the competition setup).
 *
 * Output is parsed off the standard FlatZinc stream — `_objective`/`objective = N`, `----------`
 * (timestamped as read for time-to-best), `==========` (proven), `=====UNSATISFIABLE=====` — plus
 * `%%%mzn-stat` lines (klause `-s` statistics). The raw stdout is returned verbatim for saving.
 */
internal object SolverInvocation {

    /** Solver-side knobs. [engine] (`cp`/`ls`/`portfolio`) and [free] (ignore the model annotation)
     *  apply to klause only; references run their own default search. [processors] maps to `-p`. */
    internal data class Settings(
        val engine: String? = null,
        val processors: Int = 1,
        val free: Boolean = false,
        val seed: Long = 3L,
    )

    /** One subprocess solve: verdict, best objective + time-to-best, proof status, the captured
     *  `%%%mzn-stat` lines, and the raw stdout (saved as the run's "minizinc output"). */
    internal data class Result(
        val feasible: Boolean?,
        val objective: Double?,
        val timeToBestMs: Long?,
        val proven: Boolean,
        val stats: Map<String, String>,
        val rawOutput: String,
        val command: String,
    )

    /** Registered MiniZinc solver ids, parsed once from `minizinc --solvers` (the parenthesised tag
     *  lists). Lets a reference report "not available" instead of failing obscurely. */
    private val registered: Set<String> by lazy {
        runCatching {
            val proc = ProcessBuilder("minizinc", "--solvers").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor(REGISTRY_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            Regex("""\(([^)]*)\)""").findAll(out)
                .flatMap { it.groupValues[1].split(',') }
                .map { it.trim().lowercase() }
                .toSet()
        }.getOrElse { emptySet() }
    }

    fun referenceAvailable(solverId: String): Boolean = solverId.lowercase() in registered

    /** Run [solverId] over [entry] under [budget]. `solverId == "klause"` runs the cli; any other id
     *  is a registered MiniZinc reference. [optimize] selects intermediate-solution mode (`-a`). */
    fun run(entry: ResolvedProblem, solverId: String, settings: Settings, budget: Budget, optimize: Boolean): Result {
        val cmd = if (solverId == KLAUSE) {
            klauseCommand(entry, settings, budget, optimize)
        } else {
            minizincCommand(entry, solverId, settings, budget, optimize)
        }
        return invoke(cmd)
    }

    /** klause-cli on the model file (compiled `.fzn` for MiniZinc, else the original source). */
    private fun klauseCommand(entry: ResolvedProblem, s: Settings, budget: Budget, optimize: Boolean): List<String> {
        val bin = File(CorpusFetcher.workspaceRoot(), KLAUSE_CLI_BIN)
        check(bin.canExecute()) { "klause-cli not installed at $bin (run :klause-cli:installJvmDist)" }
        val file = if (entry.ref.format == Format.MINIZINC) {
            MiniZincRunner().compileFzn(entry.ref)
        } else {
            CorpusFetcher.resolve(entry.ref.source)
        }
        return buildList {
            add(bin.absolutePath)
            add(file.absolutePath)
            add("-t")
            add(budget.timeoutMillis.toString())
            add("-r")
            add(s.seed.toString())
            add("-s")
            if (optimize) add("-a")
            if (s.free) add("-f")
            s.engine?.let {
                add("-e")
                add(it)
            }
            if (s.processors > 1) {
                add("-p")
                add(s.processors.toString())
            }
        }
    }

    /** `minizinc --solver <id>` end-to-end on the original `.mzn` (+ `.dzn`). */
    private fun minizincCommand(
        entry: ResolvedProblem,
        solverId: String,
        s: Settings,
        budget: Budget,
        optimize: Boolean,
    ): List<String> {
        require(entry.ref.format == Format.MINIZINC) {
            "reference '$solverId' only runs MiniZinc instances (got ${entry.ref.format})"
        }
        val mzn = CorpusFetcher.resolve(entry.ref.source)
        val dzn = entry.ref.data?.let { CorpusFetcher.resolve(it) }
        return buildList {
            add("minizinc")
            add("--solver")
            add(solverId)
            add("--time-limit")
            add(budget.timeoutMillis.toString())
            add("--output-mode")
            add("dzn")
            if (optimize) {
                add("-a")
                add("--output-objective")
            }
            if (s.free) add("-f")
            if (s.processors > 1) {
                add("-p")
                add(s.processors.toString())
            }
            add(mzn.absolutePath)
            if (dzn != null) add(dzn.absolutePath)
        }
    }

    private fun invoke(cmd: List<String>): Result {
        val process = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val stderr = StringBuilder()
        val drain = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
        drain.isDaemon = true
        drain.start()

        val raw = StringBuilder()
        val stats = LinkedHashMap<String, String>()
        var objective: Double? = null
        var timeToBestMs: Long? = null
        var proven = false
        var unsat = false
        var anySolution = false
        val startNanos = System.nanoTime()
        process.inputStream.bufferedReader().forEachLine { rawLine ->
            raw.appendLine(rawLine)
            when (val line = rawLine.trim()) {
                SOLUTION_SEPARATOR -> {
                    anySolution = true
                    timeToBestMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
                }

                SEARCH_COMPLETE -> proven = true

                UNSATISFIABLE -> unsat = true

                UNKNOWN, ERROR -> Unit

                else -> when {
                    line.startsWith(STAT_PREFIX) -> line.removePrefix(STAT_PREFIX).trim().split('=', limit = 2)
                        .takeIf { it.size == 2 }?.let { stats[it[0].trim()] = it[1].trim() }

                    line.startsWith(OBJECTIVE_KEY) || line.startsWith(MODEL_OBJECTIVE_KEY) ->
                        line.substringAfter('=').trim().removeSuffix(";").trim().toDoubleOrNull()
                            ?.let { objective = it }
                }
            }
        }
        if (!process.waitFor(GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor()
        }
        check(process.exitValue() == 0 || anySolution || unsat) {
            "${cmd.first()} failed (exit ${process.exitValue()}): ${stderr.toString().take(STDERR_CAP)}"
        }
        return Result(
            feasible = when {
                anySolution -> true
                unsat -> false
                else -> null
            },
            objective = objective.takeIf { anySolution },
            timeToBestMs = timeToBestMs,
            proven = proven,
            stats = stats,
            rawOutput = raw.toString(),
            command = cmd.joinToString(" "),
        )
    }

    const val KLAUSE = "klause"
    private const val KLAUSE_CLI_BIN = "klause-cli/build/install/klause-cli-jvm/bin/klause-cli"
    private const val SOLUTION_SEPARATOR = "----------"
    private const val SEARCH_COMPLETE = "=========="
    private const val UNSATISFIABLE = "=====UNSATISFIABLE====="
    private const val UNKNOWN = "=====UNKNOWN====="
    private const val ERROR = "=====ERROR====="
    private const val OBJECTIVE_KEY = "objective ="
    private const val MODEL_OBJECTIVE_KEY = "_objective"
    private const val STAT_PREFIX = "%%%mzn-stat:"
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val GRACE_MILLIS = 30_000L
    private const val STDERR_CAP = 2000
    private const val REGISTRY_WAIT_MILLIS = 10_000L
}
