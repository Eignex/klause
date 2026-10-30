package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.bench.tools.CliProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs `klause-cli` as a subprocess on the model: the compiled `.fzn` for a MiniZinc instance, or the
 * original file for every other format. klause-cli renders the model's objective, so a maximize objective
 * is reported in the model's orientation, not klause's internal minimised form.
 *
 * Output is parsed off the format's stream — `_objective`/`objective = N`, `----------`
 * (timestamped as read for time-to-best), `==========` (proven), `=====UNSATISFIABLE=====` (proven infeasible) — plus
 * `%%%mzn-stat` lines (klause `-s` statistics). The raw stdout is returned verbatim for saving.
 */
internal object SolverInvocation {

    /** klause-cli knobs, each forwarded as is. [engine] maps to `-e` and [params] to repeatable `--param
     *  key=value`; null [engine] passes no `-e`, so the cli's own default applies. [processors] maps to `-p`,
     *  emitted only above 1 (the cli's default is single-core). [seed] is `-r`. */
    internal data class Settings(
        val engine: String? = null,
        val processors: Int? = null,
        val seed: Long = DEFAULT_SEED,
        val params: List<String> = emptyList(),
        /** klause-cli `--lp CEILING` (LP-relaxation emphasis); null = unset. */
        val lp: String? = null,
        /** klause-cli `--presolve` (presolve emphasis + per-pass deltas); null = unset. */
        val presolve: String? = null,
        val exact: Boolean = false,
        val captureDirectory: File? = null,
        val measureResources: Boolean = false,
        val completeAssignment: Boolean = false,
    )

    /** One subprocess solve: verdict, best objective + time-to-best, proof status, the captured
     *  statistics, the per-arm [attribution] stream (portfolio `-s` only), and the raw stdout. */
    internal data class Result(
        val feasible: Boolean?,
        val objective: Double?,
        val timeToBestMs: Long?,
        /** Wall-clock ms to the *first* feasible solution (the first `----------`), distinct from
         *  [timeToBestMs] (the last/best). Null when never feasible. */
        val timeToFirstFeasibleMs: Long? = null,
        val proven: Boolean,
        val stats: Map<String, String>,
        val attribution: List<Attribution> = emptyList(),
        val rawOutput: String,
        val command: String,
        val buildProvenance: BuildProvenance? = null,
        /** Subprocess wall-clock duration, including launch and output consumption; null when unavailable. */
        val elapsedMs: Long? = null,
        val exactObjective: String? = null,
        val stderr: String? = null,
        val stderrComplete: Boolean = false,
        val stderrCapture: StderrCaptureStatus? = null,
    )

