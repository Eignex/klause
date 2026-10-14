package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.Xcsp3SourceChecker
import com.eignex.klause.bench.metric.Xcsp3SourceValidation
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

internal object XcspWitnessReplay {
    @Suppress("TooGenericExceptionCaught")
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Supply a base64 gzip JSON array of captured XCSP witnesses" }
        val bytes = GZIPInputStream(Base64.getDecoder().decode(args.joinToString("")).inputStream()).use { it.readBytes() }
        val records = Reports.json.decodeFromString<List<XcspWitnessRecord>>(bytes.toString(Charsets.UTF_8))
        require(records.isNotEmpty())
        val output = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        File(output, "records.json").writeBytes(bytes)
        Reports.writeJson(File(output, "checker-build.json").path, InstalledBuild.reference)
        for ((index, record) in records.withIndex()) {
            val directory = File(output, index.toString()).apply { mkdirs() }
            Reports.writeJson(File(directory, "original-record.json").path, record)
            try {
                val source = File(directory, "source.xml").apply { writeText(record.source) }
                val witness = File(directory, "witness.xml").apply { writeText(record.witness) }
                require(sha256(source.readBytes()) == record.sourceSha256) { "Original source hash differs" }
                require(sha256(witness.readBytes()) == record.witnessSha256) { "Original witness hash differs" }
                check(directory, source, witness, record.objective)
            } catch (error: Exception) {
                Reports.writeJson(File(directory, "failure.json").path, ReplayFailure(error.toString()))
            }
        }
    }

    private fun check(directory: File, source: File, witness: File, objective: String?) {
        val command = listOf(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx512m", "-XX:ActiveProcessorCount=1", "-cp", System.getProperty("java.class.path"),
            Xcsp3SourceChecker::class.java.name, source.absolutePath, witness.absolutePath,
        ) + listOfNotNull(objective)
        val stdout = File(directory, "checker.out")
        val stderr = File(directory, "checker.err")
        val process = ProcessBuilder(command).redirectOutput(stdout).redirectError(stderr).start()
        val completed = try {
            process.waitFor(10, TimeUnit.SECONDS)
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
        Reports.writeJson(File(directory, "checker-execution.json").path,
            XcspCheckerExecution(command, process.exitValue(), !completed))
        val result = if (completed) {
            Xcsp3SourceValidation.checkerResult(process.exitValue(), stdout.readText())
        } else {
            Xcsp3SourceChecker.result("unknown", "source checker timed out")
        }
        Reports.writeJson(File(directory, "source-check.json").path, result.copy(
            sourceHashes = mapOf("source.xml" to sha256(source.readBytes())),
        ))
    }
}

@Serializable
internal data class XcspWitnessRecord(
    val sourceRevision: String,
    val source: String,
    val sourceSha256: String,
    val witness: String,
    val witnessSha256: String,
    val objective: String? = null,
)

@Serializable
private data class XcspCheckerExecution(val command: List<String>, val exitCode: Int, val timedOut: Boolean)
