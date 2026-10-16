package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.tools.Profiler
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Summarize retained CLI recordings on their remote lab host without executing a solver. */
object CliRecordingSummary {
    internal const val MAX_RECORDING_BYTES = 16L * 1024 * 1024
    private const val MAX_METADATA_BYTES = 4L * 1024 * 1024

    /** Resolve hash-pinned recordings from the sibling job store exposed by `JOB_DIR`. */
    @JvmStatic
    fun main(args: Array<String>) {
        val request = parse(args)
        val job = Path.of(requireNotNull(System.getenv("JOB_DIR"))).toRealPath()
        val jobs = requireNotNull(job.parent)
        val out = Path.of(requireNotNull(System.getenv("OUT")))
        Files.createDirectories(out)
        val parserArguments = ManagementFactory.getRuntimeMXBean().inputArguments
        Reports.writeJson(out.resolve("parser.json").toString(), buildJsonObject {
            put("parserHead", requireNotNull(Reports.readGitSha()))
            put("parserBuild", Reports.json.encodeToJsonElement(InstalledBuild.reference))
            put("parserJvmArguments", Reports.json.encodeToJsonElement(parserArguments))
            put("parserJavaVersion", System.getProperty("java.version"))
            put("sourceJob", request.sourceJob)
            put("scope", "retained-recording-postprocessing")
            put("solverExecuted", false)
        })
        for ((index, recording) in request.recordings.withIndex()) {
            val input = load(jobs, request.sourceJob, recording)
            val summary = out.resolve("recording-$index.txt")
            PrintStream(Files.newOutputStream(summary), false, Charsets.UTF_8).use { stream ->
                Profiler.summarizeCli(input.path.toFile(), stream)
                check(!stream.checkError()) { "Summary output failed" }
            }
            check(hash(input.path, MAX_RECORDING_BYTES) == recording.sha256) { "Recording changed during replay" }
            Reports.writeJson(out.resolve("recording-$index.json").toString(), buildJsonObject {
                put("sourceJob", request.sourceJob)
                put("caseIndex", recording.caseIndex)
                put("measurementId", recording.measurementId)
                put("recordingSha256", recording.sha256)
                put("recordingBytes", Files.size(input.path))
                put("measurementSha256", input.measurementSha256)
                put("measurement", input.measurement)
                put("originalRecordAvailable", input.record != null)
                input.record?.let {
                    put("originalRecordHead", it.getValue("gitSha"))
                    put("originalSourceHashes", it.getValue("sourceHashes"))
                }
                put("summarySha256", hash(summary, MAX_METADATA_BYTES))
                put("solverExecuted", false)
            })
            println("Summarized retained job ${request.sourceJob}, case ${recording.caseIndex}")
        }
    }

    internal data class Recording(val caseIndex: Int, val measurementId: String, val sha256: String)
    internal data class Request(val sourceJob: Int, val recordings: List<Recording>)
    internal data class Input(
        val path: Path,
        val measurement: JsonObject,
        val measurementSha256: String,
        val record: JsonObject?,
    )

    internal fun parse(args: Array<String>): Request {
        require(args.all { it.startsWith("sourceJob=") || it.startsWith("recording=") }) { "Unknown argument" }
        val jobs = args.filter { it.startsWith("sourceJob=") }
        require(jobs.size == 1) { "Exactly one sourceJob is required" }
        val job = jobs.single().substringAfter('=')
        require(job.matches(Regex("[1-9][0-9]*")) && job.toIntOrNull() != null) { "Invalid sourceJob" }
        val inputs = args.filter { it.startsWith("recording=") }
        require(inputs.size in 1..8) { "Select one to eight recordings" }
        val recordings = inputs.map { argument ->
            val parts = argument.substringAfter('=').split(':')
            require(parts.size == 3) { "Use recording=case:measurementUUID:sha256" }
            val case = parts[0]
            require(case.matches(Regex("0|[1-9][0-9]*")) && case.toIntOrNull() != null) { "Invalid case" }
            val uuid = UUID.fromString(parts[1]).toString()
            require(uuid == parts[1]) { "Measurement UUID must be canonical" }
            require(parts[2].matches(Regex("[0-9a-f]{64}"))) { "Invalid recording hash" }
            Recording(case.toInt(), uuid, parts[2])
        }
        require(recordings.map { it.caseIndex to it.measurementId }.distinct().size == recordings.size) {
            "Duplicate recording"
        }
        return Request(job.toInt(), recordings)
    }

    internal fun load(jobs: Path, sourceJob: Int, recording: Recording): Input {
        val root = jobs.toRealPath()
        val selectedJob = root.resolve(sourceJob.toString())
        val sourceRoot = selectedJob.toRealPath()
        require(sourceRoot == selectedJob && sourceRoot.startsWith(root)) { "Selected job escaped the job store" }
        val relative = Path.of("cases", recording.caseIndex.toString(),
            "profile-${recording.measurementId}")
        val path = retainedFile(sourceRoot, relative.resolve("cli.jfr"))
        require(hash(path, MAX_RECORDING_BYTES) == recording.sha256) { "Recording identity differs" }
        val metadata = readObject(retainedFile(sourceRoot, relative.resolve("measurement.json")))
        val measurement = metadata.value
        require(measurement.getValue("schemaVersion").jsonPrimitive.int == 1)
        require(measurement.getValue("scope").jsonPrimitive.content == "whole-cli-jvm")
        require(measurement.getValue("caseIndex").jsonPrimitive.int == recording.caseIndex)
        require(measurement.getValue("measurementId").jsonPrimitive.content == recording.measurementId)
        val recordHash = measurement["recordSha256"]?.jsonPrimitive?.contentOrNull
        val record = recordHash?.let { expected ->
            require(expected.matches(Regex("[0-9a-f]{64}")))
            val snapshot = readObject(retainedFile(sourceRoot, relative.resolve("solve-record.json")))
            require(snapshot.sha256 == expected) { "Original record identity differs" }
            snapshot.value
        }
        return Input(path, measurement, metadata.sha256, record)
    }

    private fun retainedFile(root: Path, relative: Path): Path {
        val expected = root.resolve(relative).normalize()
        require(expected.startsWith(root)) { "Recording escaped the job store" }
        val real = expected.toRealPath()
        require(real == expected && real.startsWith(root) && Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            "Recording must be an unlinked regular file in the selected job"
        }
        return real
    }

    private class Metadata(val value: JsonObject, val sha256: String)

    private fun readObject(path: Path): Metadata {
        require(Files.size(path) <= MAX_METADATA_BYTES) { "Metadata is oversized" }
        val bytes = Files.newInputStream(path).use { it.readNBytes((MAX_METADATA_BYTES + 1L).toInt()) }
        require(bytes.size <= MAX_METADATA_BYTES) { "Metadata grew during reading" }
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return Metadata(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject, digest)
    }

    private fun hash(path: Path, limit: Long): String {
        val size = Files.size(path)
        require(size in 1..limit) { "Retained file size is unsupported" }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                bytes += count
                require(bytes <= limit) { "Retained file grew during reading" }
                digest.update(buffer, 0, count)
            }
        }
        require(bytes == size) { "Retained file changed during reading" }
        return HexFormat.of().formatHex(digest.digest())
    }
}
