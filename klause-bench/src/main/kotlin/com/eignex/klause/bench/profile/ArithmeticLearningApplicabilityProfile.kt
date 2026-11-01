package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

internal object ArithmeticLearningApplicabilityProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("suite", "max", "timeout-ms") })
        val suite = requireNotNull(options["suite"])
        val max = (options["max"] ?: "64").toInt().also { require(it in 1..64) }
        val timeout = (options["timeout-ms"] ?: "10000").toLong().also { require(it in 1..10_000L) }
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val sources = Catalog.uncapped(suite).problems.filter { it.format == Format.MPS }.sortedBy { it.name }.take(max)
        out.resolve("applicability.jsonl").bufferedWriter().use { output ->
            sources.forEachIndexed { index, source ->
                val directory = out.resolve(index.toString()).apply { mkdirs() }
                val record = buildJsonObject {
                    put("revision", Reports.readGitSha())
                    put("suite", suite)
                    put("problem", source.name)
                    put("entryPoint", "in-process-finite-preparation")
                    put("cliRoutingEquivalence", "unverified")
                    put("searchExecuted", false)
                    put("sourceVerdictClaim", false)
                    put("preparationBudgetMs", timeout)
                    val bytes = CorpusFiles.open(CorpusFetcher.resolve(source.source)).use { it.readNBytes(1_048_577) }
                    put("sourceBytesRead", bytes.size)
                    if (bytes.size > 1_048_576) {
                        put("status", "source-size-declined")
                        return@buildJsonObject
                    }
                    put("sourceSha256", sha256(bytes))
                    val file = directory.resolve("source.mps").apply { writeBytes(bytes) }
                    val started = System.nanoTime()
                    val stop = Cancellation.after(timeout.milliseconds)
                    try {
                        val entry = Runners.resolve(source.copy(source = ProblemSource.Prepared(file)))
                        val original = entry.problem
                        put("originalIntegers", original.numIntVars)
                        put("originalBooleans", original.numBoolVars)
                        put("originalReals", original.numRealVars)
                        if (original.numIntVars + original.numBoolVars + original.numRealVars > 4096) {
                            put("status", "column-limit-declined")
                            return@buildJsonObject
                        }
                        val prepared = FinitePipeline.prepare(
                            FinitePipelineRequest(
                                original,
                                FiniteEngine.BACKTRACK,
                                entry.objective,
                                cancellation = stop,
                                solveCancellation = stop,
                                randomSeed = 1L,
                            )
                        )
                        val model = prepared.problem as? BakedProblem
                        if (model == null || stop() || prepared.presolve?.infeasible == true) {
                            put("status", "preparation-incomplete-or-terminal")
                            return@buildJsonObject
                        }
                        put("status", "prepared")
                        put("integers", model.numIntVars)
                        put("booleans", model.numBoolVars)
                        put("reals", model.numRealVars)
                        put("factors", model.factors.size)
                        put("objective", prepared.objective != null)
                        put("annotatedSearch", entry.searchParams != null)
                        put("policy", policy(model, prepared.objective, entry.searchParams != null))
                    } catch (error: IllegalArgumentException) {
                        decline(error)
                    } catch (error: IllegalStateException) {
                        decline(error)
                    } catch (error: UnsupportedOperationException) {
                        decline(error)
                    } finally {
                        put("preparationNs", System.nanoTime() - started)
                        put("cancelled", stop())
                    }
                }
                output.appendLine(record.toString())
                output.flush()
                println(record)
            }
        }
    }

    private fun JsonObjectBuilder.decline(error: RuntimeException) {
        put("status", "preparation-declined")
        put("error", error::class.simpleName)
        put("message", error.message?.take(512))
    }

    private fun policy(model: BakedProblem, objective: LinearObjective?, annotated: Boolean) = buildJsonObject {
        val factsType = Class.forName("com.eignex.klause.portfolio.ProblemFacts")
        val companion = factsType.getField("Companion").get(null)
        val facts = factsType.declaredClasses.single { it.simpleName == "Companion" }.methods.single {
            it.name == "of" && it.parameterCount == 5
        }.invoke(companion, model, ProblemProfile.of(model, objective != null), LpConfig.AGGRESSIVE, objective, null)
        for (name in listOf("IntegerColumnsOnly", "BinaryIntegers", "IntegerLinear", "CompactCardinalityEquality")) {
            put(name.replaceFirstChar { it.lowercaseChar() }, factsType.getMethod("get$name").invoke(facts) as Boolean)
        }
        if (objective == null || annotated) {
            put("automaticSlots", buildJsonArray {})
            return@buildJsonObject
        }
        val composition = Class.forName("com.eignex.klause.portfolio.PortfolioComposition")
        val arms = composition.getMethod("compose", PortfolioScenario::class.java, factsType).invoke(
            composition.getField("INSTANCE").get(null),
            PortfolioScenario.sequential(Kind.COP, EngineMix.BACKTRACK, 1L),
            facts,
        ) as List<*>
        put(
            "automaticSlots",
            buildJsonArray {
                arms.forEachIndexed { index, arm ->
                    requireNotNull(arm)
                    val recipe = arm.javaClass.getMethod("getRecipe").invoke(arm) as BacktrackRecipe
                    if (recipe.build(1001L + index, null).integerRowLearning) add(JsonPrimitive(index))
                }
            }
        )
    }
}
