package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.ExactFlatZincValidation
import com.eignex.klause.bench.metric.ExactObjective
import com.eignex.klause.bench.metric.MiniZincSourceValidation
import com.eignex.klause.bench.metric.SourceValidation
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal object FznStreamChecker {
    @Suppress("TooGenericExceptionCaught")
    @JvmStatic
    fun main(args: Array<String>) {
        val bytes = decodeRecords(args)
        val records = Json.decodeFromString<List<CapturedStreamRecord>>(bytes.toString(Charsets.UTF_8))
        require(records.isNotEmpty())
        val output = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        File(output, "records.json").writeBytes(bytes)
        for ((index, record) in records.withIndex()) {
            val directory = File(output, index.toString()).apply { mkdirs() }
            Reports.writeJson(File(directory, "original-record.json").path, record)
            try {
                val source = File(directory, "input.fzn").apply { writeText(record.source) }
                val stdout = File(directory, "stdout").apply { writeText(record.stdout) }
                require(sha256(source.readBytes()) == record.sourceSha256) { "Original source hash differs" }
                require(sha256(stdout.readBytes()) == record.stdoutSha256) { "Original output hash differs" }
                val results = check(record.source, record.stdout, record.interrupted, record.minimumWitnesses)
                Reports.writeJson(File(directory, "source-check-fzn.json").path, results)
            } catch (error: Exception) {
                Reports.writeJson(File(directory, "failure.json").path, ReplayFailure(error.toString()))
            }
        }
    }

    internal fun decodeRecords(args: Array<String>): ByteArray {
        if (args.size == 1) return Base64.getDecoder().decode(args.single())
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(args.size == 2 && options.keys == setOf("gzip64", "sha256")) {
            "Supply a base64 record array or gzip64=<base64 gzip bytes> and sha256=<expanded digest>"
        }
        val encoded = options.getValue("gzip64")
        require(encoded.length <= 96 * 1024) { "Inline compressed records are oversized" }
        val compressed = Base64.getDecoder().decode(encoded)
        val bytes = GZIPInputStream(compressed.inputStream()).use { it.readNBytes(64 * 1024 * 1024 + 1) }
        require(bytes.size <= 64 * 1024 * 1024 && sha256(bytes) == options.getValue("sha256")) {
            "Record size or identity differs"
        }
        return bytes
    }

    fun check(source: String, output: String, interrupted: Boolean, minimumWitnesses: Int): List<SourceValidation> {
        require(minimumWitnesses > 0)
        require(!interrupted || output.lineSequence().none {
            it.startsWith("=====") || it.startsWith("% optimizationStatus=")
        }) {
            "Interrupted output carries terminal optimization metadata"
        }
        val publications = output.split("----------").dropLast(1)
        require(publications.size >= minimumWitnesses) { "Missing complete FlatZinc publications" }
        val solve = source.lineSequence().map { it.substringBefore('%') }.joinToString("\n")
            .split(';').single { it.trim().startsWith("solve ") }
        val sense = Regex("\\b(minimize|maximize)\\b").find(solve)?.value
        require(sense != null || Regex("\\bsatisfy\\s*$").containsMatchIn(solve)) { "Unsupported solve directive" }
        val costs = ArrayList<ExactObjective?>()
        val checked = publications.map { publication ->
            val exactCoordinates = MiniZincSourceValidation.exactCoordinates(publication + "----------")
            val costLines = publication.lineSequence().filter { it.startsWith("% objective=") }.toList()
            val helperCosts = Regex("(?m)^\\s*_objective\\s*=\\s*([^;]+);\\s*$").findAll(publication)
                .map { it.groupValues[1].trim() }.toList()
            val exactCosts = Regex("(?m)^\\s*_objective\\s*=\\s*([^;]+);\\s*$")
                .findAll(exactCoordinates.orEmpty()).map { it.groupValues[1].trim() }.toList()
            require(costLines.size <= 1 && helperCosts.size <= 1 && exactCosts.size <= 1) {
                "FlatZinc publication repeats its cost"
            }
            val commentCost = costLines.singleOrNull()?.substringAfter('=')
            val helperCost = helperCosts.singleOrNull()
            val exactCost = exactCosts.singleOrNull()
            val cost = if (sense != null) {
                requireNotNull(exactCost ?: commentCost ?: helperCost) {
                    "FlatZinc publication requires its exact cost"
                }
            } else {
                require(commentCost == null && helperCost == null && exactCost == null) {
                    "Satisfaction publication carries an objective cost"
                }
                null
            }
            val value = cost?.let { requireNotNull(ExactObjective.parse(it)) }
            if (commentCost != null) {
                require(requireNotNull(value).compareTo(requireNotNull(ExactObjective.parse(commentCost))) == 0) {
                    "Conflicting published FlatZinc costs"
                }
            }
            if (helperCost != null) {
                val helperValue = requireNotNull(ExactObjective.parse(helperCost))
                require(requireNotNull(value).compareTo(helperValue) == 0 ||
                    (exactCost != null && value.approximate()?.let { it == helperCost.toDoubleOrNull() } == true)) {
                    "Conflicting published FlatZinc costs"
                }
            }
            costs.add(value)
            val coordinates = exactCoordinates
                ?: MiniZincSourceValidation.candidate(publication + "----------")
                ?: error("FlatZinc publication has no complete coordinates")
            ExactFlatZincValidation.inspect(source, coordinates, cost)
        }
        if (sense != null && checked.all { it.status == "valid" }) {
            for ((prior, next) in costs.zipWithNext()) {
                val comparison = requireNotNull(next).compareTo(requireNotNull(prior))
                require(if (sense == "minimize") comparison < 0 else comparison > 0) {
                    "FlatZinc incumbent does not strictly improve in original direction"
                }
            }
        }
        return checked
    }
}
