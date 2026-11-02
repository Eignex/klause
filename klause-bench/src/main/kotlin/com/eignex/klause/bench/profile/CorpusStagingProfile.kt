package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import java.io.File
import java.io.InputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object CorpusStagingProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val request = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        if ("trial-path" in request) {
            trial(request)
            return
        }
        require(request.keys - setOf("order", "staging-target", "staging-buffer-kib") ==
            setOf("suite", "problem", "sha256", "budget-ms", "repeats"))
        val budget = request.getValue("budget-ms").toLong().also { require(it in 1..60_000) }
        val repeats = request.getValue("repeats").toInt().also { require(it in 2..4) }
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val reference = Catalog.uncapped(request.getValue("suite")).problems
            .single { it.name == request.getValue("problem") }
        capture(CorpusFetcher.resolve(reference.source), out, budget, repeats, request.getValue("sha256"),
            CorpusStagingControls(request["order"] ?: "forward", request["staging-target"] ?: "cache",
                (request["staging-buffer-kib"] ?: "8").toInt()))
    }

    internal fun capture(
        packed: File,
        out: File,
        budget: Long,
        repeats: Int = 2,
        expectedHash: String? = null,
        controls: CorpusStagingControls = CorpusStagingControls(),
    ) {
        require(budget in 1..60_000 && repeats in 2..4)
        val stagingRoot = controls.stagingRoot
        val profileStart = System.nanoTime()
        out.resolve("request.json").writeText(buildJsonObject {
            put("source", packed.absolutePath)
            put("expectedSha256", expectedHash)
            put("budgetMs", budget)
            put("repeats", repeats)
            put("order", controls.order)
            put("stagingTarget", controls.stagingTarget)
            put("stagingBufferBytes", controls.bufferKiB * 1024)
            put("stagingDirectory", stagingRoot.absolutePath)
            put("instrumentation", "stage-uninstrumented and stage-prehashed have no JFR")
            put("prehash", "stage-prehashed matches canonical source hashing before the source deadline")
            put("osCache", "uncontrolled; parent identity reads and plain materialization precede trials")
            put("jvmCache", "fresh child per mode; first operation then repeated operations in that child")
            put("solverExecuted", false)
            put("throughputQualification", false)
        }.toString())
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val setupStart = System.nanoTime()
        require(CorpusFiles.isCompressed(packed))
        val plain = out.resolve(CorpusFiles.plainPath(packed.name))
        try {
            CorpusFiles.withPlainFile(packed, checkActive = {
                check((System.nanoTime() - setupStart) / NANOS_PER_MILLI < budget) { "Identity preparation exhausted" }
            }) { Files.move(it.toPath(), plain.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            val sourceHash = digest(CorpusFiles.open(plain))
            require(expectedHash == null || sourceHash == expectedHash)
            out.resolve("identity.json").writeText(buildJsonObject {
                put("sourceSha256", sourceHash)
                put("packedSha256", digest(packed.inputStream()))
                put("packedBytes", packed.length())
                put("plainBytes", plain.length())
                put("sourceFileStore", Files.getFileStore(packed.toPath()).toString())
                put("plainFileStore", Files.getFileStore(plain.toPath()).toString())
                put("identityStagingFileStore",
                    Files.getFileStore(File(CorpusFetcher.cacheRoot, ".plain").toPath()).toString())
                put("stagingFileStore", Files.getFileStore(stagingRoot.toPath()).toString())
                put("setupNanos", System.nanoTime() - setupStart)
            }.toString())
            val modes = listOf("packed-read", "decode-hash", "stage", "stage-uninstrumented",
                "stage-prehashed", "plain-hash")
                .let { if (controls.order == "reverse") it.reversed() else it }
            for (mode in modes) {
                val command = buildList {
                    addAll(listOf(File(System.getProperty("java.home"), "bin/java").absolutePath,
                        "-Xmx512m", "-XX:ActiveProcessorCount=1", "--enable-native-access=ALL-UNNAMED",
                        "-Dklause.bench.corpusCache=${CorpusFetcher.cacheRoot}"))
                    if (mode !in setOf("stage-uninstrumented", "stage-prehashed")) {
                        add("-XX:StartFlightRecording=filename=${out.resolve("$mode.jfr")}," +
                            "settings=profile,dumponexit=true")
                    }
                    addAll(listOf("-cp", System.getProperty("java.class.path"), CorpusStagingProfile::class.java.name,
                        "trial-path=${if (mode == "plain-hash") plain else packed}", "mode=$mode",
                        "sha256=$sourceHash", "budget-ms=$budget", "repeats=$repeats",
                        "samples=${out.resolve("$mode.jsonl")}", "staging-target=${controls.stagingTarget}",
                        "staging-buffer-bytes=${controls.bufferKiB * 1024}",
                        "staging-directory=${stagingRoot.absolutePath}"))
                }
                out.resolve("$mode-command.json").writeText(Reports.json.encodeToString(command))
                val process = ProcessBuilder(command).redirectOutput(out.resolve("$mode.stdout"))
                    .redirectError(out.resolve("$mode.stderr")).start()
                var interrupted = false
                val completed = try {
                    process.waitFor(budget * repeats + 30_000, TimeUnit.MILLISECONDS)
                } catch (failure: InterruptedException) {
                    interrupted = true
                    throw failure
                } finally {
                    if (process.isAlive) {
                        process.destroyForcibly()
                        process.waitFor(5000, TimeUnit.MILLISECONDS)
                    }
                    if (interrupted) Thread.currentThread().interrupt()
                }
                out.resolve("$mode-exit.json").writeText(buildJsonObject {
                    put("completed", completed)
                    put("exit", if (process.isAlive) null else process.exitValue())
                }.toString())
            }
            for (mode in modes) {
                val recording = out.resolve("$mode.jfr")
                if (recording.isFile) summarizeIo(recording, out.resolve("$mode-io.jsonl"))
            }
        } finally {
            try {
                check(!plain.exists() || plain.delete()) { "Cannot remove plain control: $plain" }
            } finally {
                out.resolve("profile-nanos.txt").writeText("${System.nanoTime() - profileStart}\n")
            }
        }
    }

    private fun trial(request: Map<String, String>) {
        val source = File(request.getValue("trial-path"))
        val mode = request.getValue("mode")
        require(mode in setOf("packed-read", "decode-hash", "stage", "stage-uninstrumented",
            "stage-prehashed", "plain-hash"))
        val budget = request.getValue("budget-ms").toLong()
        val cpu = ManagementFactory.getThreadMXBean()
        File(request.getValue("samples")).printWriter().use { samples ->
            repeat(request.getValue("repeats").toInt()) { index ->
                val iterationStart = System.nanoTime()
                val prehash = runCatching {
                    if (mode == "stage-prehashed") {
                        val hash = digest(CorpusFiles.open(source)) {
                            check(!Thread.currentThread().isInterrupted) { "Interrupted" }
                            check((System.nanoTime() - iterationStart) / NANOS_PER_MILLI < budget) {
                                "Source identity preparation exhausted"
                            }
                        }
                        require(hash == request.getValue("sha256")) { "Prehash source identity mismatch" }
                    }
                }
                val prehashNanos = if (mode == "stage-prehashed") System.nanoTime() - iterationStart else 0L
                val start = System.nanoTime()
                val cpuStart = if (cpu.isCurrentThreadCpuTimeSupported) cpu.currentThreadCpuTime else -1L
                var stagingNanos: Long? = null
                var cleanupNanos = 0L
                var verificationNanos = 0L
                var stagedBytes: Long? = null
                var observedHash: String? = null
                fun checkActive() {
                    check(!Thread.currentThread().isInterrupted) { "Interrupted" }
                    check((System.nanoTime() - start) / NANOS_PER_MILLI < budget) { "Source allowance exhausted" }
                }
                val failure = prehash.exceptionOrNull()?.toString() ?: runCatching {
                    when (mode) {
                        "stage", "stage-uninstrumented", "stage-prehashed" -> CorpusFiles.withPlainFile(
                            source, ::checkActive, { cleanupNanos = it },
                            stagingDirectory = File(request.getValue("staging-directory")),
                            bufferBytes = request.getValue("staging-buffer-bytes").toInt(),
                        ) { plain ->
                            stagingNanos = System.nanoTime() - start
                            val verifyStart = System.nanoTime()
                            stagedBytes = plain.length()
                            observedHash = digest(plain.inputStream(), ::checkActive)
                            verificationNanos = System.nanoTime() - verifyStart
                        }
                        else -> {
                            observedHash = digest(if (mode == "decode-hash") CorpusFiles.open(source)
                                else source.inputStream(), ::checkActive)
                        }
                    }
                }.exceptionOrNull()?.toString()
                val total = System.nanoTime() - start
                val cpuEnd = if (cpu.isCurrentThreadCpuTimeSupported) cpu.currentThreadCpuTime else -1L
                samples.println(buildJsonObject {
                    put("mode", mode)
                    put("instrumented", mode !in setOf("stage-uninstrumented", "stage-prehashed"))
                    put("operation", index)
                    put("jvmCache", if (index == 0) "fresh-first-operation" else "same-process-repeated-operation")
                    put("osCache", "uncontrolled; parent read and materialization completed before child startup")
                    put("sourcePath", source.absolutePath)
                    put("stagingTarget", request.getValue("staging-target"))
                    put("stagingBufferBytes", request.getValue("staging-buffer-bytes").toInt())
                    put("stagingDirectory", request.getValue("staging-directory"))
                    put("sourceFileBytes", source.length())
                    put("stagedBytes", stagedBytes)
                    put("sha256", observedHash)
                    put("matchesPlainSource", observedHash?.let { it == request.getValue("sha256") })
                    put("sourceAllowanceMs", budget)
                    put("prehashNanos", prehashNanos)
                    put("stagingNanos", stagingNanos)
                    put("cleanupNanos", cleanupNanos)
                    put("verificationNanos", verificationNanos)
                    put("totalNanos", total)
                    put("iterationTotalNanos", System.nanoTime() - iterationStart)
                    put("threadCpuNanos", if (cpuStart >= 0L && cpuEnd >= 0L) cpuEnd - cpuStart else null)
                    put("failure", failure)
                })
                samples.flush()
            }
        }
    }

    private fun digest(input: InputStream, checkActive: () -> Unit = {}): String = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkActive()
            val count = it.read(buffer)
            checkActive()
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        HexFormat.of().formatHex(digest.digest())
    }

    private fun summarizeIo(recording: File, output: File) {
        val ioEvents = setOf("jdk.FileRead", "jdk.FileWrite")
        output.bufferedWriter().use { writer ->
            writer.appendLine(buildJsonObject {
                put("scope", "Recorded JFR IO events only; recording thresholds can omit short operations")
                put("overlapPolicy", "Event durations are observations, not disjoint source cost totals")
            }.toString())
            RecordingFile(recording.toPath()).use { events ->
                while (events.hasMoreEvents()) {
                    val event = events.readEvent()
                    if (event.eventType.name !in ioEvents) continue
                    writer.appendLine(buildJsonObject {
                        put("event", event.eventType.name)
                        put("start", event.startTime.toString())
                        put("durationNanos", event.duration.toNanos())
                        put("path", event.getString("path"))
                        put("thread", event.thread?.javaName)
                        put("bytes", event.getLong(if (event.eventType.name == "jdk.FileRead")
                            "bytesRead" else "bytesWritten"))
                        put("stack", JsonArray(event.stackTrace?.frames.orEmpty().take(16).map {
                            JsonPrimitive("${it.method.type.name}.${it.method.name}")
                        }))
                    }.toString())
                }
            }
        }
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
