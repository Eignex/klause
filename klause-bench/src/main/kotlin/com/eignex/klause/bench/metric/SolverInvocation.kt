package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.profile.SourcePreparationCapture
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.bench.source.SourceStaging
import com.eignex.klause.bench.tools.CliProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Runs `klause-cli` as a subprocess on the compiled `.fzn` for MiniZinc, else the original file. klause-cli renders
// the objective in the model's orientation, not klause's internal minimized form.
internal object SolverInvocation {

    // Each knob is forwarded as is; a null engine passes no `-e`, so the cli's default applies.
    internal data class Settings(
        val engine: String? = null,
        val processors: Int? = null,
        val seed: Long = DEFAULT_SEED,
        val params: List<String> = emptyList(),
        val lp: String? = null,
        val presolve: String? = null,
        val exact: Boolean = false,
        val captureDirectory: File? = null,
        val measureResources: Boolean = false,
        val completeAssignment: Boolean = false,
        val sourceStaging: SourceStaging = SourceStaging.CACHE,
        val observeSourceHarness: Boolean = false,
        val verbose: Boolean? = null,
        val stdoutFile: File? = captureDirectory?.resolve("cli.stdout"),
    )

    internal data class Result(
        val feasible: Boolean?,
        val objective: Double?,
        val timeToBestMs: Long?,
        val timeToFirstFeasibleMs: Long? = null,
        val proven: Boolean,
        val stats: Map<String, String>,
        val attribution: List<Attribution> = emptyList(),
        val stdout: SolverOutput,
        val command: String,
        val buildProvenance: BuildProvenance? = null,
        // Includes launch and output consumption.
        val elapsedMs: Long? = null,
        val exactObjective: String? = null,
        val stderr: String? = null,
        val stderrComplete: Boolean = false,
        val stderrCapture: StderrCaptureStatus? = null,
        val compiledInputHashes: Map<String, String> = emptyMap(),
    )

