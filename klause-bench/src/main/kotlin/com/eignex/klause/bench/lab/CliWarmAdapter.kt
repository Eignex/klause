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

/** Paired repeat-invocation measurements of installed JVM CLI entry points. */
fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    BenchCli.execute(CliWarmSuite, args, stdout = protocolOutput)
}

private object CliWarmSuite : BenchSuite {
    private const val REPETITIONS = 18
    private const val WARMUPS = 5

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
            put("stdoutSha256", sha256(out.resolve("warm-1.out").readBytes()))
            put("solverSeed", seed)
            put("nodeLimit", 100_000)
            put("exact", true)
            put("warmups", WARMUPS)
            put("measured", warm.size)
            put("javaRuntime", System.getProperty("java.runtime.version"))
        }
        val artifacts = listOf("warm.tsv", "warm.time", "warm.err", "warm-1.out", "warm-$REPETITIONS.out")
            .map { context.artifact(it) }
        return BenchResult(case.id, metrics = metrics, artifacts = artifacts, details = details)
    }
}
