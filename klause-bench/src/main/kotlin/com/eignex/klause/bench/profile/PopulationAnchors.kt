package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.localsearch.CostShaping
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.IteratedLocalSearchRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.PerturbationKind
import com.eignex.klause.localsearch.RestartPolicy
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.SatisfiedStructured
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.util.Cancellation
import java.io.File
import java.util.Base64
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

internal object PopulationAnchors {
    @JvmStatic
    fun main(args: Array<String>) {
        val captureStarted = System.nanoTime()
        val captureAllocated = trajectoryThreadBytes()
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("suite", "problem", "sha256", "source64", "sourceGzip64",
            "seed", "offers64", "presolve") })
        val presolve = options["presolve"] ?: "off"
        require(presolve in setOf("off", "default"))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val bytes = trajectorySourceBytes(options.getValue("suite"), options.getValue("problem"),
            options.getValue("sha256"), options["source64"], options["sourceGzip64"])
        out.resolve("source.xml").writeBytes(bytes)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        val parsed = Xcsp3.parse(bytes.decodeToString())
        require(parsed.sense == ObjectiveSense.MINIMIZE && parsed.problem.numRealVars == 0)
        require(parsed.problem.numBoolVars + parsed.problem.numIntVars <= 8192)
        val objective = requireNotNull(parsed.objective?.toLinearObjective())
        val source = parsed.problem.bake()
        val seed = options["seed"]?.toLong() ?: 1L
        val offers = options["offers64"]?.let {
            Json.parseToJsonElement(Base64.getDecoder().decode(it).decodeToString()).jsonArray
                .map { point -> sourceCenter(point.toString()) }.also { points -> require(points.size in 1..16) }
        }
        val stop = Cancellation.after(120.seconds)
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(parsed.problem, parsed.definedVars, emptyList(), parsed.definitionHints),
            ExecutionOperation.OPTIMIZE, FiniteEngine.LOCAL_SEARCH, objective = objective, cancellation = stop,
        ))
        val plan = requireNotNull(invocation.preparedPlan)
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(
            requireNotNull(plan.model), FiniteEngine.LOCAL_SEARCH, objective,
            cancellation = stop, solveCancellation = stop, preparedPlan = plan,
            presolveConfig = if (presolve == "off") PresolveConfig.NONE else PresolveConfig.DEFAULT, randomSeed = seed,
        ))
        val problem = prepared.problem as BakedProblem
        val workingObjective = requireNotNull(prepared.objective)
        val coordinates = LocalSearchTrajectory.trajectoryCoordinates(invocation, prepared, stop)
        val hints = LocalSearchTrajectory.heuristicHints(invocation, prepared, stop)
        val sweep = hints?.let { LocalSearchTrajectory.getter(it, "getSweep") as DefinitionalSweep? }
        val gradient = hints?.let { LocalSearchTrajectory.getter(it, "getObjective") as IncrementalObjective? }
        out.resolve("preparation.json").writeText(buildJsonObject {
            put("sourceIntegers", source.numIntVars)
            put("workingIntegers", problem.numIntVars)
            put("workingFactors", problem.numFactors)
            put("definitions", sweep?.size ?: 0)
            put("mapped", coordinates != null)
            put("presolve", presolve)
            put("runtime", System.getProperty("java.runtime.version"))
            put("os", System.getProperty("os.name"))
            put("architecture", System.getProperty("os.arch"))
            put("sourceConditions", "none")
            put("independentWitnessCheck", false)
            put("sourceVerdictClaim", false)
            put("elapsedNanos", System.nanoTime() - captureStarted)
            put("allocatedBytes", trajectoryThreadBytes() - captureAllocated)
        }.toString())
        if (coordinates == null || stop()) return
        val inputs = parsed.intVarNames.values.distinct().filterNot { it in parsed.definedVars }
        fun equalInputs(a: Sample, b: Sample): Boolean =
            inputs.all { a.exactIntValue(it) == b.exactIntValue(it) }
        val modes = if (offers == null) listOf("walk") else listOf("uniform", "factor", "kick")
        out.resolve("observations.jsonl").bufferedWriter().use { writer ->
            for (mode in modes) for (dedup in listOf(false, true)) {
                val started = System.nanoTime()
                val allocated = trajectoryThreadBytes()
                var offered = 0
                var duplicates = 0
                var declined = 0
                var consumed = 0
                val policy = IteratedLocalSearchRestart(populationSize = 3,
                    crossoverRate = if (mode == "kick") 0.0 else if (mode == "walk") 0.25 else 1.0,
                    linkageAware = mode == "factor", perturbationKind = PerturbationKind.BasinHopping)
                val state = LocalSearchState(problem, Random(seed))
                state.invariants = sweep?.network(problem.numIntVars, problem.numBoolVars)
                fun checked(working: Sample): Sample? {
                    val point = prepared.reconstruct(working)
                    if (stop() || !FiniteConflictSelection.checkSource(source, point, stop) ||
                        workingObjective.evaluateExact(working) != objective.evaluateExact(point) ||
                        BigFraction.ofDouble(workingObjective.evaluate(working)) != objective.evaluateExact(point)
                    ) return null
                    return point
                }
                val ingress = object : RestartPolicy {
                    override fun reset() = policy.reset()
                    override fun shouldRestart(stepsSinceLastRestart: Int) = policy.shouldRestart(stepsSinceLastRestart)
                    override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                        if (policy.incumbents.isNotEmpty()) consumed++
                        policy.restart(state, bestSoFar)
                    }
                    override fun onLocalOptimum(state: LocalSearchState, sample: Sample, objective: Double) =
                        onFeasibleStall(state, sample, objective)
                    override fun onFeasibleStall(state: LocalSearchState, sample: Sample, objective: Double) {
                        offered++
                        val point = checked(sample)
                        if (point == null) { declined++; return }
                        val duplicate = policy.incumbents.any { equalInputs(point, prepared.reconstruct(it.sample)) }
                        if (duplicate) duplicates++
                        if (!dedup || !duplicate) policy.onFeasibleStall(state, sample, objective)
                    }
                }
                try {
                    if (offers == null) {
                        val recipe = LocalSearchCatalog.byLabel("cbls-plateau/fixed").withRestart(ingress)
                        val solver = LocalSearchSolver(problem, strategy = recipe.strategy,
                            optimizeStrategy = recipe.optimizeStrategy, definitionalSweep = sweep,
                            perMoveInvariants = sweep != null, seedImplicitOnRestart = false)
                        val result = solver.minimize(workingObjective, LocalSearchParams(
                            randomSeed = seed, maxFlips = 1600, cancellation = stop, lsObjective = gradient,
                            costShaping = CostShaping.Linear(lambda = 1.0), normalizeWeightsByClass = true))
                        writer.appendLine(buildJsonObject {
                            put("mode", mode); put("dedup", dedup)
                            put("objective", result.objectiveValue)
                            put("moves", result.stats.ls.moves.sum)
                            result.assignment?.let { put("checkedResult", checked(it) != null) }
                        }.toString())
                    } else {
                        for (point in offers) {
                            require(FiniteConflictSelection.checkSource(source, point, stop))
                            val decline = loadSourceCenter(point, state, coordinates, prepared.reconstruct) {
                                sweep?.sweep(state.assignment, state.rootDomains, problem.factors)
                                state.recompute()
                            }
                            if (decline != null) {
                                writer.appendLine(buildJsonObject {
                                    put("mode", mode); put("dedup", dedup)
                                    put("projectionDecline", decline)
                                    put("elapsedNanos", System.nanoTime() - captureStarted)
                                    put("allocatedBytes", trajectoryThreadBytes() - captureAllocated)
                                }.toString())
                                return
                            }
                            ingress.onFeasibleStall(state, state.assignment.snapshot(),
                                workingObjective.evaluate(state.assignment))
                        }
                        for (draw in 0 until 64) {
                            if (stop()) break
                            ingress.restart(state, null)
                            sweep?.sweep(state.assignment, state.rootDomains, problem.factors)
                            state.recompute()
                            val point = checked(state.assignment.snapshot())
                            writer.appendLine(buildJsonObject {
                                put("mode", mode); put("dedup", dedup); put("draw", draw)
                                put("nativeSourceAccepted", point != null)
                                if (point != null) {
                                    put("source", trajectorySource(point))
                                    put("exactObjective", objective.evaluateExact(point).toString())
                                    put("strictImprovement", offers.all {
                                        objective.evaluateExact(point) < objective.evaluateExact(it)
                                    })
                                }
                            }.toString())
                        }
                    }
                    writer.appendLine(buildJsonObject {
                        put("mode", mode); put("dedup", dedup); put("summary", true)
                        put("offered", offered); put("duplicates", duplicates); put("declined", declined)
                        put("consumedRestarts", consumed); put("population", policy.incumbents.size)
                        put("populationObjectives", JsonArray(policy.incumbents.map {
                            JsonPrimitive(objective.evaluateExact(prepared.reconstruct(it.sample)).toString())
                        }))
                        put("elapsedNanos", System.nanoTime() - started)
                        put("allocatedBytes", trajectoryThreadBytes() - allocated)
                        put("cancelled", stop())
                        put("independentWitnessCheck", false)
                    }.toString())
                    writer.flush()
                } finally {
                    ingress.reset()
                    check(policy.incumbents.isEmpty())
                }
            }
            if (offers != null && !stop()) {
                for ((index, point) in offers.distinctBy { trajectorySource(it).toString() }.withIndex()) {
                    for (extended in listOf(false, true)) {
                        if (stop()) break
                        val recipe = LocalSearchCatalog.byLabel("cbls-plateau/fixed").let {
                            if (!extended) it else it.withSources { sources ->
                                sources + ConfiguredSource(SatisfiedStructured.sampledExtended(8))
                            }
                        }
                        val projected = projectedSourceCenter(point, problem, coordinates, sweep, prepared.reconstruct)
                        val started = System.nanoTime()
                        val result = projected.first?.let {
                            LocalSearchSolver(problem, strategy = recipe.strategy,
                                optimizeStrategy = recipe.optimizeStrategy, definitionalSweep = sweep,
                                perMoveInvariants = sweep != null).minimize(workingObjective, LocalSearchParams(
                                randomSeed = seed, maxFlips = 512, initialAssignment = it, cancellation = stop,
                                lsObjective = gradient, costShaping = CostShaping.Linear(lambda = 1.0),
                                normalizeWeightsByClass = true))
                        }
                        writer.appendLine(buildJsonObject {
                            put("mode", "existing-recipe-control"); put("center", index); put("extended", extended)
                            put("projectionDecline", projected.second)
                            put("objective", result?.objectiveValue)
                            put("moves", result?.stats?.ls?.moves?.sum)
                            put("elapsedNanos", System.nanoTime() - started)
                            result?.assignment?.let {
                                put("nativeSourceAccepted", FiniteConflictSelection.checkSource(
                                    source, prepared.reconstruct(it), stop))
                            }
                            put("independentWitnessCheck", false)
                        }.toString())
                        writer.flush()
                    }
                }
                val started = System.nanoTime()
                val result = BacktrackSolver(source).minimize(objective, BacktrackParams(
                    maxDecisions = 1000, cancellation = stop, randomSeed = seed))
                writer.appendLine(buildJsonObject {
                    put("mode", "whole-source-cp-control")
                    put("objective", result.objectiveValue)
                    put("nodes", result.stats.search.nodes.sum)
                    put("elapsedNanos", System.nanoTime() - started)
                    result.assignment?.let {
                        put("nativeSourceAccepted", FiniteConflictSelection.checkSource(source, it, stop))
                    }
                    put("independentWitnessCheck", false)
                }.toString())
            }
        }
        out.resolve("full-cost.json").writeText(buildJsonObject {
            put("elapsedNanos", System.nanoTime() - captureStarted)
            put("allocatedBytes", trajectoryThreadBytes() - captureAllocated)
            put("cancelled", stop())
            put("scope", "source loading, preparation, checks, all controls, serialization and policy disposal")
        }.toString())
    }
}
