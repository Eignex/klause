package com.eignex.klause.bench.lab

import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchCli
import com.eignex.lab.bench.BenchContext
import com.eignex.lab.bench.BenchDescriptor
import com.eignex.lab.bench.BenchMetric
import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchRequest
import com.eignex.lab.bench.BenchResources
import com.eignex.lab.bench.BenchResult
import com.eignex.lab.bench.BenchSuite
import com.eignex.lab.bench.MetricDirection
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Paired production clausal-walk buffer allocation and latency measurements. */
fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    configureLabPaths()
    BenchCli.execute(ClausePrimalBuffersSuite, args, stdout = protocolOutput)
}

internal object ClausePrimalBuffersSuite : BenchSuite {
    override fun describe() = BenchDescriptor("clause-primal-buffers", "Production clausal repair buffer costs")

    override fun plan(request: BenchRequest): List<BenchCase> {
        require(request.parameters.keys - setOf("groups", "costs", "seeds") == emptySet<String>())
        val groups = (request.parameters["groups"] ?: "22,1366,21846").split(',').map(String::toInt)
        val costs = (request.parameters["costs"] ?: "2,64").split(',').map(String::toInt)
        val seeds = (request.parameters["seeds"] ?: "1,3,7").split(',').map(String::toLong)
        require(groups.all { it in 2..22_000 } && costs.all { it in setOf(2, 64) })
        require(groups.size * costs.size * seeds.size in 1..32)
        return groups.flatMap { size -> costs.flatMap { count -> seeds.map { seed ->
            val id = "groups-$size/costs-$count/seed-$seed"
            BenchCase(id, parameters = mapOf("groups" to "$size", "costs" to "$count", "seed" to "$seed"),
                resources = BenchResources(1, 2048, 120),
                attributes = mapOf("suite" to "clause-primal-buffers", "problem" to id,
                    "comparisonKey" to id, "pairKey" to id, "recordSchema" to "clause-primal-buffers-v1"))
        } } }
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val root = CorpusFetcher.workspaceRoot()
        val profile = root.resolve("klause/build/lp-profile/jvm")
        val classpath = listOf(profile.resolve("classes"), profile.resolve("lib/*"))
            .joinToString(File.pathSeparator) { it.absolutePath }
        val out = context.outputDirectory
        Files.createDirectories(out)
        val report = out.resolve("buffers.json")
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val argv = listOf(java, "-Xms512m", "-Xmx512m", "-XX:ActiveProcessorCount=1",
            "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector", "-cp", classpath,
            "com.eignex.klause.bench.profile.ClausePrimalBuffersProfile", case.parameters.getValue("groups"),
            case.parameters.getValue("costs"), case.parameters.getValue("seed"), report.toAbsolutePath().toString())
        val process = ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(out.resolve("profile.log").toFile())
            .start()
        try {
            check(process.waitFor(100, TimeUnit.SECONDS)) { "Clausal buffer profile timed out" }
            check(process.exitValue() == 0) { "Clausal buffer profile failed; see profile.log" }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        val details = BenchProtocol.json.parseToJsonElement(Files.readString(report)).jsonObject
        val metrics = listOf("constructionBytes" to "bytes/walk", "trialBytes" to "bytes/trial",
            "constructionNs" to "ns/walk", "trialNs" to "ns/trial").map { (name, unit) ->
            BenchMetric(name, details.getValue(name).jsonPrimitive.double, unit, MetricDirection.LOWER)
        }
        return BenchResult(case.id, metrics = metrics, artifacts = context.artifacts(), details = details)
    }
}
