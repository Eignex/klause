package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.bench.tools.Profiler
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit

internal object CliRecordingCapture {
    private const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
    private const val MAX_SUMMARY_BYTES = 4 * 1024 * 1024
    private const val BASE_OPTIONS = "-Xmx3g -XX:+UseSerialGC -XX:ActiveProcessorCount=1"

    @JvmStatic
    @Suppress("LongMethod")
    fun main(args: Array<String>) {
        val request = parse(args)
        val root = CorpusFetcher.workspaceRoot().canonicalFile
        val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
        require(listOf(root, out).all { file -> file.path.none(Char::isWhitespace) })
        val ref = Catalog.uncapped(request.suite).problems.single { it.name == request.problem }
        require(ref.format == Format.SMTLIB && ref.data == null)
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(ref.source)).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
        require(bytes.size <= MAX_SOURCE_BYTES && sha256(bytes) == request.sha256) { "Original source differs" }
        val input = File(out, "input.smt2").apply { writeBytes(bytes) }
        val runtime = File(System.getProperty("java.home")).canonicalFile
        val settings = File(out, "cli.jfc")
        CompilationStudyBuilds.command(
            root,
            out,
            "jfr-settings",
            listOf(
                File(runtime, "bin/jfr").path,
                "configure",
                "--input",
                "profile",
                "--output",
                settings.path,
                "jdk.InitialEnvironmentVariable#enabled=false",
                "jdk.InitialSystemProperty#enabled=false",
            ),
        )
        val recording = File(out, "cli.jfr")
        val launcher = SolverInvocation.klauseCliBin().canonicalFile
        val command = command(request, launcher, input)
        val environment = childEnvironment(System.getenv(), runtime, recording, settings)
        val before = InstalledBuild.capture(launcher, runtime, environment)
        InstalledBuild.writeManifest(File(out, "installed-manifest.json"), launcher, runtime)
        Reports.writeJson(File(out, "build-provenance.json").path, before)
        Reports.writeJson(File(out, "request.json").path, request)
        Reports.writeJson(File(out, "command.json").path, command)
        val started = System.nanoTime()
        val process = ProcessBuilder(command).directory(root).apply {
            environment().clear()
            environment().putAll(environment)
            redirectOutput(File(out, "cli.stdout"))
            redirectError(File(out, "cli.stderr"))
        }.start()
        val completed = try {
            process.waitFor(request.timeout * 2 + 20_000L, TimeUnit.MILLISECONDS)
        } finally {
            if (process.isAlive) CompilationStudyBuilds.kill(process)
        }
        Reports.writeJson(
            File(out, "measurement.json").path,
            buildJsonObject {
                put("head", Reports.readGitSha())
                put("scope", "whole-cli-child-jvm")
                put("sourceSha256", request.sha256)
                put("sourceBytes", bytes.size)
                put("timeoutMs", request.timeout)
                put("seed", request.seed)
                put("processors", 1)
                put("childPid", process.pid())
                put("exit", process.exitValue())
                put("watchdog", !completed)
                put("processElapsedNs", System.nanoTime() - started)
                put("jvmOptions", environment.getValue("KLAUSE_CLI_OPTS"))
                put("settingsSha256", sha256(settings.readBytes()))
                put("ordinaryTimingCredit", false)
            },
        )
        check(completed && process.exitValue() == 0) { "Profiled CLI did not finish successfully" }
        check(sha256(input.readBytes()) == request.sha256) { "Measured source changed" }
        check(before == InstalledBuild.capture(launcher, runtime, environment)) { "Installed build changed" }
        check(recording.isFile && recording.length() in 1L..CliRecordingSummary.MAX_RECORDING_BYTES)
        val digest = sha256(recording.readBytes())
        val identity = childIdentity(recording)
        check(identity.command.contains("com.eignex.klause.cli.")) { "Recording does not contain the CLI child" }
        check(identity.options.contains("-XX:ActiveProcessorCount=1")) { "CLI processor cap differs" }
        check(identity.pid == null || identity.pid == process.pid()) { "Recording belongs to another process" }
        Reports.writeJson(File(out, "child-jvm.json").path, identity)
        PrintStream(File(out, "summary.txt"), Charsets.UTF_8).use { stream ->
            Profiler.summarizeCli(recording, stream)
            check(!stream.checkError())
        }
        check(File(out, "summary.txt").length() <= MAX_SUMMARY_BYTES)
        check(sha256(recording.readBytes()) == digest) { "Recording changed during summarization" }
        Reports.writeJson(
            File(out, "recording.json").path,
            buildJsonObject {
                put("sha256", digest)
                put("bytes", recording.length())
                put("childPid", process.pid())
                put("sourceSha256", request.sha256)
                put("summarySha256", sha256(File(out, "summary.txt").readBytes()))
                put("independentSourceCheck", false)
            },
        )
    }

    internal fun parse(args: Array<String>): Request {
        val options = args.associate { argument ->
            require('=' in argument)
            argument.substringBefore('=') to argument.substringAfter('=')
        }
        require(options.size == args.size && options.keys == setOf("suite", "problem", "sha256", "timeout", "seed"))
        val suite = options.getValue("suite")
        val problem = options.getValue("problem")
        require(suite.isNotBlank() && problem.isNotBlank() && suite.length <= 256 && problem.length <= 1024)
        require(options.getValue("sha256").matches(Regex("[0-9a-f]{64}")))
        val timeout = requireNotNull(options.getValue("timeout").toLongOrNull())
        require(timeout in 1L..60_000L)
        val seed = requireNotNull(options.getValue("seed").toLongOrNull())
        return Request(suite, problem, options.getValue("sha256"), timeout, seed)
    }

    internal fun command(request: Request, launcher: File, source: File): List<String> = listOf(
        launcher.absolutePath,
        source.absolutePath,
        "-t",
        request.timeout.toString(),
        "-r",
        request.seed.toString(),
        "-s",
    )

    internal fun childEnvironment(
        inherited: Map<String, String>,
        runtime: File,
        recording: File,
        settings: File,
    ): Map<String, String> = inherited.toMutableMap().apply {
        for (name in listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) remove(name)
        put("JAVA_HOME", runtime.path)
        put("JAVA_OPTS", "")
        put(
            "KLAUSE_CLI_OPTS",
            "$BASE_OPTIONS -XX:StartFlightRecording=filename=${recording.path}," +
                "settings=${settings.path},dumponexit=true -Xlog:jfr+startup=warning",
        )
    }

    private fun childIdentity(recording: File): ChildIdentity = RecordingFile(recording.toPath()).use { reader ->
        while (reader.hasMoreEvents()) {
            val event = reader.readEvent()
            if (event.eventType.name == "jdk.JVMInformation") {
                return@use ChildIdentity(
                    event.getString("javaArguments"),
                    event.getString("jvmArguments"),
                    if (event.hasField("pid")) event.getLong("pid") else null,
                )
            }
        }
        error("CLI recording has no JVM identity")
    }

    @Serializable
    internal data class Request(
        val suite: String,
        val problem: String,
        val sha256: String,
        val timeout: Long,
        val seed: Long,
    )

    @Serializable
    private data class ChildIdentity(val command: String, val options: String, val pid: Long?)
}
