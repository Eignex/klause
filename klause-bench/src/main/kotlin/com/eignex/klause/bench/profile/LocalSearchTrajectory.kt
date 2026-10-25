package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.localsearch.CostShaping
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.MoveSource
import com.eignex.klause.localsearch.movesource.MoveSourceId
import com.eignex.klause.localsearch.movesource.Phase
import com.eignex.klause.localsearch.movesource.Pool
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionStrategy
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelinePreparation
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.pipeline.PreparedSourceInvocation
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

internal object LocalSearchTrajectory {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "sha256", "recipe", "seed", "moves"))
        val reference = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use { it.readBytes() }
        require(sha256(bytes) == options.getValue("sha256"))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("source.xml").writeBytes(bytes)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        val parsed = Xcsp3.parse(bytes.decodeToString())
        val objective = requireNotNull(parsed.objective).toLinearObjective()
        require(parsed.sense == ObjectiveSense.MINIMIZE)
        val seed = options.getValue("seed").toLong()
        val moves = options.getValue("moves").toLong().also { require(it in 1..1_000_000) }
        for (rewrite in listOf(false, true)) {
            val stop = Cancellation.after(60.seconds)
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                SourceAttachment(parsed.problem, parsed.definedVars, emptyList(), parsed.definitionHints),
                ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, objective = objective, cancellation = stop,
            ))
            val plan = requireNotNull(invocation.preparedPlan)
            val prepared = FinitePipeline.prepare(FinitePipelineRequest(
                requireNotNull(plan.model), FiniteEngine.MIXED, objective,
                cancellation = stop, solveCancellation = stop, presolveBudget = PresolveBudget(1_375_000_000L),
                preparedPlan = plan, transformObjective = rewrite, randomSeed = seed,
            ))
            check(!stop() && prepared.presolve?.infeasible != true)
            val problem = prepared.problem as BakedProblem
            val workingObjective = requireNotNull(prepared.objective)
            val hints = heuristicHints(invocation, prepared, stop)
            val sweep = hints?.let { getter(it, "getSweep") as DefinitionalSweep? }
            val gradient = hints?.let { getter(it, "getObjective") as IncrementalObjective? }
            out.resolve("model-$rewrite.json").writeText(buildJsonObject {
                put("objective", workingObjective.toString())
                put("factors", buildJsonArray {
                    for (factor in problem.factors) add(buildJsonObject {
                        put("kind", factor::class.simpleName)
                        put("key", factor.structuralKey().toString())
                    })
                })
                put("domains", JsonArray(problem.rootIntDomains().map { JsonPrimitive(it.toString()) }))
                put("sourceVerdictClaim", false)
            }.toString())
            out.resolve("walk-$rewrite.jsonl").bufferedWriter().use { writer ->
                var picks = 0L
                val recorder = object : MoveSource {
                    override val id = MoveSourceId("trajectory-observation")
                    override val phase = Phase.Any
                    override val pool = Pool.NoiseEligible
                    override fun generate(state: LocalSearchState, sink: MoveSink) {
                        val pick = picks++
                        if (pick >= 256 && pick % 512 != 0L) return
                        val sample = prepared.reconstruct(state.assignment.snapshot())
                        writer.appendLine(buildJsonObject {
                            put("pick", pick)
                            put("step", state.step)
                            put("cost", state.cost)
                            put("sourceIntegers", buildJsonObject {
                                for ((name, variable) in parsed.intVarNames) put(name, sample.intValue(variable))
                            })
                            put("degrees", JsonArray(state.factorDegree.map(::JsonPrimitive)))
                            put("violated", JsonArray(state.violated.toIntArray().map(::JsonPrimitive)))
                        }.toString())
                        writer.flush()
                    }
                }
                val recipe = LocalSearchCatalog.byLabel(options.getValue("recipe")).withSources {
                    listOf(ConfiguredSource(recorder)) + it
                }
                val solver = LocalSearchSolver(
                    problem, strategy = recipe.strategy, optimizeStrategy = recipe.optimizeStrategy,
                    definitionalSweep = sweep, perMoveInvariants = sweep != null && recipe.perMoveInvariants,
                    seedImplicitOnRestart = recipe.seedImplicitOnRestart,
                )
                val params = LocalSearchParams(
                    randomSeed = seed, maxFlips = moves, cancellation = stop,
                    costShaping = CostShaping.Linear(lambda = 1.0), lsObjective = gradient,
                    normalizeWeightsByClass = true, reportResiduals = true,
                )
                solver.resumable(workingObjective, params).use { run ->
                    val result = run.runSlice(stop, Long.MAX_VALUE) {}
                    out.resolve("result-$rewrite.txt").writeText(
                        "picks=$picks\ncancelled=${stop()}\nresult=$result\nstats=${run.stats}\n",
                    )
                }
            }
        }
    }

    private fun getter(owner: Any, name: String): Any? = owner.javaClass.methods.single {
        it.name.substringBefore('$') == name && it.parameterCount == 0
    }.invoke(owner)

    private fun heuristicHints(
        invocation: PreparedSourceInvocation,
        prepared: FinitePipelinePreparation,
        stop: Cancellation,
    ): Any? {
        val mapping = getter(prepared, "getSourceMapping")
        val plan = requireNotNull(getter(prepared, "getExecutionPlan"))
        val capabilities = getter(plan, "getCapabilities")
        return Class.forName("com.eignex.klause.solver.pipeline.PreparedSourceHintsKt").methods.single {
            it.name == "heuristicHints"
        }.invoke(null, invocation, prepared.problem, prepared.objective, mapping, stop,
            setOf(ExecutionStrategy.LOCAL_SEARCH), capabilities)
    }
}
