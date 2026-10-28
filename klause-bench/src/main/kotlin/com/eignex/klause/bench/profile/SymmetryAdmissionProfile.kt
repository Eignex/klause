package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

internal object SymmetryAdmissionProfile {
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
        sources.forEachIndexed { index, input ->
            val source = input.jsonObject
            require(source.keys == setOf("suite", "problem", "sha256"))
            val bytes = trajectorySourceBytes(source.getValue("suite").jsonPrimitive.content,
                source.getValue("problem").jsonPrimitive.content, source.getValue("sha256").jsonPrimitive.content, null)
            val directory = out.resolve("$index").apply { mkdirs() }
            val booleanSource = source.getValue("suite").jsonPrimitive.content == "wcnf-core"
            directory.resolve(if (booleanSource) "source.wcnf" else "source.xml").writeBytes(bytes)
            for (mode in listOf("default", "default,+objective-symmetry")) {
                val stop = Cancellation.after(8.seconds)
                val (problem, objective) = if (booleanSource) {
                    val parsed = Dimacs.parseWcnf(bytes.decodeToString()).toProblem()
                    parsed.problem to parsed.objective.toLinearObjective()
                } else {
                    val parsed = Xcsp3.parse(bytes.decodeToString(), recoveryCancellation = stop)
                    parsed.problem to requireNotNull(parsed.objective).toLinearObjective()
                }
                val budget = PresolveBudget(550_000_000L)
                val started = System.nanoTime()
                val prepared = FinitePipeline.prepare(FinitePipelineRequest(
                    problem, FiniteEngine.BACKTRACK, objective, PresolveConfig.parse(mode),
                    explicitPresolveConfig = true, cancellation = stop, solveCancellation = stop,
                    presolveBudget = budget, randomSeed = 3,
                ))
                val elapsed = System.nanoTime() - started
                val cost = requireNotNull(prepared.objective)
                val ints = cost.intCoefficients
                val bools = cost.boolWeights
                val model = prepared.problem
                val bakedModel = model as? BakedProblem
                val report = buildJsonObject {
                    put("input", input)
                    put("revision", Reports.readGitSha())
                    put("mode", mode)
                    put("sourceBreaksSymmetry", problem.hasSymmetryBreaking)
                    put("sourceObjective", objective.toString())
                    put("preparedObjective", cost.toString())
                    put("sourceIntegers", problem.numIntVars)
                    put("sourceBooleans", problem.numBoolVars)
                    put("preparedIntegers", model.numIntVars)
                    put("preparedBooleans", model.numBoolVars)
                    put("preparedFactors", model.factors.size)
                    put("rootDomainsAvailable", bakedModel != null)
                    put("rootIntegerDomains", buildJsonArray {
                        if (bakedModel != null) for (v in 0 until bakedModel.numIntVars) {
                            add(JsonPrimitive(bakedModel.rootIntDomain(v).toString()))
                        }
                    })
                    put("preparedIntegralRows", buildJsonArray {
                        model.factors.forEachIndexed { factorId, factor ->
                            for (row in factor.impliedLinearRows) {
                                val constants = row.constants as? IntegralConstants ?: continue
                                add(buildJsonObject {
                                    put("factor", factorId)
                                    put("relation", row.relation.name)
                                    put("activator", row.activator)
                                    put("strict", row.strict)
                                    put("bound", constants.exactBound.toString())
                                    put("terms", buildJsonArray {
                                        for (k in 0 until row.size) add(buildJsonObject {
                                            put("reference", row.ref(k))
                                            put("coefficient", constants.exactCoeff(k).toString())
                                        })
                                    })
                                })
                            }
                        }
                    })
                    put("preparationNs", elapsed)
                    put("preparationWork", budget.spent())
                    put("stopped", stop())
                    put("infeasible", prepared.presolve?.infeasible)
                    put("generators", buildJsonArray {
                        for (handling in model.factors.filterIsInstance<SymmetryHandling>()) {
                            for ((intMap, boolMap) in handling.generators) add(buildJsonObject {
                                put("integers", buildJsonArray {
                                    for (v in intMap.indices) if (intMap[v] != v) add(buildJsonObject {
                                        put("from", v)
                                        put("to", intMap[v])
                                        put("coefficient", ints.getOrElse(v) { 0L })
                                    })
                                })
                                put("booleans", buildJsonArray {
                                    for (v in boolMap.indices) if (boolMap[v] != v) add(buildJsonObject {
                                        put("from", v)
                                        put("to", boolMap[v])
                                        put("coefficient", bools.getOrElse(v) { 0L })
                                    })
                                })
                            })
                        }
                    })
                    put("passes", JsonArray(prepared.presolve?.passes.orEmpty().map(::JsonPrimitive)))
                    put("invocations", buildJsonArray {
                        for (call in budget.invocations) add(buildJsonObject {
                            put("lane", call.lane.name)
                            put("pass", call.pass)
                            put("outcome", call.outcome.name)
                            put("work", call.work)
                            put("ns", call.elapsed.inWholeNanoseconds)
                        })
                    })
                    put("searchExecuted", false)
                    put("sourceVerdictClaim", false)
                }
                directory.resolve(if (mode == "default") "protected.json" else "objective.json")
                    .writeText(report.toString())
                println("${source.getValue("problem")}: $mode, ${model.factors.size} factors, stopped=${stop()}")
            }
        }
    }
}
