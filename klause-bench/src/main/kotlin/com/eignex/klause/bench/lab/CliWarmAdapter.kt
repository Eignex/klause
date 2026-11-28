package com.eignex.klause.bench.lab

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.profile.CliStartupProfile
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchCli
import com.eignex.lab.bench.BenchContext
import com.eignex.lab.bench.BenchDescriptor
import com.eignex.lab.bench.BenchInput
import com.eignex.lab.bench.BenchInputRoot
import com.eignex.lab.bench.BenchMetric
import com.eignex.lab.bench.BenchRequest
import com.eignex.lab.bench.BenchResources
import com.eignex.lab.bench.BenchResult
import com.eignex.lab.bench.BenchSuite
import com.eignex.lab.bench.MetricDirection
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Paired repeat-invocation measurements of installed JVM CLI entry points. */
fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    BenchCli.execute(CliWarmSuite, args, stdout = protocolOutput)
}

private object CliWarmSuite : BenchSuite {
    private const val REPETITIONS = 100
    private const val WARMUPS = 25

    override fun describe() = BenchDescriptor("cli-warm", "Warm installed CLI entry-point latency and CPU")

    override fun plan(request: BenchRequest): List<BenchCase> {
        require(request.parameters.keys == setOf("sources", "seed"))
        val seed = request.parameters.getValue("seed").toLong()
        return request.parameters.getValue("sources").split(',').map { descriptor ->
            val hash = descriptor.substringBefore(':')
            val path = descriptor.substringAfter(':')
            require(hash.matches(Regex("[0-9a-f]{64}")) && path.startsWith("klause-bench/smoke-corpus/"))
            val problem = path.removePrefix("klause-bench/smoke-corpus/")
            val key = "cli-warm/$problem/$seed"
            BenchCase(key, mapOf("seed" to "$seed"), BenchResources(1, 4096, 120),
                inputs = listOf(BenchInput(path, BenchInputRoot.SOURCE, "model", hash)),
                attributes = mapOf("suite" to "cli-warm", "problem" to problem, "seed" to "$seed",
                    "comparisonKey" to key, "pairKey" to key, "recordSchema" to "cli-warm-v1"))
        }
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        LabInputs.verify(case, context)
        val input = case.inputs.single()
        val source = LabInputs.path(input, context).toFile()
        val out = context.outputDirectory.toFile()
        val root = requireNotNull(context.worktree).toFile()
        val seed = case.parameters.getValue("seed").toLong()
        CliStartupProfile.captureWarm(root, source, out, REPETITIONS, seed)
        val outputs = (1..REPETITIONS).map { out.resolve("warm-$it.out") }
        val protocolHashes = outputs.map { nativeOutcome(it, source.extension == "mps") }.distinct()
        check(protocolHashes.size == 1) { "Warm terminal protocol differed on ${source.name}" }
        val outputHashes = outputs.associateWith { sha256(it.readBytes()) }
        out.resolve("output-sha256.tsv").writeText(outputHashes.entries.joinToString("\n", postfix = "\n") {
            "${it.key.name}\t${it.value}"
        })
        val outputVariants = outputs.distinctBy { outputHashes.getValue(it) }
        val retainedOutputs = (outputVariants + outputs.last()).distinct()
        for (duplicate in outputs - retainedOutputs.toSet()) check(duplicate.delete())
        val timings = out.resolve("warm.tsv").readLines().drop(1).map { line ->
            line.split('\t').map(String::toLong)
        }
        check(timings.size == REPETITIONS && timings.all { it.size == 5 && it.drop(1).all { ns -> ns > 0 } })
        val warm = timings.drop(WARMUPS)
        fun time(name: String, ns: Long) = BenchMetric(name, ns / 1_000_000.0, "ms", MetricDirection.LOWER)
        fun median(column: Int): Long = warm.map { it[column] }.sorted()[warm.size / 2]
        val first = timings.first()
        val warmCpuPerInvocation = (warm.last()[4] - warm.first()[3]) / warm.size
        val metrics = listOf(time("firstWallMs", first[1]), time("firstProcessCpuMs", first[4] - first[3]),
            time("warmWallMs", median(1)), time("warmThreadCpuMs", median(2)),
            time("warmProcessCpuMs", warmCpuPerInvocation))
        val details = buildJsonObject {
            put("schema", "cli-warm-v1")
            put("sourceSha256", input.sha256)
            put("terminalProtocolSha256", protocolHashes.single())
            put("stdoutVariants", outputVariants.size)
            put("solverSeed", seed)
            put("nodeLimit", 100_000)
            put("exact", true)
            put("warmups", WARMUPS)
            put("measured", warm.size)
            put("javaRuntime", System.getProperty("java.runtime.version"))
        }
        val artifacts = (listOf("warm.tsv", "warm.time", "warm.err", "output-sha256.tsv") + retainedOutputs.map { it.name })
            .map { context.artifact(it) }
        return BenchResult(case.id, metrics = metrics, artifacts = artifacts, details = details)
    }

    private fun nativeOutcome(output: File, mps: Boolean): String {
        if (!mps) return sha256(output.readBytes())
        val lines = output.readLines()
        val status = lines.indexOfLast { it.startsWith("s ") }
        val finalObjective = lines.take(status.coerceAtLeast(0)).indexOfLast { it.startsWith("o ") }
        if (finalObjective < 0) return sha256(output.readBytes())
        val policies = lines.filter { it.startsWith("c numericalSemantics=") || it.startsWith("c optimalityTolerance=") }
        return sha256((policies + lines.drop(finalObjective)).joinToString("\n").encodeToByteArray())
    }
}
