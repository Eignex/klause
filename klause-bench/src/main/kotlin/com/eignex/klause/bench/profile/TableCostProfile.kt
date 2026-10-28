package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.MiniZincCompatibility
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object TableCostProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys - "dataSha256" == setOf("suite", "problem", "sha256"))
        val reference = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use {
            it.readNBytes(64 * 1024 * 1024 + 1)
        }
        require(bytes.size <= 64 * 1024 * 1024 && sha256(bytes) == options.getValue("sha256"))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve(if (reference.format == Format.MINIZINC) "source.mzn" else "source.xml").writeBytes(bytes)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        val (problem, objective) = if (reference.format == Format.MINIZINC) {
            val data = CorpusFiles.open(CorpusFetcher.resolve(requireNotNull(reference.data))).use {
                it.readNBytes(64 * 1024 * 1024 + 1)
            }
            require(data.size <= 64 * 1024 * 1024 && sha256(data) == options.getValue("dataSha256"))
            out.resolve("source.dzn").writeBytes(data)
            out.resolve("compiler.txt").writeText(MiniZincCompatibility.compiler.toString())
            val runner = MiniZincRunner(timeoutSec = 60)
            runner.compileFzn(reference).copyTo(out.resolve("source.fzn"))
            runner.compileOutput(reference).copyTo(out.resolve("source.ozn"))
            val resolved = runner.resolve(reference)
            resolved.problem to requireNotNull(resolved.objective)
        } else {
            require(reference.format == Format.XCSP3 && "dataSha256" !in options)
            val parsed = Xcsp3.parse(bytes.decodeToString())
            parsed.problem to requireNotNull(parsed.objective).toLinearObjective().let {
                if (parsed.sense == ObjectiveSense.MAXIMIZE) it.negated() else it
            }
        }
        out.resolve("source.json").writeText(snapshot(problem, objective).toString())
        for (config in listOf("off", "default")) {
            val stop = Cancellation.after(30.seconds)
            val budget = PresolveBudget(1_650_000_000L)
            val start = System.nanoTime()
            val prepared = FinitePipeline.prepare(FinitePipelineRequest(
                problem, FiniteEngine.MIXED, objective,
                presolveConfig = PresolveConfig.parse(config), explicitPresolveConfig = true,
                cancellation = stop, solveCancellation = stop, presolveBudget = budget, randomSeed = 3L,
            ))
            val elapsed = System.nanoTime() - start
            val working = requireNotNull(prepared.objective)
            out.resolve("prepared-$config.json").writeText(buildJsonObject {
                put("revision", Reports.readGitSha())
                put("sourceSha256", sha256(bytes))
                put("java", System.getProperty("java.version"))
                put("os", System.getProperty("os.name"))
                put("arch", System.getProperty("os.arch"))
                put("preparationNs", elapsed)
                put("preparationWork", budget.spent())
                put("preparationWorkAllowance", budget.allowance)
                put("cancelled", stop())
                put("infeasible", prepared.presolve?.infeasible == true)
                put("searchExecuted", false)
                put("sourceVerdictClaim", false)
                put("working", snapshot(prepared.problem, working))
                val getter = working.javaClass.methods.single { it.name.startsWith("getBinaryIntegerSource") }
                val source = getter.invoke(working)
                put("binaryIntegerSourcePresent", source != null)
                if (source != null) {
                    put("binaryIntegerSource", snapshot(
                        source.javaClass.getMethod("getProblem").invoke(source) as Problem,
                        source.javaClass.getMethod("getObjective").invoke(source) as LinearObjective,
                    ))
                }
            }.toString())
        }
    }

    private fun snapshot(problem: Problem, objective: LinearObjective) = buildJsonObject {
        require(problem.numIntVars <= 100_000 && problem.numFactors <= 100_000)
        val weights = objective.intCoefficients
        val readers = Array(problem.numIntVars) { ArrayList<String>() }
        for ((index, factor) in problem.factors.withIndex()) {
            for (v in factor.intVars.distinct()) readers[v].add("$index:${factor::class.simpleName}")
        }
        put("boolVars", problem.numBoolVars)
        put("intVars", problem.numIntVars)
        put("objective", objective.toString())
        put("factorKinds", buildJsonObject {
            problem.factors.groupingBy { it::class.simpleName ?: "anonymous" }.eachCount()
                .forEach { (kind, count) -> put(kind, count) }
        })
        put("tableShapes", buildJsonObject {
            problem.factors.filterIsInstance<Table>().groupingBy { "${it.arity}/${it.numTuples}/${it.hi != null}" }
                .eachCount().forEach { (shape, count) -> put(shape, count) }
        })
        put("ternaryTables", buildJsonArray {
            for ((index, table) in problem.factors.withIndex()) {
                if (table !is Table || table.arity != 3) continue
                add(buildJsonObject {
                    put("factor", index)
                    put("rows", table.numTuples)
                    put("intervals", table.hi != null)
                    put("columns", buildJsonArray {
                        for (v in table.xs) add(buildJsonObject {
                            put("id", v)
                            put("domain", problem.intDomainOrNull(v).toString())
                            put("weight", weights.getOrElse(v) { 0L })
                            put("readers", buildJsonArray { readers[v].forEach { add(JsonPrimitive(it)) } })
                        })
                    })
                    if (table.numTuples <= 64) {
                        put("tuples", buildJsonArray { table.tuples.forEach { add(JsonPrimitive(it)) } })
                    }
                })
            }
        })
    }
}
