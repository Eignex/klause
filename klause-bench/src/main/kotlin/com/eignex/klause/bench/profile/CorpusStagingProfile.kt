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
import kotlinx.serialization.encodeToString
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
        require(request.keys == setOf("suite", "problem", "sha256", "budget-ms", "repeats"))
        val budget = request.getValue("budget-ms").toLong().also { require(it in 1..60_000) }
        val repeats = request.getValue("repeats").toInt().also { require(it in 2..4) }
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val reference = Catalog.uncapped(request.getValue("suite")).problems
            .single { it.name == request.getValue("problem") }
        capture(CorpusFetcher.resolve(reference.source), out, budget, repeats, request.getValue("sha256"))
    }

    internal fun capture(packed: File, out: File, budget: Long, repeats: Int = 2, expectedHash: String? = null) {
        require(budget in 1..60_000 && repeats in 2..4)
        val profileStart = System.nanoTime()
        out.resolve("request.json").writeText(buildJsonObject {
            put("source", packed.absolutePath)
            put("expectedSha256", expectedHash)
            put("budgetMs", budget)
            put("repeats", repeats)
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
                put("stagingFileStore", Files.getFileStore(File(CorpusFetcher.cacheRoot, ".plain").toPath()).toString())
                put("setupNanos", System.nanoTime() - setupStart)
            }.toString())
            val modes = listOf("packed-read", "decode-hash", "stage", "plain-hash")
            for (mode in modes) {
                val command = listOf(File(System.getProperty("java.home"), "bin/java").absolutePath,
                    "-Xmx512m", "-XX:ActiveProcessorCount=1", "--enable-native-access=ALL-UNNAMED",
                    "-Dklause.bench.corpusCache=${CorpusFetcher.cacheRoot}",
                    "-XX:StartFlightRecording=filename=${out.resolve("$mode.jfr")},settings=profile,dumponexit=true",
                    "-Xlog:jfr+startup=warning",
                    "-cp", System.getProperty("java.class.path"), CorpusStagingProfile::class.java.name,
                    "trial-path=${if (mode == "plain-hash") plain else packed}", "mode=$mode",
                    "sha256=$sourceHash", "budget-ms=$budget", "repeats=$repeats")
                out.resolve("$mode-command.json").writeText(Reports.json.encodeToString(command))
                val process = ProcessBuilder(command).redirectOutput(out.resolve("$mode.jsonl"))
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
        require(mode in setOf("packed-read", "decode-hash", "stage", "plain-hash"))
        val budget = request.getValue("budget-ms").toLong()
        val cpu = ManagementFactory.getThreadMXBean()
        repeat(request.getValue("repeats").toInt()) { index ->
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
            val failure = runCatching {
                when (mode) {
                    "stage" -> CorpusFiles.withPlainFile(source, ::checkActive, { cleanupNanos = it }) { plain ->
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
            println(buildJsonObject {
                put("mode", mode)
                put("operation", index)
                put("jvmCache", if (index == 0) "fresh-first-operation" else "same-process-repeated-operation")
                put("osCache", "uncontrolled; parent read and materialization completed before child startup")
                put("sourcePath", source.absolutePath)
                put("sourceFileBytes", source.length())
                put("stagedBytes", stagedBytes)
                put("sha256", observedHash)
                put("matchesPlainSource", observedHash?.let { it == request.getValue("sha256") })
                put("sourceAllowanceMs", budget)
                put("stagingNanos", stagingNanos)
                put("cleanupNanos", cleanupNanos)
                put("verificationNanos", verificationNanos)
                put("totalNanos", total)
                put("threadCpuNanos", if (cpuStart >= 0L && cpuEnd >= 0L) cpuEnd - cpuStart else null)
                put("failure", failure)
            })
            System.out.flush()
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

    private const val NANOS_PER_MILLI = 1_000_000L
}