    internal class Failure(val result: Result, message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    fun run(entry: ResolvedProblem, settings: Settings, budget: Budget, optimize: Boolean): Result {
        val file = if (entry.ref.format == Format.MINIZINC) {
            MiniZincRunner().compileFzn(entry.ref)
        } else {
            CorpusFetcher.resolve(entry.ref.source)
        }
        val compiled = if (entry.ref.format == Format.MINIZINC) {
            SolveEvidence.fileHashes(
                mapOf(
                    "flatzinc" to file,
                    "outputMapping" to MiniZincRunner().outputFile(file),
                )
            ) + ("flatzincContentV1" to SolveEvidence.flatZincContentHash(file))
        } else {
            emptyMap()
        }
        return withSourceInput(file, budget, staging = settings.sourceStaging,
            observeHarness = if (settings.observeSourceHarness) ({ SourceHarnessObservation.start() }) else null,
            prepareInput = if (settings.completeAssignment && entry.ref.format == Format.SMTLIB) {
                { input, checkpoint, solve -> SmtModelInput.withInput(input, checkpoint, solve) }
            } else null,
        ) { plain, remaining ->
            val cmd = klauseCommand(entry, settings, remaining, optimize, plain)
            try {
                invoke(
                    cmd,
                    dialectFor(entry.ref.format),
                    hardTimeoutMs = remaining.timeoutMillis * 2 + 20_000,
                    captureDirectory = settings.captureDirectory,
                    measureResources = settings.measureResources,
                    stdoutFile = settings.stdoutFile,
                ).copy(compiledInputHashes = compiled)
            } catch (failure: Failure) {
                throw Failure(failure.result.copy(compiledInputHashes = compiled), failure.message.orEmpty(), failure)
            }
        }.copy(buildProvenance = InstalledBuild.current)
    }

    internal fun withSourceInput(
        file: File,
        budget: Budget,
        nanoTime: () -> Long = System::nanoTime,
        staging: SourceStaging = SourceStaging.CACHE,
        decoderIdentity: ((() -> Unit) -> Map<String, String>) = { SourceDecoder.capture(it) },
        observeHarness: (() -> SourceHarnessObservation)? = null,
        prepareInput: ((File, () -> Unit, (File) -> Result) -> Result)? = null,
        solve: (File, Budget) -> Result,
    ): Result {
        val start = nanoTime()
        val observation = if (observeHarness == null) null else runCatching(observeHarness).getOrNull()
        val observationSetup = if (observeHarness == null) 0L else nanoTime() - start
        val cliDecodesInput = CorpusFiles.isCompressed(file) && staging == SourceStaging.CLI
        val stagingDirectory = if (staging == SourceStaging.CLI) null else staging.directory
        var decoder = emptyMap<String, String>()
        var preparationNanos: Long? = null
        var cleanupNanos = 0L
        var cliNanos = 0L
        var result = Result(null, null, null, proven = false, stats = emptyMap(),
            stdout = SolverOutput.Text(""), command = "SOURCE_PREPARATION")
        fun checkActive() = checkSourceActive(nanoTime() - start, budget)
        val failure = runCatching {
            SourcePreparationCapture.span("source-preparation").use { preparation ->
                fun invokeSource(input: File): Result {
                    preparation.close()
                    observation?.preparationComplete()
                    checkActive()
                    val cliStart = nanoTime()
                    preparationNanos = cliStart - start
                    val remaining = budget.timeoutMillis - (cliStart - start) / NANOS_PER_MILLI
                    if (remaining <= 0L) throw SourcePreparationTimeout()
                    try {
                        result = SourcePreparationCapture.measure("source-cli") { solve(input, Budget(remaining)) }
                    } finally {
                        cliNanos = nanoTime() - cliStart
                    }
                    return result
                }
                fun prepareSource(input: File): Result =
                    prepareInput?.invoke(input, ::checkActive, ::invokeSource) ?: invokeSource(input)
                if (cliDecodesInput) {
                    checkActive()
                    decoder = decoderIdentity(::checkActive)
                    result = prepareSource(file)
                } else {
                    CorpusFiles.withPlainFile(file, ::checkActive, { cleanupNanos = it },
                        stagingDirectory = stagingDirectory ?: CorpusFiles.stagingRoot,
                        nanoTime = nanoTime, onCleanupStart = observation?.let { { it.cleanupStarted() } },
                        block = ::prepareSource).also { result = it }
                }
            }
        }.exceptionOrNull()
        if (failure is Failure) result = failure.result
        if (failure is SourcePreparationTimeout) {
            result = result.copy(stats = result.stats + ("sourcePreparationBudgetExhausted" to "true"))
        }
        val observationFinishStart = if (observeHarness == null) null else nanoTime()
        val observed = observation?.finish().orEmpty()
        val observationFinish = observationFinishStart?.let { nanoTime() - it } ?: 0L
        val totalNanos = nanoTime() - start
        val preparationMillis = (preparationNanos ?: (totalNanos - cleanupNanos)) / NANOS_PER_MILLI
        val decoding = if (cliDecodesInput) emptyMap() else mapOf("sourceDecodeMs" to preparationMillis.toString())
        val observationStats = if (observeHarness == null) emptyMap() else observed + mapOf(
            "sourceHarnessObservationKind" to if (observation == null) "unavailable" else "jvm-mxbeans",
            "sourceObservationSetupMs" to (observationSetup / NANOS_PER_MILLI).toString(),
            "sourceObservationFinishMs" to (observationFinish / NANOS_PER_MILLI).toString(),
        )
        result = result.copy(stats = (result.stats - "sourceDecodeMs") + decoder + decoding + observationStats + mapOf(
            "sourceStaging" to if (CorpusFiles.isCompressed(file)) staging.key else "plain",
            "sourceStagingDirectory" to
                if (CorpusFiles.isCompressed(file)) stagingDirectory?.absolutePath.orEmpty() else "",
            "sourcePreparationMs" to preparationMillis.toString(),
            "sourceDecodeLocation" to when {
                cliDecodesInput -> "cli-frontend"
                CorpusFiles.isCompressed(file) -> "harness"
                else -> "none"
            },
            "sourceCleanupMs" to (cleanupNanos / NANOS_PER_MILLI).toString(),
            "sourceCliMs" to (cliNanos / NANOS_PER_MILLI).toString(),
            "sourceInvocationMs" to (totalNanos / NANOS_PER_MILLI).toString(),
        ))
        if (failure != null && (failure !is SourcePreparationTimeout || failure.suppressed.isNotEmpty())) {
            throw Failure(result, failure.message.orEmpty(), failure)
        }
        return result
    }

    private fun checkSourceActive(elapsedNanos: Long, budget: Budget) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Source staging interrupted")
        if (elapsedNanos / NANOS_PER_MILLI >= budget.timeoutMillis) throw SourcePreparationTimeout()
    }

