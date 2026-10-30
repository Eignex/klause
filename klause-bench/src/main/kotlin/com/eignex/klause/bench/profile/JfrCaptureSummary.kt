package com.eignex.klause.bench.profile

import com.eignex.lab.bench.*
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.zip.GZIPInputStream

internal object JfrCaptureSummary : BenchSuite {
    @JvmStatic
    fun main(args: Array<String>) = BenchCli.execute(this, args)

    override fun describe() = BenchDescriptor("jfr-capture-summary", "Sampled stacks from a retained hashed capture")

    override fun plan(request: BenchRequest): List<BenchCase> {
        validate(request.parameters)
        return listOf(BenchCase(
            "jfr/${request.parameters.getValue("sha256")}",
            request.parameters,
            BenchResources(cpus = 1, memoryMiB = 2048, timeoutSeconds = 120,
                requiredTags = setOf("platform:linux", "arch:x86_64")),
        ))
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        validate(case.parameters)
        Files.createDirectories(context.outputDirectory)
        val capture = context.outputDirectory.resolve("capture.jfr")
        decode(case.parameters, capture)
        val samples = Samples()
        RecordingFile(capture).use { recording ->
            while (recording.hasMoreEvents()) {
                val event = recording.readEvent()
                if (event.eventType.name != "jdk.ExecutionSample") continue
                val stack = event.stackTrace
                val methods = stack?.frames.orEmpty().asSequence().map {
                    it.method.type.name.replace('/', '.') + "." + it.method.name
                }
                samples.record(methods, stack?.isTruncated == true)
            }
        }
        val summary = samples.summary(case.parameters.getValue("source"), case.parameters.getValue("sha256"))
        Files.writeString(context.outputDirectory.resolve("summary.json"), summary.toString())
        return BenchResult(case.id,
            artifacts = listOf(BenchArtifact("capture.jfr"), BenchArtifact("summary.json")),
            details = summary)
    }

    private fun decode(parameters: Map<String, String>, capture: Path) {
        val encoded = ByteArrayInputStream(parameters.getValue("gzip-base64").toByteArray(Charsets.US_ASCII))
        val digest = MessageDigest.getInstance("SHA-256")
        GZIPInputStream(Base64.getDecoder().wrap(encoded)).use { input ->
            Files.newOutputStream(capture).use { output ->
                val buffer = ByteArray(8192)
                var bytes = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes += read
                    check(bytes <= 16_777_216) { "capture exceeds 16 MiB" }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        check(HexFormat.of().formatHex(digest.digest()) == parameters.getValue("sha256")) {
            "capture SHA-256 mismatch"
        }
    }

    internal class Samples {
        private var sampleCount = 0L
        private var truncated = 0L
        private var depthLimited = 0L
        private var emptyStacks = 0L
        private val methods = HashMap<String, Long>()

        fun record(frames: Sequence<String>, truncatedStack: Boolean) {
            sampleCount++
            if (truncatedStack) truncated++
            val seen = HashSet<String>()
            for ((depth, method) in frames.take(STACK_DEPTH + 1).withIndex()) {
                if (depth == STACK_DEPTH) {
                    depthLimited++
                    break
                }
                seen.add(method)
            }
            if (seen.isEmpty()) emptyStacks++
            for (method in seen) {
                check(method in methods || methods.size < 100_000) { "capture exceeds 100000 sampled methods" }
                methods[method] = (methods[method] ?: 0L) + 1L
            }
        }

        fun summary(source: String, sha256: String): JsonObject = buildJsonObject {
            put("source", source)
            put("sha256", sha256)
            put("samples", sampleCount)
            put("stackDepth", STACK_DEPTH)
            put("truncatedStacks", truncated)
            put("depthLimitedStacks", depthLimited)
            put("emptyStacks", emptyStacks)
            put("sampledMethods", buildJsonArray {
                for ((method, count) in methods.toList().sortedWith(
                    compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first },
                )) add(buildJsonObject {
                    put("method", method)
                    put("samples", count)
                })
            })
        }
    }

    private const val STACK_DEPTH = 32

    private fun validate(parameters: Map<String, String>) {
        require(parameters.keys == setOf("source", "sha256", "gzip-base64"))
        require(parameters.getValue("source").let { it.isNotBlank() && it.length <= 1024 })
        require(parameters.getValue("sha256").matches(Regex("[a-f0-9]{64}")))
        require(parameters.getValue("gzip-base64").length in 1..2_097_152)
    }
}
