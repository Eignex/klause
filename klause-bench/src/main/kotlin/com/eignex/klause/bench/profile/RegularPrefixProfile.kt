package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
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

internal object RegularPrefixProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..4 && args.first().startsWith("request64="))
        val allowed = setOf("replay=true", "fixed=true", "controls=3", "controls=6", "controls=off")
        require(args.drop(1).all { it in allowed })
        val replay = "replay=true" in args
        val fixed = "fixed=true" in args
        val sizes = when (args.firstOrNull { it.startsWith("controls=") }) {
            "controls=3" -> listOf(3)
            "controls=6" -> listOf(6)
            "controls=off" -> emptyList()
            else -> listOf(3, 6)
        }
        val request = Base64.getDecoder().decode(args.first().substringAfter('='))
        require(request.size <= 16 * 1024)
        val sources = Reports.json.parseToJsonElement(request.decodeToString()).jsonArray
        require(sources.size in 1..4)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        out.resolve("request.json").writeBytes(request)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        for (k in sizes) {
            for (allowTwo in listOf(false, true)) {
                capture(control(k, allowTwo), out.resolve("control-$k-$allowTwo"), replay, fixed)
            }
        }
        sources.forEachIndexed { index, input ->
            val source = input.jsonObject
            require(source.keys == setOf("suite", "problem", "sha256"))
            val bytes = trajectorySourceBytes(
                source.getValue("suite").jsonPrimitive.content,
                source.getValue("problem").jsonPrimitive.content,
                source.getValue("sha256").jsonPrimitive.content,
                null,
            )
            val directory = out.resolve("source-$index").apply { mkdirs() }
            directory.resolve("source.xml").writeBytes(bytes)
            capture(Xcsp3.parse(bytes.decodeToString()).problem, directory, replay, fixed)
        }
    }

    private fun control(k: Int, allowTwo: Boolean): Problem {
        val transitions = longArrayOf(1, 2, 2, 3, 3, 1)
        return Problem(
            0,
            k + 2,
            Array(k + 2) { IntDomain(1, 2) },
            arrayOf(
                Regular(
                    IntArray(k) { it },
                    3,
                    2,
                    transitions,
                    1,
                    if (allowTwo) intArrayOf(1, 2, 3) else intArrayOf(1, 2),
                ),
                Regular(IntArray(k + 2) { it }, 3, 2, transitions, 1, intArrayOf(1)),
                Linear(intArrayOf(1, -1), intArrayOf(k, k + 1), LinearOp.NE, 0),
            ),
        )
    }

    private fun capture(source: Problem, out: File, replay: Boolean, fixed: Boolean) {
        out.mkdirs()
        val codec = ProblemJson()
        out.resolve("source.json").writeText(codec.encodeToJsonElement(source).toString())
        for (mode in listOf("default", "aggressive")) {
            val stop = Cancellation.after(12.seconds)
            val budget = PresolveBudget(825_000_000L)
            val started = System.nanoTime()
            val prepared = FinitePipeline.prepare(
                FinitePipelineRequest(
                    source,
                    FiniteEngine.BACKTRACK,
                    presolveConfig = PresolveConfig.parse(mode),
                    explicitPresolveConfig = mode != "default",
                    cancellation = stop,
                    solveCancellation = stop,
                    presolveBudget = budget,
                    randomSeed = 3L,
                ),
            )
            val preparationNs = System.nanoTime() - started
            val model = prepared.problem
            out.resolve("$mode-prepared.json").writeText(codec.encodeToJsonElement(model).toString())
            val root = if (prepared.presolve?.infeasible != true && !stop()) {
                PropagationSession(
                    model,
                    cancellation = stop,
                ).seed(Assumptions.None)
            } else {
                null
            }
            val report = buildJsonObject {
                put("revision", Reports.readGitSha())
                put("mode", mode)
                put("sourceIntegers", source.numIntVars)
                put("sourceBooleans", source.numBoolVars)
                put("sourceFactorKinds", kinds(source))
                put("preparedIntegers", model.numIntVars)
                put("preparedBooleans", model.numBoolVars)
                put("preparedFactorKinds", kinds(model))
                put("infeasible", prepared.presolve?.infeasible == true || root is PropagationResult.Unsat)
                put("stopped", stop())
                put("preparationNs", preparationNs)
                put("preparationWork", budget.spent())
                put("passes", JsonArray(prepared.presolve?.passes.orEmpty().map(::JsonPrimitive)))
                put(
                    "regular",
                    buildJsonArray {
                        model.factors.forEachIndexed { id, factor ->
                            if (factor is Regular) {
                                add(
                                    buildJsonObject {
                                        put("factor", id)
                                        put("length", factor.seq.size)
                                        put("states", factor.numStates)
                                        put("alphabet", factor.alphabetSize)
                                        put(
                                            "deadTransitions",
                                            factor.transitions.count { it !in 1L..factor.numStates.toLong() },
                                        )
                                        put("distinctCoordinates", factor.seq.toSet().size == factor.seq.size)
                                        put("coordinates", JsonArray(factor.seq.map(::JsonPrimitive)))
                                    },
                                )
                            }
                        }
                    },
                )
                put("os", System.getProperty("os.name"))
                put("arch", System.getProperty("os.arch"))
                put("javaVersion", System.getProperty("java.version"))
                put("sourceVerdictClaim", false)
                put("searchExecuted", false)
            }
            out.resolve("$mode-report.json").writeText(report.toString())
            println("${out.name}/$mode: $report")
            if (replay && mode == "default" && prepared.presolve?.infeasible != true && !stop()) {
                RegularPrefixReplay.capture(
                    model,
                    sha256(
                        codec.encodeToJsonElement(model).toString().toByteArray(),
                    ),
                    out,
                    source,
                    prepared.reconstruct,
                    fixed,
                )
            }
        }
    }

    private fun kinds(problem: Problem) = buildJsonObject {
        problem.factors.groupingBy { it.javaClass.simpleName }.eachCount().forEach { (kind, count) -> put(kind, count) }
    }
}
