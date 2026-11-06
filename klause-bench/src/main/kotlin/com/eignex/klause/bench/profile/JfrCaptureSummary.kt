package com.eignex.klause.bench.profile

import com.eignex.lab.bench.*
import com.github.luben.zstd.ZstdInputStreamNoFinalizer
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
        val samples = Samples(case.parameters["stack-depth"]?.toInt() ?: DEFAULT_STACK_DEPTH)
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
        val events = case.parameters["events"]?.toBooleanStrict() ?: false
        if (events) HarnessRecording.extract(context.outputDirectory, capture,
            "retained capture: ${case.parameters.getValue("source")}; original recording scope applies")
        val error = context.outputDirectory.resolve("harness-analysis-error.txt")
        val artifacts = listOf("capture.jfr", "summary.json") +
            if (events) listOf("harness-events.jsonl", "harness-analysis.json", "harness-analysis-error.txt")
            else emptyList()
        return BenchResult(case.id,
            status = if (events && Files.exists(error)) BenchStatus.FAILED else BenchStatus.SUCCESS,
            artifacts = artifacts
                .filter { Files.isRegularFile(context.outputDirectory.resolve(it)) }
                .map { BenchArtifact(it) },
            details = summary)
    }

    internal fun decode(parameters: Map<String, String>, capture: Path) {
        validate(parameters)
        val codec = parameters.keys.single { it in PAYLOAD_KEYS }
        val encoded = ByteArrayInputStream(parameters.getValue(codec).toByteArray(Charsets.US_ASCII))
        val digest = MessageDigest.getInstance("SHA-256")
        Base64.getDecoder().wrap(encoded).use { compressed ->
            val decoded = when (codec) {
                "zstd-base64" -> ZstdInputStreamNoFinalizer(compressed)
                else -> GZIPInputStream(compressed)
            }
            decoded.use { input ->
                if (input is ZstdInputStreamNoFinalizer) input.setLongMax(23)
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
        }
        check(HexFormat.of().formatHex(digest.digest()) == parameters.getValue("sha256")) {
            "capture SHA-256 mismatch"
        }
    }

    internal class Samples(private val stackDepth: Int = DEFAULT_STACK_DEPTH) {
        private var sampleCount = 0L
        private var truncated = 0L
        private var depthLimited = 0L
        private var emptyStacks = 0L
        private val methods = HashMap<String, Long>()
        private val paths = HashMap<List<String>, Long>()
        private var droppedPaths = 0L

        init { require(stackDepth in 1..MAX_STACK_DEPTH) }

        fun record(frames: Sequence<String>, truncatedStack: Boolean) {
            sampleCount++
            if (truncatedStack) truncated++
            val seen = HashSet<String>()
            val path = ArrayList<String>()
            for ((depth, method) in frames.take(stackDepth + 1).withIndex()) {
                if (depth == stackDepth) {
                    depthLimited++
                    break
                }
                seen.add(method)
                path.add(method)
            }
            if (seen.isEmpty()) emptyStacks++
            if (path.isNotEmpty()) recordPath(path)
            for (method in seen) {
                check(method in methods || methods.size < 100_000) { "capture exceeds 100000 sampled methods" }
                methods[method] = (methods[method] ?: 0L) + 1L
            }
        }

        private fun recordPath(path: List<String>) {
            val count = paths[path]
            if (count != null) paths[path] = count + 1L
            else if (paths.size < MAX_RECORDED_PATHS) paths[path] = 1L
            else droppedPaths++
        }

        fun summary(source: String, sha256: String): JsonObject = buildJsonObject {
            put("source", source)
            put("sha256", sha256)
            put("samples", sampleCount)
            put("stackDepth", stackDepth)
            put("truncatedStacks", truncated)
            put("depthLimitedStacks", depthLimited)
            put("emptyStacks", emptyStacks)
            val printed = paths.entries.sortedWith(
                compareByDescending<Map.Entry<List<String>, Long>> { it.value }
                    .thenBy { it.key.joinToString("\n") },
            ).take(MAX_REPORTED_PATHS)
            put("callerPathGroups", paths.size)
            put("callerPathDroppedSamples", droppedPaths)
            put("callerPathUnreportedSamples", paths.values.sum() - printed.sumOf { it.value })
            put("callerPaths", buildJsonArray {
                for ((frames, count) in printed) add(buildJsonObject {
                    put("samples", count)
                    put("frames", buildJsonArray { frames.forEach { add(it) } })
                })
            })
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

    private const val DEFAULT_STACK_DEPTH = 32
    private const val MAX_STACK_DEPTH = 256
    private const val MAX_RECORDED_PATHS = 1024
    private const val MAX_REPORTED_PATHS = 32
    private val PAYLOAD_KEYS = setOf("gzip-base64", "zstd-base64")

    private fun validate(parameters: Map<String, String>) {
        val codec = requireNotNull(parameters.keys.intersect(PAYLOAD_KEYS).singleOrNull())
        val required = setOf("source", "sha256", codec)
        require(parameters.keys - setOf("events", "stack-depth") == required)
        parameters["stack-depth"]?.toInt()?.let { require(it in 1..MAX_STACK_DEPTH) }
        parameters["events"]?.toBooleanStrict()
        require(parameters.getValue("source").let { it.isNotBlank() && it.length <= 1024 })
        require(parameters.getValue("sha256").matches(Regex("[a-f0-9]{64}")))
        require(parameters.getValue(codec).length in 1..2_097_152)
    }
}