    private class SourcePreparationTimeout : IOException("Source preparation budget exhausted")

    fun klauseCliBin(): File = File(CorpusFetcher.workspaceRoot(), KLAUSE_CLI_BIN)

    // A dist that cannot start (e.g. compiled for a newer JDK) fails every instance identically, which looks like a
    // selection where nothing was decided. Checking once up front stops the run instead.
    fun klauseCliDefect(bin: File = klauseCliBin()): String? {
        if (!bin.isFile) return "$bin is missing — run `./gradlew :klause-cli:installJvmDist`"
        val proc = runCatching { cliVersionCommand(bin).start() }
            .getOrElse { return "$bin will not start: ${it.message}" }
        // Kill a child that never answers so the read below returns.
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

    internal fun klauseCommand(
        entry: ResolvedProblem,
        s: Settings,
        budget: Budget,
        optimize: Boolean,
        file: File,
        bin: File = klauseCliBin(),
    ): List<String> {
        check(bin.canExecute()) { "klause-cli is not executable at $bin" }
        return buildList {
            add(bin.absolutePath)
            // Extensionless MPS files cannot be detected by suffix.
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
            val verbose = s.verbose ?: s.params.any {
                it.startsWith("portfolio-work=") || it.startsWith("companion-integers=") ||
                    it == "portfolio-progress=true"
            }
            if (verbose) add("-v")
            if (s.exact) add("--exact")
            if (s.completeAssignment && entry.ref.format == Format.MINIZINC) add("--output-complete-assignment")
            if (optimize) {
                add("-a")
                // The objective var is usually not in the model's `output` section.
                add("--output-objective")
            }
            // No -f: the engine value encodes free vs fixed search (fixed = follow the annotation).
            s.engine?.let {
                add("-e")
                add(it)
            }
            s.processors?.let {
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
                if (param.startsWith("source-tolerance-envelope=")) {
                    add("--source-tolerance-envelope")
                    add(param.substringAfter('='))
                } else {
                    add("--param")
                    add(param)
                }
            }
        }
    }

    // klause-cli speaks the output convention of the input format; XCSP3, MPS, OPB, CNF and WCNF share the
    // `s`/`o`/`v`/`c` shape.
    internal enum class Dialect { MINIZINC, PB_COMPETITION, SMT_LIB }

    internal fun dialectFor(format: Format): Dialect = when {
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
        stdoutFile: File? = captureDirectory?.resolve("cli.stdout"),
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
        val stdoutCapture = runCatching { stdoutFile?.bufferedWriter() }
            .onFailure { outputFailure = it }.getOrNull()
        // The read loop blocks until stdout closes; killing a runaway closes it and the partial output is scored.
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
        val stderr = StderrCapture(
            process.errorStream,
            captureDirectory?.let { directory ->
                { directory.resolve("cli.stderr").outputStream() }
            }
        ).start()

        val raw = if (stdoutFile == null) StringBuilder() else null
        val stats = LinkedHashMap<String, String>()
        val attribution = ArrayList<Attribution>()
        var objective: Double? = null
        var exactObjective: String? = null
        var pendingMiniZincObjective: ExactObjective? = null
        var timeToBestMs: Long? = null
        var timeToFirstFeasibleMs: Long? = null
        var proven = false
        var unsat = false
        var anySolution = false
        var smtSat = false
        var smtResponses = 0

        fun recordObjective(text: String): Boolean {
            val value = ExactObjective.parse(text) ?: return false
            exactObjective = value.toString()
            objective = value.approximate()
            return true
        }

        fun markIncumbent() {
            anySolution = true
            val elapsed = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
            timeToBestMs = elapsed
            if (timeToFirstFeasibleMs == null) timeToFirstFeasibleMs = elapsed
        }

        // A terminal status keeps the incumbent time already stamped by an `o`/`----------` line.
        fun markFeasible() {
            anySolution = true
            if (timeToBestMs == null) markIncumbent()
        }
        fun recordStat(kv: String) = kv.split('=', limit = 2)
            .takeIf { it.size == 2 }?.let { stats[it[0].trim()] = it[1].trim() }
        fun smtResponse(status: String) {
            if (smtResponses++ > 0) {
                objective = null
                exactObjective = null
                for (key in SMT_QUERY_RESULT_KEYS) stats.remove(key)
                timeToBestMs = null
                timeToFirstFeasibleMs = null
            }
            anySolution = status == SMT_SAT
            smtSat = anySolution
            unsat = status == SMT_UNSAT
            if (anySolution) markFeasible()
        }
        val smtScanner = SmtResponseScanner(status = ::smtResponse)
        try {
            forEachOutputLine(process.inputStream.bufferedReader(), dialect) { rawLine, ending ->
                stdoutCapture?.apply {
                    append(rawLine)
                    append(ending)
                    flush()
                }
                raw?.append(rawLine)?.append(ending)
                val line = rawLine.trim()
                val smtTopLevel = smtScanner.atTopLevel
                if (dialect == Dialect.SMT_LIB) {
                    smtScanner.feed(rawLine)
                    smtScanner.feed(ending)
                }
                when (dialect) {
                    Dialect.MINIZINC -> when (line) {
                        MINIZINC_SOLUTION_SEPARATOR -> {
                            objective = pendingMiniZincObjective?.approximate()
                            exactObjective = pendingMiniZincObjective?.toString()
                            pendingMiniZincObjective = null
                            markIncumbent()
                        }

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

                            line.startsWith(RESULT_COMMENT_PREFIX) &&
                                line.removePrefix(RESULT_COMMENT_PREFIX).substringBefore('=').trim() in
                                LINEAR_RESULT_KEYS -> recordStat(line.removePrefix(RESULT_COMMENT_PREFIX).trim())

                            line.startsWith("% klause-exact: _objective = ") ||
                                line.startsWith("% klause-coordinate: _objective = ") ||
                                line.startsWith(OBJECTIVE_KEY) || line.startsWith(MODEL_OBJECTIVE_KEY) -> {
                                val value = ExactObjective.parse(
                                    line.substringAfter('=').trim().removeSuffix(";").trim(),
                                )
                                if (value != null) pendingMiniZincObjective = value
                            }
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

                        line.startsWith(XCSP_OBJECTIVE_PREFIX) -> {
                            if (recordObjective(line.removePrefix(XCSP_OBJECTIVE_PREFIX).trim())) markIncumbent()
                        }

                        line.startsWith(ARM_PREFIX) -> parseArm(line.removePrefix(ARM_PREFIX))?.let {
                            val arrivedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
                            attribution.add(it.copy(processElapsedMs = arrivedMs))
                        }

                        line.startsWith(XCSP_COMMENT_PREFIX) ->
                            recordStat(line.removePrefix(XCSP_COMMENT_PREFIX).trim())
                    }

                    Dialect.SMT_LIB -> when {
                        smtTopLevel && line.startsWith(SMT_COMMENT_PREFIX) -> {
                            val kv = line.removePrefix(SMT_COMMENT_PREFIX).trim()
                            recordStat(kv)
                            if (kv.substringBefore('=').trim() == "objective") {
                                exactObjective = null
                                objective = null
                                if (recordObjective(kv.substringAfter('=', "").trim()) &&
                                    (smtResponses == 0 || smtSat)) markIncumbent()
                            }
                        }
                    }
                }
            }
            if (dialect == Dialect.SMT_LIB) smtScanner.finish()
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
        if (dialect == Dialect.SMT_LIB) stats["smtQueryResponses"] = smtResponses.toString()
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
            stdout = if (stdoutCapture != null) {
                SolverOutput.Spool(requireNotNull(stdoutFile))
            } else {
                SolverOutput.Text(raw?.toString().orEmpty())
            },
            command = cmd.joinToString(" "),
            stderr = diagnostics.text,
            stderrComplete = diagnostics.complete,
            stderrCapture = diagnostics.status,
        )
        if ((outputFailure != null && killed.get() != "hard-timeout") || exit == null ||
            (exit != 0 && !anySolution && !unsat && killed.get() == null && diagnostics.refusal == null)
        ) {
            throw Failure(
                result,
                "${cmd.first()} failed (exit $exit): ${diagnostics.text.take(STDERR_CAP)}",
                outputFailure,
            )
        }
        return result
    }

    internal fun parsePbObjective(text: String): Double? = ExactObjective.parse(text)?.approximate()

    private fun forEachOutputLine(reader: BufferedReader, dialect: Dialect, consume: (String, String) -> Unit) {
        if (dialect != Dialect.SMT_LIB) {
            reader.forEachLine { consume(it, "\n") }
            return
        }
        val line = StringBuilder()
        val buffer = CharArray(8192)
        var carriageReturn = false
        fun emit(ending: String) {
            consume(line.toString(), ending)
            line.setLength(0)
        }
        while (true) {
            val size = reader.read(buffer)
            if (size < 0) break
            for (index in 0 until size) {
                val char = buffer[index]
                if (carriageReturn) {
                    carriageReturn = false
                    if (char == '\n') {
                        emit("\r\n")
                        continue
                    }
                    emit("\r")
                }
                when (char) {
                    '\r' -> carriageReturn = true
                    '\n' -> emit("\n")
                    else -> line.append(char)
                }
            }
        }
        if (carriageReturn) emit("\r") else if (line.isNotEmpty()) emit("")
    }

    // A missing or malformed `label`, `objective` or `time` drops the entry; a malformed `continuousObjective`
    // only drops that channel.
    private fun parseArm(body: String): Attribution? {
        val kv = body.trim().split(' ').mapNotNull {
            it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] }
        }.toMap()
        val label = kv["label"] ?: return null
        val exactObjective = kv["objective"]?.let(ExactObjective::parse) ?: return null
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
    private const val SEARCH_COMPLETE = "=========="
    private const val UNSATISFIABLE = "=====UNSATISFIABLE====="
    private const val UNKNOWN = "=====UNKNOWN====="
    private const val ERROR = "=====ERROR====="
    private const val OBJECTIVE_KEY = "objective ="
    private const val MODEL_OBJECTIVE_KEY = "_objective"
    private const val STAT_PREFIX = "%%%mzn-stat:"
    private const val RESULT_COMMENT_PREFIX = "% "
    private val LINEAR_RESULT_KEYS = setOf(
        "lpDeductionAuthority", "numericalObjectiveTolerance", "numericalObjectiveBoundDirection",
        "numericalObjectiveBound", "numericalRelativeGap", "optimizationStatus",
    )
    private val SMT_QUERY_RESULT_KEYS = LINEAR_RESULT_KEYS + setOf(
        "objective", "objectiveBound", "objectiveBoundDirection", "objectiveBoundStrict",
    )
    private const val ARM_PREFIX = "%%%klause-arm:"

    private const val XCSP_SATISFIABLE = "s SATISFIABLE"
    private const val XCSP_OPTIMUM = "s OPTIMUM FOUND"
    private const val XCSP_UNSATISFIABLE = "s UNSATISFIABLE"
    private const val XCSP_UNKNOWN = "s UNKNOWN"
    private const val XCSP_OBJECTIVE_PREFIX = "o "
    private const val XCSP_COMMENT_PREFIX = "c "

    private const val SMT_SAT = "sat"
    private const val SMT_UNSAT = "unsat"
    private const val SMT_COMMENT_PREFIX = "; "
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val GRACE_MILLIS = 30_000L
    private const val STDERR_CAP = 2000

    private const val STDERR_DRAIN_WAIT_MS = 2_000L

    const val QUARANTINED = "quarantined"

    private const val PREFLIGHT_TIMEOUT_MS = 30_000L
}

// A `%%%klause-arm:` incumbent. Arrival order may differ from improvement order because publication and emission
// use separate critical sections; use SolveMetric.best for the best holder.
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
