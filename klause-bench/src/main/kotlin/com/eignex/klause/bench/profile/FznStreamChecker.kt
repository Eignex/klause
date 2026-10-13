package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.ExactFlatZincValidation
import com.eignex.klause.bench.metric.ExactObjective
import com.eignex.klause.bench.metric.MiniZincSourceValidation
import com.eignex.klause.bench.metric.SourceValidation
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

internal object FznStreamChecker {
    @Suppress("TooGenericExceptionCaught")
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Supply a base64 JSON array of captured FlatZinc records" }
        val bytes = Base64.getDecoder().decode(args.single())
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

    fun check(source: String, output: String, interrupted: Boolean, minimumWitnesses: Int): List<SourceValidation> {
        require(minimumWitnesses > 0)
        require(!interrupted || output.lineSequence().none {
            it.startsWith("=====") || it.startsWith("% optimizationStatus=")
        }) {
            "Interrupted output carries terminal optimization metadata"
        }
        val publications = output.split("----------").dropLast(1)
        require(publications.size >= minimumWitnesses) { "Missing complete FlatZinc publications" }
        val costs = ArrayList<ExactObjective?>()
        val checked = publications.map { publication ->
            val costLines = publication.lineSequence().filter { it.startsWith("% objective=") }.toList()
            val helperCosts = Regex("(?m)^\\s*_objective\\s*=\\s*([^;]+);\\s*$").findAll(publication)
                .map { it.groupValues[1].trim() }.toList()
            require(costLines.size <= 1 && helperCosts.size <= 1) { "FlatZinc publication repeats its cost" }
            val commentCost = costLines.singleOrNull()?.substringAfter('=')
            val helperCost = helperCosts.singleOrNull()
            val cost = requireNotNull(commentCost ?: helperCost) { "FlatZinc publication requires its exact cost" }
            if (commentCost != null && helperCost != null) {
                val commentValue = requireNotNull(ExactObjective.parse(commentCost))
                val helperValue = requireNotNull(ExactObjective.parse(helperCost))
                require(commentValue.compareTo(helperValue) == 0) {
                    "Conflicting published FlatZinc costs"
                }
            }
            costs.add(ExactObjective.parse(cost))
            val coordinates = MiniZincSourceValidation.exactCoordinates(publication + "----------")
                ?: MiniZincSourceValidation.candidate(publication + "----------")
                ?: error("FlatZinc publication has no complete coordinates")
            ExactFlatZincValidation.inspect(source, coordinates, cost)
        }
        if (checked.all { it.status == "valid" }) {
            val solve = source.lineSequence().map { it.substringBefore('%') }.joinToString("\n")
                .split(';').single { it.trim().startsWith("solve ") }
            val sense = requireNotNull(Regex("\\b(minimize|maximize)\\b").find(solve)).value
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
