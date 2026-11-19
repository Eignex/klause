package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.lab.configureLabPaths
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.InProcessRunner
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.util.ExecutionControl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration.Companion.seconds

internal object ObjectiveSymmetryCapture {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty())
        configureLabPaths()
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        args.forEachIndexed { index, selection ->
            val target = out.resolve("$index").apply { mkdirs() }
            val report = runCatching { inspect(selection, target) }.getOrElse { failure ->
                buildJsonObject {
                    put("selection", selection)
                    put("failure", failure.toString())
                    put("sourceVerdictClaim", false)
                }
            }
            target.resolve("capture.json").writeText("$report\n")
            println(report)
        }
    }

    private fun inspect(selection: String, out: File) = buildJsonObject {
        val suite = selection.substringBefore('/')
        val name = selection.substringAfter('/')
        val reference = Catalog.named(suite, listOf(name)).single()
        val file = CorpusFetcher.resolve(reference.source)
        val bytes = CorpusFiles.open(file).use { it.readBytes() }
        out.resolve("source.txt").writeBytes(bytes)
        put("selection", selection)
        put("sourceSha256", sha256(bytes))
        put("sourceBytes", bytes.size)
        put("sourceVerdictClaim", false)
        val resolved = if (reference.format == Format.MINIZINC) {
            MiniZincRunner().resolve(reference)
        } else {
            InProcessRunner.resolve(reference)
        }
        val source = resolved.problem
        val objective = requireNotNull(resolved.objective) { "optimization source required" }
        put("sourceHasSymmetryBreaking", source.hasSymmetryBreaking)
        put("sourceFactors", source.factors.size)
        put("sourceIntegerColumns", source.numIntVars)
        put("sourceBooleanColumns", source.numBoolVars)
        put("arms", JsonArray(listOf("default,-objective-symmetry", "default").map { spec ->
            val stop = ExecutionControl.after(30.seconds)
            val budget = PresolveBudget(1_650_000_000L)
            val start = System.nanoTime()
            val prepared = FinitePipeline.prepare(FinitePipelineRequest(
                source, FiniteEngine.BACKTRACK, objective, PresolveConfig.parse(spec),
                cancellation = stop, solveCancellation = stop, presolveBudget = budget, randomSeed = 1L,
            ))
            val elapsed = System.nanoTime() - start
            val model = prepared.problem
            val intCosts = prepared.objective?.intCoefficients ?: LongArray(0)
            val boolCosts = prepared.objective?.boolWeights ?: LongArray(0)
            val generators = model.factors.filter { it.javaClass.simpleName == "SymmetryHandling" }.flatMap { factor ->
                @Suppress("UNCHECKED_CAST")
                factor.javaClass.getMethod("getGenerators").invoke(factor) as List<Pair<IntArray, IntArray>>
            }
            buildJsonObject {
                put("presolve", spec)
                put("preparationNs", elapsed)
                put("preparationWork", budget.spent())
                put("cancelled", stop())
                put("generators", generators.size)
                put("objectiveMovingGenerators", generators.count { (ints, bools) ->
                    ints.indices.any { ints[it] != it && intCosts.getOrElse(it) { 0L } != 0L } ||
                        bools.indices.any { bools[it] != it && boolCosts.getOrElse(it) { 0L } != 0L }
                })
                put("passes", JsonArray(prepared.presolve?.passes.orEmpty().map(::JsonPrimitive)))
                put("integerColumns", model.numIntVars)
                put("booleanColumns", model.numBoolVars)
                put("factors", model.factors.size)
            }
        }))
    }
}
