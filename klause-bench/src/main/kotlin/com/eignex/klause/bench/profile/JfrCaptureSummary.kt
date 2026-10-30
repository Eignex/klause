package com.eignex.klause.bench.profile

import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.lab.bench.*
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files

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
        val encoded = context.outputDirectory.resolve("capture.jfr.gz.base64")
        Files.writeString(encoded, case.parameters.getValue("gzip-base64"))
        val script = File(CorpusFetcher.workspaceRoot(), "klause-bench/scripts/summarize-jfr.sh")
        val process = ProcessBuilder("bash", script.path, case.parameters.getValue("source"),
            case.parameters.getValue("sha256"), encoded.toString())
            .redirectOutput(context.outputDirectory.resolve("summary.stdout").toFile())
            .redirectError(context.outputDirectory.resolve("summary.stderr").toFile())
        process.environment()["OUT"] = context.outputDirectory.toString()
        val status = process
            .start()
            .waitFor()
        check(status == 0) { "JFR capture summary exited with status $status" }
        val summary = Files.readString(context.outputDirectory.resolve("summary.json"))
        return BenchResult(case.id,
            artifacts = listOf("capture.jfr", "summary.json", "summary.stdout", "summary.stderr").map {
                BenchArtifact(it)
            },
            details = BenchProtocol.json.parseToJsonElement(summary).jsonObject)
    }

    private fun validate(parameters: Map<String, String>) {
        require(parameters.keys == setOf("source", "sha256", "gzip-base64"))
        require(parameters.getValue("source").let { it.isNotBlank() && it.length <= 1024 })
        require(parameters.getValue("sha256").matches(Regex("[a-f0-9]{64}")))
        require(parameters.getValue("gzip-base64").length in 1..2_097_152)
    }
}