    internal class Failure(val result: Result, message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    /** Run klause-cli over [entry] under [budget]. [optimize] selects intermediate-solution mode (`-a`). */
    fun run(entry: ResolvedProblem, settings: Settings, budget: Budget, optimize: Boolean): Result {
        val file = if (entry.ref.format == Format.MINIZINC) MiniZincRunner().compileFzn(entry.ref)
            else CorpusFetcher.resolve(entry.ref.source)
        return withSourceInput(file, budget) { plain, remaining ->
            val cmd = klauseCommand(entry, settings, remaining, optimize, plain)
            invoke(
                cmd,
                dialectFor(entry.ref.format),
                hardTimeoutMs = remaining.timeoutMillis * 2 + 20_000,
                captureDirectory = settings.captureDirectory,
                measureResources = settings.measureResources,
            )
        }.copy(buildProvenance = InstalledBuild.current)
    }

    internal fun withSourceInput(file: File, budget: Budget, solve: (File, Budget) -> Result): Result {
        if (!CorpusFiles.isCompressed(file)) return solve(file, budget)
        val start = System.nanoTime()
        var preparationMs = 0L
        val result = CorpusFiles.withPlainFile(file) { plain ->
            preparationMs = (System.nanoTime() - start) / 1_000_000L
            val remaining = budget.timeoutMillis - preparationMs
            val result = if (remaining <= 0L) {
                Result(null, null, null, proven = false, stats = mapOf("sourcePreparationBudgetExhausted" to "true"),
                    rawOutput = "", command = "SOURCE_PREPARATION")
            } else {
                solve(plain, Budget(remaining))
            }
            result
        }
        return result.copy(stats = result.stats + mapOf(
            "sourceDecodeMs" to preparationMs.toString(),
            "sourceInvocationMs" to ((System.nanoTime() - start) / 1_000_000L).toString(),
        ))
    }

    /** The provisioned klause-cli launcher. */
    fun klauseCliBin(): File = File(CorpusFetcher.workspaceRoot(), KLAUSE_CLI_BIN)

    /**
     * Why the klause dist at [bin] cannot solve anything, or null when it can. A dist compiled for a
     * newer JDK than the default `java` kills every subprocess identically with an
     * `UnsupportedClassVersionError`, and one error record per instance is indistinguishable from a
     * selection nothing was decided on: a whole sweep reports "0 feasible" in seconds. A run asks this
     * once up front so that failure stops it instead of being spread over the selection.
     */
    fun klauseCliDefect(bin: File = klauseCliBin()): String? {
        if (!bin.isFile) return "$bin is missing — run `./gradlew :klause-cli:installJvmDist`"
        val proc = runCatching { cliVersionCommand(bin).start() }
            .getOrElse { return "$bin will not start: ${it.message}" }
        // Same watchdog shape as [invoke]: kill a child that never answers so the read below returns.
        Thread {
            runCatching { if (!proc.waitFor(PREFLIGHT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) proc.destroyForcibly() }
        }.apply {
            isDaemon = true
            start()
        }
        val output = proc.inputStream.bufferedReader().readText().trim()
        val exit = proc.waitFor()
        return "`$bin --version` exited $exit: ${output.take(STDERR_CAP)}".takeIf { exit != 0 }
    }

    internal fun cliVersionCommand(bin: File, environment: Map<String, String> = System.getenv()): ProcessBuilder =
        ProcessBuilder(bin.absolutePath, "--version").redirectErrorStream(true).apply {
            environment().apply {
                clear()
                putAll(environment)
                environment["KLAUSE_CLI_PREFLIGHT_OPTS"]?.let { put("KLAUSE_CLI_OPTS", it) }
            }
        }

    /** klause-cli on the model file (compiled `.fzn` for MiniZinc, else the original source). */
    private fun klauseCommand(
        entry: ResolvedProblem, s: Settings, budget: Budget, optimize: Boolean, file: File,
    ): List<String> {
        val bin = klauseCliBin()
        check(bin.canExecute()) { "klause-cli not installed at $bin (run :klause-cli:installJvmDist)" }
        return buildList {
            add(bin.absolutePath)
            // MPS instances (notably extension-less MIPLIB files) can't be auto-detected by suffix;
            // the bench knows the format, so pass it explicitly.
            if (entry.ref.format == Format.MPS) {
                add("--format")
                add("mps")
            }
            add(file.absolutePath)
            add("-t")
            add(budget.timeoutMillis.toString())
            add("-r")
            add(s.seed.toString())
            add("-s")
            if (s.params.any { it.startsWith("portfolio-work=") || it.startsWith("companion-integers=") ||
                    it == "portfolio-progress=true" }) add("-v")
            if (s.exact) add("--exact")
            if (s.completeAssignment && entry.ref.format == Format.MINIZINC) add("--output-complete-assignment")
            if (optimize) {
                add("-a")
                // Emit `_objective = <value>;` per solution. The objective var is usually not in the
                // model's `output` section, so without this the parser records objective=null even when
                // klause proves the optimum.
                add("--output-objective")
            }
            // No -f: the engine value encodes free vs fixed search (fixed = follow the annotation).
            s.engine?.let {
                add("-e")
                add(it)
            }
            s.processors?.takeIf { it > 1 }?.let {
                add("-p")
                add(it.toString())
            }
            s.lp?.let {
                add("--lp")
                add(it)
            }
            s.presolve?.let {
                add("--presolve")
                add(it)
            }
            for (param in s.params) {
                add("--param")
                add(param)
            }
        }
    }

    /** Which output stream a subprocess emits, so the read loop parses the right markers. klause-cli
     *  speaks the front-end bound to the *input* format — MiniZinc
     *  for `.mzn`/`.fzn`, the PB-competition stream (`s`/`o`/`c key=value`) for XCSP3 `.xml`, MPS,
     *  OPB, DIMACS CNF and WCNF (the DIMACS/MaxSAT conventions are the same `s`/`o`/`v`/`c` shape),
     *  and the SMT-LIB convention (`sat`/`unsat`/`unknown` + `; key=value` comments) for `.smt2`. */
    internal enum class Dialect { MINIZINC, PB_COMPETITION, SMT_LIB }

    private fun dialectFor(format: Format): Dialect = when {
        format == Format.XCSP3 || format == Format.MPS || format == Format.OPB -> Dialect.PB_COMPETITION
        format == Format.DIMACS || format == Format.WCNF -> Dialect.PB_COMPETITION
        format == Format.SMTLIB -> Dialect.SMT_LIB
        else -> Dialect.MINIZINC
    }

    internal fun invoke(
        cmd: List<String>,
        dialect: Dialect,
        hardTimeoutMs: Long = Long.MAX_VALUE,
        captureDirectory: File? = null,
        measureResources: Boolean = false,
        startProcess: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(false).start() },
        stderrDrainWaitMs: Long = STDERR_DRAIN_WAIT_MS,
    ): Result {
        val startNanos = System.nanoTime()
        captureDirectory?.mkdirs()
        val measured = if (measureResources) {
            listOf(
                if (System.getProperty("os.name").startsWith("Mac")) "gtime" else "/usr/bin/time",
                "--format=peakRssKiB=%M\nuserSeconds=%U\nsystemSeconds=%S\nwallSeconds=%e\nexit=%x",
                "--output=${requireNotNull(captureDirectory).resolve("resources.txt")}",
            ) + cmd
        } else {
            cmd
        }
        captureDirectory?.resolve("cli.stderr.json")?.let {
            check(!it.exists() || it.delete()) { "Cannot remove stale stderr completion marker: $it" }
        }
        captureDirectory?.resolve("cli-command.json")?.writeText(Reports.json.encodeToString(measured))
        val process = startProcess(measured)
        var outputFailure: Throwable? = null
        val stdoutCapture = runCatching { captureDirectory?.resolve("cli.stdout")?.bufferedWriter() }
            .onFailure { outputFailure = it }.getOrNull()
        // Watchdog: force-kill a runaway that blew past [hardTimeoutMs] so the read loop below (which
        // blocks until the child's stdout closes) can't hang forever on a child that never exits. A
        // killed child's stream closes, the loop returns, and the partial output is scored as usual.
        val killed = AtomicReference<String?>(null)
        val watchdog = Thread {
            runCatching {
                if (!process.waitFor(hardTimeoutMs, TimeUnit.MILLISECONDS) && process.isAlive) {
                    killed.compareAndSet(null, "hard-timeout")
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val stderr = StderrCapture(process.errorStream, captureDirectory?.let { directory ->
            { directory.resolve("cli.stderr").outputStream() }
        }).start()

        val raw = StringBuilder()
        val stats = LinkedHashMap<String, String>()
        val attribution = ArrayList<Attribution>()
        var objective: Double? = null
        var exactObjective: String? = null
        var timeToBestMs: Long? = null
        var timeToFirstFeasibleMs: Long? = null
        var proven = false
        var unsat = false
        var anySolution = false
        var smtSat = false

        fun recordObjective(text: String): Boolean {
            val value = ExactObjective.parse(text) ?: return false
            exactObjective = value.toString()
            objective = value.approximate()
            return true
        }

        // An improving incumbent (a new solution / better objective): stamp time-to-best afresh.
        fun markIncumbent() {
            anySolution = true
            val elapsed = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
            timeToBestMs = elapsed
            if (timeToFirstFeasibleMs == null) timeToFirstFeasibleMs = elapsed
        }

        // A terminal status proving a solution exists but without its own timestamp: don't clobber the
        // incumbent time already stamped by an `o`/`----------` line; only stamp if none was seen.
        fun markFeasible() {
            anySolution = true
            if (timeToBestMs == null) markIncumbent()
        }
        fun recordStat(kv: String) = kv.split('=', limit = 2)
            .takeIf { it.size == 2 }?.let { stats[it[0].trim()] = it[1].trim() }
        try {
            process.inputStream.bufferedReader().forEachLine { rawLine ->
                stdoutCapture?.apply {
                    appendLine(rawLine)
                    flush()
                }
                raw.appendLine(rawLine)
                val line = rawLine.trim()
                when (dialect) {
                    Dialect.MINIZINC -> when (line) {
                        SOLUTION_SEPARATOR -> markIncumbent()

                        SEARCH_COMPLETE -> proven = true

                        // MiniZinc prints it only once the search has completed: a proof of infeasibility.
                        UNSATISFIABLE -> {
                            unsat = true
                            proven = true
                        }

                        UNKNOWN, ERROR -> Unit

                        else -> when {
                            line.startsWith(
                                ARM_PREFIX,
                            ) -> parseArm(line.removePrefix(ARM_PREFIX))?.let {
                                val arrivedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
                                attribution.add(it.copy(processElapsedMs = arrivedMs))
                            }

                            line.startsWith(STAT_PREFIX) -> recordStat(line.removePrefix(STAT_PREFIX).trim())

                            line.startsWith("% klause-exact: _objective = ") ||
                                line.startsWith("% klause-coordinate: _objective = ") ->
                                recordObjective(line.substringAfter('=').trim().removeSuffix(";").trim())

                            line.startsWith(OBJECTIVE_KEY) || line.startsWith(MODEL_OBJECTIVE_KEY) ->
                                recordObjective(line.substringAfter('=').trim().removeSuffix(";").trim())
                        }
                    }

                    Dialect.PB_COMPETITION -> when {
                        line == XCSP_SATISFIABLE -> markFeasible()

                        line == XCSP_OPTIMUM -> {
                            markFeasible()
                            proven = true
                        }

                        line == XCSP_UNSATISFIABLE -> {
                            unsat = true
                            proven = true
                        }

                        line == XCSP_UNKNOWN -> Unit

                        // `o <cost>`: one line per improving incumbent (model-oriented objective).
                        line.startsWith(XCSP_OBJECTIVE_PREFIX) -> {
                            if (recordObjective(line.removePrefix(XCSP_OBJECTIVE_PREFIX).trim())) markIncumbent()
                        }

                        line.startsWith(ARM_PREFIX) -> parseArm(line.removePrefix(ARM_PREFIX))?.let {
                            val arrivedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
                            attribution.add(it.copy(processElapsedMs = arrivedMs))
                        }

                        // `c <key>=<value>` statistics (same shape as `%%%mzn-stat:`, different prefix).
                        line.startsWith(XCSP_COMMENT_PREFIX) ->
                            recordStat(line.removePrefix(XCSP_COMMENT_PREFIX).trim())
                    }

                    Dialect.SMT_LIB -> when {
                        line == SMT_SAT -> {
                            smtSat = true
                            markFeasible()
                        }

                        line == SMT_UNSAT -> {
                            unsat = true
                            proven = true
                        }

                        line == SMT_UNKNOWN -> Unit

                        // `; <key>=<value>` statistics (same shape as `%%%mzn-stat:`, different prefix).
                        line.startsWith(SMT_COMMENT_PREFIX) -> {
                            val kv = line.removePrefix(SMT_COMMENT_PREFIX).trim()
                            recordStat(kv)
                            if (kv.substringBefore('=').trim() == "objective") {
                                exactObjective = null
                                objective = null
                                if (recordObjective(kv.substringAfter('=', "").trim())) markIncumbent()
                            }
                        }
                    }
                }
            }
        } catch (failure: IOException) {
            outputFailure = failure
        } finally {
            runCatching { stdoutCapture?.close() }.onFailure { outputFailure = it }
        }
        if (outputFailure != null && process.isAlive) {
            killed.compareAndSet(null, "output-capture-failure")
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        if (!process.waitFor(GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            killed.compareAndSet(null, "hard-timeout")
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(STDERR_DRAIN_WAIT_MS, TimeUnit.MILLISECONDS)
        }
        watchdog.interrupt()
        var diagnostics = stderr.await(stderrDrainWaitMs, killed.get() != null)
        captureDirectory?.let { directory ->
            runCatching {
                directory.resolve("cli.stderr.json").writeText(Reports.json.encodeToString(diagnostics.status))
            }.onFailure {
                diagnostics = diagnostics.copy(
                    status = diagnostics.status.copy(metadataFailure = StderrCapture.describe(it)),
                )
            }
        }
        val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
        val exit = runCatching { process.exitValue() }.getOrNull()
        if (exit != null && exit != 0 && !anySolution && !unsat && diagnostics.refusal != null) {
            stats["unsupported"] = requireNotNull(diagnostics.refusal)
        }
        killed.get()?.let { stats["killed"] = it }
        outputFailure?.let { stats["outputCaptureFailure"] = StderrCapture.describe(it) }
        stats["exit"] = exit?.toString() ?: "unavailable"
        diagnostics.quarantines?.let { stats[QUARANTINED] = it }
        if (cmd.first().substringAfterLast('/') == "klause-cli") {
            System.getenv("KLAUSE_LAB_PROFILE_DIR")?.let { directory ->
                val recording = File(directory, "cli.jfr")
                runCatching { CliProfile.summarizeCli(recording) }
                    .onFailure { System.err.println("CLI profile summary failed: ${it.message}") }
            }
        }
        val result = Result(
            feasible = when {
                anySolution -> true
                unsat -> false
                else -> null
            },
            objective = objective.takeIf { anySolution },
            exactObjective = exactObjective.takeIf { anySolution },
            elapsedMs = elapsedMs,
            timeToBestMs = timeToBestMs,
            timeToFirstFeasibleMs = timeToFirstFeasibleMs,
            proven = if (dialect == Dialect.SMT_LIB) {
                unsat || (smtSat && exactObjective != null && stats["optimizationStatus"] == "optimal")
            } else {
                proven
            },
            stats = stats,
            attribution = attribution,
            rawOutput = raw.toString(),
            command = cmd.joinToString(" "),
            stderr = diagnostics.text,
            stderrComplete = diagnostics.complete,
            stderrCapture = diagnostics.status,
        )
        if ((outputFailure != null && killed.get() != "hard-timeout") || exit == null ||
            (exit != 0 && !anySolution && !unsat && killed.get() == null && diagnostics.refusal == null)) {
            throw Failure(
                result, "${cmd.first()} failed (exit $exit): ${diagnostics.text.take(STDERR_CAP)}", outputFailure,
            )
        }
        return result
    }

    internal fun parsePbObjective(text: String): Double? = ExactObjective.parse(text)?.approximate()

    /** Parse a `%%%klause-arm:` body. `label`, `objective` (the exact discrete `Long` channel), and
     *  `time` are required — a line missing or malformed on any of those is unusable and the whole
     *  entry is dropped. `continuousObjective` is optional (the whole objective when a continuous
     *  column carries cost) and independent of the required fields: a malformed or non-finite value
     *  degrades to "no continuous channel" rather than discarding an otherwise-valid entry. */
    private fun parseArm(body: String): Attribution? {
        val kv = body.trim().split(' ').mapNotNull {
            it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] }
        }.toMap()
        val label = kv["label"] ?: return null
        val exactObjective = kv["objective"]?.toLongOrNull() ?: return null
        val continuousObjective = kv["continuousObjective"]?.toDoubleOrNull()?.takeIf(Double::isFinite)
        return Attribution(
            label = label,
            exactObjective = exactObjective.toString(),
            continuousObjective = continuousObjective,
            elapsedMs = kv["time"]?.toLongOrNull() ?: return null,
        )
    }

    const val KLAUSE = "klause"
    const val DEFAULT_SEED = 3L
    private const val KLAUSE_CLI_BIN = "klause-cli/build/install/klause-cli-jvm/bin/klause-cli"
    private const val SOLUTION_SEPARATOR = "----------"
    private const val SEARCH_COMPLETE = "=========="
    private const val UNSATISFIABLE = "=====UNSATISFIABLE====="
    private const val UNKNOWN = "=====UNKNOWN====="
    private const val ERROR = "=====ERROR====="
    private const val OBJECTIVE_KEY = "objective ="
    private const val MODEL_OBJECTIVE_KEY = "_objective"
    private const val STAT_PREFIX = "%%%mzn-stat:"
    private const val ARM_PREFIX = "%%%klause-arm:"

    // XCSP3 competition output stream (klause-cli's `.xml` front-end).
    private const val XCSP_SATISFIABLE = "s SATISFIABLE"
    private const val XCSP_OPTIMUM = "s OPTIMUM FOUND"
    private const val XCSP_UNSATISFIABLE = "s UNSATISFIABLE"
    private const val XCSP_UNKNOWN = "s UNKNOWN"
    private const val XCSP_OBJECTIVE_PREFIX = "o "
    private const val XCSP_COMMENT_PREFIX = "c "

    // SMT-LIB output stream (klause-cli's `.smt2` front-end).
    private const val SMT_SAT = "sat"
    private const val SMT_UNSAT = "unsat"
    private const val SMT_UNKNOWN = "unknown"
    private const val SMT_COMMENT_PREFIX = "; "
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val GRACE_MILLIS = 30_000L
    private const val STDERR_CAP = 2000

    /** How long the exit path waits for the stderr drain before reading what it collected. */
    private const val STDERR_DRAIN_WAIT_MS = 2_000L

    /** The stat key a record keeps quarantined portfolio arms under. */
    const val QUARANTINED = "quarantined"

    /** Ceiling on the dist's answer to `--version`, so a child that never answers cannot hang a run. */
    private const val PREFLIGHT_TIMEOUT_MS = 30_000L
}

/**
 * A portfolio incumbent record parsed from `%%%klause-arm:`. [exactObjective] stores
 * model-oriented discrete cost as decimal text; [continuousObjective] stores the whole
 * cost when a continuous column contributes.
 *
 * Arrival order may differ from improvement order because publication and emission
 * use separate critical sections. Find the best holder with [SolveMetric.best].
 */
@Serializable
internal data class Attribution(
    val label: String,
    val exactObjective: String? = null,
    val continuousObjective: Double? = null,
    val elapsedMs: Long,
    val processElapsedMs: Long? = null,
)

internal fun solveTimeMs(stats: Map<String, String>): Long? = stats["solveTime"]?.toDoubleOrNull()
    ?.takeIf { it.isFinite() && it >= 0 }?.let { (it * 1000).toLong() }
