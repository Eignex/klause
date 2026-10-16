package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.ExactOpbValidation
import com.eignex.klause.bench.metric.SourceValidation
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.util.Base64
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal object OpbStreamChecker {
    @Suppress("TooGenericExceptionCaught")
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Supply a base64 JSON array of captured OPB records" }
        val bytes = Base64.getDecoder().decode(args.single())
        val records = Json.decodeFromString<List<CapturedStreamRecord>>(bytes.toString(Charsets.UTF_8))
        require(records.isNotEmpty())
        val output = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        File(output, "records.json").writeBytes(bytes)
        for ((index, record) in records.withIndex()) {
            val directory = File(output, index.toString()).apply { mkdirs() }
            Reports.writeJson(File(directory, "original-record.json").path, record)
            try {
                val source = File(directory, "input.opb").apply { writeText(record.source) }
                val stdout = File(directory, "stdout").apply { writeText(record.stdout) }
                require(sha256(source.readBytes()) == record.sourceSha256) { "Original source hash differs" }
                require(sha256(stdout.readBytes()) == record.stdoutSha256) { "Original output hash differs" }
                Reports.writeJson(File(directory, "source-check-opb.json").path,
                    check(record.source, record.stdout, record.interrupted, record.minimumWitnesses))
            } catch (error: Exception) {
                Reports.writeJson(File(directory, "failure.json").path, ReplayFailure(error.toString()))
            }
        }
    }

    fun check(source: String, output: String, interrupted: Boolean, minimumWitnesses: Int): List<SourceValidation> {
        require(minimumWitnesses > 0)
        val models = ArrayList<SourceValidation>()
        var cost: String? = null
        for (line in output.lineSequence()) {
            when {
                line.startsWith("o ") -> cost = line.substring(2).trim()
                line == "v" || line.startsWith("v ") ->
                    models.add(ExactOpbValidation.inspect(source, line.removePrefix("v").trim(), cost))
                interrupted && line.startsWith("s ") -> error("Interrupted output carries terminal status")
            }
        }
        require(models.size >= minimumWitnesses) { "Missing complete OPB publications" }
        return models
    }
}
