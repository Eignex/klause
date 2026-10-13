package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

internal object SmtStreamReplay {
    // Every supplied record needs a check outcome even when an earlier record fails.
    @Suppress("TooGenericExceptionCaught")
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Supply a base64 JSON array of captured SMT records" }
        val bytes = Base64.getDecoder().decode(args.single())
        val records = Json.decodeFromString<List<CapturedStreamRecord>>(bytes.toString(Charsets.UTF_8))
        require(records.isNotEmpty())
        val output = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        File(output, "records.json").writeBytes(bytes)
        val checker = SmtStreamChecker(output)
        for ((index, record) in records.withIndex()) {
            val directory = File(output, index.toString()).apply { mkdirs() }
            Reports.writeJson(File(directory, "original-record.json").path, record)
            try {
                val source = File(directory, "input.smt2").apply { writeText(record.source) }
                val stdout = File(directory, "stdout").apply { writeText(record.stdout) }
                require(sha256(source.readBytes()) == record.sourceSha256) { "Original source hash differs" }
                require(sha256(stdout.readBytes()) == record.stdoutSha256) { "Original output hash differs" }
                checker.check(directory, source, record.interrupted, record.minimumWitnesses, provision = true)
            } catch (error: Exception) {
                Reports.writeJson(File(directory, "failure.json").path, ReplayFailure(error.toString()))
            }
        }
    }
}

@Serializable
internal data class CapturedStreamRecord(
    val sourceRevision: String,
    val source: String,
    val stdout: String,
    val sourceSha256: String,
    val stdoutSha256: String,
    val interrupted: Boolean = false,
    val minimumWitnesses: Int = 1,
)

@Serializable
internal data class ReplayFailure(val error: String)
