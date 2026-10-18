package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.metric.BuildProvenance
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.bench.tools.Profiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object CliStreamCapture {
    // Ordinary input or launch failures must not discard later planned cases.
    @Suppress("TooGenericExceptionCaught")
    @JvmStatic
    fun main(args: Array<String>) {
        val output = File(requireNotNull(System.getenv("OUT")) { "OUT must name the capture directory" })
        output.mkdirs()
        val requests = if (args.size == 1 && args[0].startsWith("batch64=")) {
            val bytes = Base64.getDecoder().decode(args[0].substringAfter('='))
            val decoded = Json.decodeFromString<List<List<String>>>(bytes.toString(Charsets.UTF_8))
            require(decoded.isNotEmpty()) { "The capture batch is empty" }
            File(output, "requests.json").writeBytes(bytes)
            decoded
        } else null
        val capture = {
            val checker = SmtStreamChecker(output)
            if (requests != null) {
                for ((index, request) in requests.withIndex()) {
                    val directory = File(output, index.toString()).apply { mkdirs() }
                    try {
                        run(request.toTypedArray(), directory, checker)
                    } catch (error: Exception) {
                        Reports.writeJson(File(directory, "failure.json").path, CaptureFailure(error.toString()))
                    }
                }
            } else run(args, output, checker)
        }
        val profiled = (requests ?: listOf(args.toList())).any { request ->
            "profileJvm=true" in request.takeWhile { it != "--" }
        }
        if (profiled) ProfileCaptureArchive.run(output, capture) else capture()
    }

    private fun run(args: Array<String>, output: File, checker: SmtStreamChecker) {
        val settings = prepare(args, output)
        val provenance = InstalledBuild.current
        val revision = Reports.readGitSha()
        Reports.writeJson(
            File(output, "planned.json").path,
            CapturePlan(revision, settings.sourceSha256, settings.command, settings.stopAfter,
                settings.timeoutMs, settings.checkSmt, settings.checkFzn, settings.checkOpb, settings.minimumWitnesses,
                settings.provisionChecker, provenance, settings.childJvmOptions, settings.recording?.path),
        )
        val stdout = File(output, "stdout")
        val stderr = File(output, "stderr")
        val start = System.nanoTime()
        val builder = ProcessBuilder(settings.command).redirectError(stderr)
        settings.childJvmOptions?.let { builder.environment()["KLAUSE_CLI_OPTS"] = it }
        val process = builder.start()
        val markers = AtomicLong()
        val interruption = AtomicReference<Interruption?>()
        val executor = Executors.newSingleThreadExecutor()
        val reader = executor.submit {
            capture(process, output, stdout, settings, start, markers, interruption)
        }
        var failure: String? = null
        try {
            reader.get(settings.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            interruption.compareAndSet(null, Interruption("watchdog", System.nanoTime() - start, null))
            kill(process)
            failure = runCatching { reader.get(5, TimeUnit.SECONDS) }.exceptionOrNull()?.toString()
        } catch (error: ExecutionException) {
            failure = error.toString()
        } finally {
            if (failure == null) process.waitFor(5, TimeUnit.SECONDS)
            if (process.isAlive) {
                interruption.compareAndSet(null, Interruption("process-watchdog", System.nanoTime() - start, null))
                kill(process)
            }
            process.waitFor(5, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
        Reports.writeJson(File(output, "capture.json").path, CaptureResult(
            exitCode = if (process.isAlive) null else process.exitValue(),
            markers = markers.get(), interruption = interruption.get(), readerFailure = failure,
            stdoutSha256 = stdout.takeIf(File::isFile)?.readBytes()?.let(::sha256),
            stderrSha256 = stderr.takeIf(File::isFile)?.readBytes()?.let(::sha256),
            processId = settings.recording?.let { process.pid() },
        ))
        settings.recording?.let { summarize(it, output) }
        if (settings.checkSmt) checker.check(output, settings.source, interruption.get() != null,
            settings.minimumWitnesses, settings.provisionChecker)
        if (settings.checkFzn) Reports.writeJson(File(output, "source-check-fzn.json").path,
            FznStreamChecker.check(settings.source.readText(), stdout.readText(), interruption.get() != null,
                settings.minimumWitnesses))
        if (settings.checkOpb) Reports.writeJson(File(output, "source-check-opb.json").path,
            OpbStreamChecker.check(settings.source.readText(), stdout.readText(), interruption.get() != null,
                settings.minimumWitnesses))
    }

    private fun summarize(recording: File, output: File) {
        runCatching {
            check(recording.isFile && recording.length() > 0L) { "Child recording is missing" }
            PrintStream(File(output, "cli-profile-summary.txt")).use { stream ->
                Profiler.summarizeCli(recording, stream)
            }
        }.onFailure { error ->
            Reports.writeJson(File(output, "profile-failure.json").path, CaptureFailure(error.toString()))
        }
    }

    internal fun prepare(args: Array<String>, output: File): CaptureSettings {
        val separator = args.indexOf("--")
        require(separator >= 0) { "Use capture settings, --, then CLI arguments" }
        val settings = args.take(separator).associate { it.substringBefore('=') to it.substringAfter('=') }
        require(settings.keys.all { it in setOf("input", "text64", "suite", "problem", "sha256", "profileJvm",
            "extension", "marker", "stopAfter", "timeoutMs", "checkSmt", "checkFzn", "checkOpb",
            "minimumWitnesses", "provisionChecker") })
        val (source, actual) = captureSource(settings, output)
        val extension = source.extension
        val stopAfter = settings["stopAfter"]?.toLong() ?: 0L
        val timeoutMs = settings["timeoutMs"]?.toLong() ?: 60_000L
        require(stopAfter >= 0L && timeoutMs > 0L)
        val marker = settings["marker"]?.let(::Regex)
        require(stopAfter == 0L || marker != null) { "Stopping requires a complete-output marker" }
        val command = listOf(SolverInvocation.klauseCliBin().absolutePath) + args.drop(separator + 1) + source.path
        val checkSmt = settings["checkSmt"]?.toBooleanStrict() ?: false
        val checkFzn = settings["checkFzn"]?.toBooleanStrict() ?: false
        val checkOpb = settings["checkOpb"]?.toBooleanStrict() ?: false
        val provisionChecker = settings["provisionChecker"]?.toBooleanStrict() ?: false
        val minimumWitnesses = settings["minimumWitnesses"]?.toInt() ?: 1
        require(minimumWitnesses > 0 && (!checkSmt || extension == "smt2"))
        require(!checkFzn || extension == "fzn")
        require(!checkOpb || extension == "opb")
        val profile = settings["profileJvm"]?.toBooleanStrict() ?: false
        val recording = if (profile) File(output, "cli.jfr").canonicalFile else null
        val options = recording?.let { profileOptions(System.getenv("KLAUSE_CLI_OPTS").orEmpty(), it) }
        return CaptureSettings(command, source, actual, marker, stopAfter, timeoutMs,
            checkSmt, checkFzn, checkOpb, minimumWitnesses, provisionChecker, options, recording)
    }

    internal fun profileOptions(inherited: String, recording: File): String {
        require("-XX:StartFlightRecording" !in inherited) { "Child recording is already configured" }
        require(recording.path.none { it.isWhitespace() || it == ',' }) { "Recording path is not a JVM option" }
        val option = "-XX:StartFlightRecording=filename=${recording.path},settings=profile,dumponexit=true"
        return listOf(inherited, option).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun captureSource(settings: Map<String, String>, output: File): Pair<File, String> {
        val catalog = settings["suite"] != null || settings["problem"] != null
        require(listOf(settings["input"] != null, settings["text64"] != null, catalog).count { it } == 1) {
            "Supply one input, text64, or catalog source"
        }
        val expected = settings["sha256"]
        require(expected == null || expected.matches(Regex("[0-9a-f]{64}"))) { "A full source hash is required" }
        val ref = if (catalog) {
            require(settings["suite"] != null && settings["problem"] != null && expected != null) {
                "Catalog capture requires suite, problem and sha256"
            }
            Catalog.uncapped(settings.getValue("suite")).problems.single { it.name == settings.getValue("problem") }
                .also { require(it.data == null) { "Capture requires a self-contained source" } }
        } else null
        val catalogExtension = when (ref?.format) {
            null -> null
            Format.MPS -> "mps"
            Format.SMTLIB -> "smt2"
            Format.OPB -> "opb"
            Format.XCSP3 -> "xml"
            else -> error("Unsupported capture format: ${ref.format}")
        }
        val extension = settings["extension"] ?: catalogExtension ?: "smt2"
        require(extension in setOf("smt2", "fzn", "mps", "opb", "xml"))
        require(catalogExtension == null || extension == catalogExtension) { "Catalog source format differs" }
        val bytes = when {
            ref != null -> CorpusFiles.open(CorpusFetcher.resolve(ref.source)).use { it.readBytes() }
            settings["input"] != null -> CorpusFiles.open(File(settings.getValue("input"))).use { it.readBytes() }
            else -> Base64.getDecoder().decode(settings.getValue("text64"))
        }
        val actual = sha256(bytes)
        require(expected == null || actual == expected) { "Original source hash differs" }
        val source = File(output, "input.$extension").apply { writeBytes(bytes) }
        return source to actual
    }

    private fun capture(
        process: Process, output: File, stdout: File, settings: CaptureSettings, start: Long,
        markers: AtomicLong, interruption: AtomicReference<Interruption?>,
    ) {
        val json = Json { encodeDefaults = true }
        File(output, "arrivals.jsonl").bufferedWriter().use { arrivals ->
            stdout.outputStream().buffered().use { raw ->
                process.inputStream.buffered().use { input ->
                    val line = ByteArrayOutputStream()
                    var offset = 0L
                    while (true) {
                        val byte = input.read()
                        if (byte < 0) break
                        raw.write(byte)
                        offset++
                        if (byte != '\n'.code) {
                            line.write(byte)
                            continue
                        }
                        raw.flush()
                        val text = line.toString(Charsets.UTF_8).removeSuffix("\r")
                        line.reset()
                        val matched = settings.marker?.matches(text) == true
                        val count = if (matched) markers.incrementAndGet() else markers.get()
                        val arrival = Arrival(System.nanoTime() - start, offset, process.isAlive, matched, text)
                        arrivals.appendLine(json.encodeToString(arrival))
                        arrivals.flush()
                        if (matched && settings.stopAfter > 0L && count == settings.stopAfter && process.isAlive) {
                            interruption.set(Interruption("output-marker", System.nanoTime() - start, offset))
                            kill(process)
                        }
                    }
                }
            }
        }
    }

    private fun kill(process: Process) {
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }
}

@Serializable
private data class CapturePlan(
    val revision: String?, val sourceSha256: String, val command: List<String>,
    val stopAfter: Long, val timeoutMs: Long, val checkSmt: Boolean, val checkFzn: Boolean, val checkOpb: Boolean,
    val minimumWitnesses: Int,
    val provisionChecker: Boolean, val build: BuildProvenance,
    val childJvmOptions: String? = null, val recording: String? = null,
)

@Serializable
private data class Arrival(
    val elapsedNanos: Long, val stdoutBytes: Long, val processAlive: Boolean, val marker: Boolean, val line: String,
)

@Serializable
private data class Interruption(val trigger: String, val elapsedNanos: Long, val stdoutBytes: Long?)

@Serializable
private data class CaptureResult(
    val exitCode: Int?, val markers: Long, val interruption: Interruption?, val readerFailure: String?,
    val stdoutSha256: String?, val stderrSha256: String?,
    val processId: Long? = null,
)

internal data class CaptureSettings(
    val command: List<String>, val source: File, val sourceSha256: String, val marker: Regex?,
    val stopAfter: Long, val timeoutMs: Long, val checkSmt: Boolean, val checkFzn: Boolean, val checkOpb: Boolean,
    val minimumWitnesses: Int,
    val provisionChecker: Boolean,
    val childJvmOptions: String?, val recording: File?,
)

@Serializable
private data class CaptureFailure(val error: String)
