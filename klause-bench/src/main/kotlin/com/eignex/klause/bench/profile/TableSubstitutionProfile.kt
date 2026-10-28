package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

internal object TableSubstitutionProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args.single().startsWith("request64="))
        val request = Base64.getDecoder().decode(args.single().substringAfter('='))
        require(request.size <= 16 * 1024)
        val sources = Reports.json.parseToJsonElement(request.decodeToString()).jsonArray
        require(sources.size in 1..8)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        out.resolve("request.json").writeBytes(request)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
        allocation.isThreadAllocatedMemoryEnabled = true
        sources.forEachIndexed { index, input ->
            val source = input.jsonObject
            require(source.keys == setOf("suite", "problem", "sha256"))
            val bytes = trajectorySourceBytes(source.getValue("suite").jsonPrimitive.content,
                source.getValue("problem").jsonPrimitive.content, source.getValue("sha256").jsonPrimitive.content, null)
            val directory = out.resolve("$index").apply { mkdirs() }
            directory.resolve("source.xml").writeBytes(bytes)
            for (mode in listOf("default", "default,+table-substitution")) {
                val stop = Cancellation.after(8.seconds)
                val parsed = Xcsp3.parse(bytes.decodeToString(), recoveryCancellation = stop)
                val objective = parsed.objective?.toLinearObjective()
                val budget = PresolveBudget(550_000_000L)
                val allocated = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId())
                val started = System.nanoTime()
                val prepared = FinitePipeline.prepare(FinitePipelineRequest(
                    parsed.problem, FiniteEngine.BACKTRACK, objective, PresolveConfig.parse(mode),
                    explicitPresolveConfig = true, cancellation = stop, solveCancellation = stop,
                    presolveBudget = budget, randomSeed = 3,
                ))
                val elapsed = System.nanoTime() - started
                val allocatedBytes = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated
                val model = prepared.problem
                val baked = model as? BakedProblem
                val costs = prepared.objective?.intCoefficients ?: longArrayOf()
                directory.resolve(if (mode == "default") "control.json" else "substitution.json")
                    .writeText(buildJsonObject {
                        put("input", input)
                        put("revision", Reports.readGitSha())
                        put("mode", mode)
                        put("sourceObjective", objective.toString())
                        put("preparedObjective", prepared.objective.toString())
                        put("sourceIntegers", parsed.problem.numIntVars)
                        put("preparedIntegers", model.numIntVars)
                        put("preparedBooleans", model.numBoolVars)
                        put("preparedFactors", model.factors.size)
                        put("preparationNs", elapsed)
                        put("preparationAllocatedBytes", allocatedBytes)
                        put("preparationWork", budget.spent())
                        put("stopped", stop())
                        put("infeasible", prepared.presolve?.infeasible)
                        put("tables", model.factors.count { it is Table })
                        put("objectiveCoordinates", buildJsonArray {
                            for (v in costs.indices) if (costs[v] != 0L) add(buildJsonObject {
                                put("variable", v)
                                put("coefficient", costs[v])
                                put("domain", baked?.rootIntDomain(v).toString())
                                put("readerKinds", model.factors.filter { v in it.intVars }
                                    .groupingBy { it::class.simpleName }.eachCount().toString())
                            })
                        })
                        put("passes", JsonArray(prepared.presolve?.passes.orEmpty().map(::JsonPrimitive)))
                        put("invocations", buildJsonArray {
                            for (call in budget.invocations) add(buildJsonObject {
                                put("lane", call.lane.name)
                                put("pass", call.pass)
                                put("outcome", call.outcome.name)
                                put("work", call.work)
                                put("ns", call.elapsed.inWholeNanoseconds)
                                put("narrowedColumns", call.narrowedColumns)
                            })
                        })
                        put("searchExecuted", false)
                        put("independentSourceValidation", false)
                    }.toString())
                println("${source.getValue("problem")}: $mode, ${model.factors.size} factors, stopped=${stop()}")
            }
        }
    }
}
