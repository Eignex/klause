package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.formats.xcsp3.Xcsp3Problem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.VarRemap
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
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelinePreparation
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ObjectiveViewComparison {
    private enum class View { RETAINED, SUBSTITUTED, FUNCTIONAL }

    private data class Prepared(
        val pipeline: FinitePipelinePreparation,
        val coordinates: VarRemap?,
        val sweep: DefinitionalSweep?,
        val gradient: IncrementalObjective?,
        val nanos: Long,
        val allocatedBytes: Long,
    )

    private data class Boundary(val label: String, val source: Sample)

    private object InitialCaptured : RuntimeException(null, null, false, false)

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        if (args.size == 1 && args[0].startsWith("batch64=")) {
            val requests = Json.decodeFromString<List<List<String>>>(
                Base64.getDecoder().decode(args[0].substringAfter('=')).decodeToString())
            require(requests.size in 1..8)
            for ((index, request) in requests.withIndex()) {
                capture(request.toTypedArray(), out.resolve("$index").apply { mkdirs() })
            }
        } else capture(args, out)
    }

    private fun capture(args: Array<String>, out: File) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val required = setOf("suite", "problem", "sha256")
        val optional = setOf("source64", "sourceGzip64", "recipe", "seed", "moves", "timeoutMillis", "screen", "greedy")
        require(options.keys.containsAll(required) && options.keys.all { it in required || it in optional })
        val bytes = trajectorySourceBytes(
            options.getValue("suite"), options.getValue("problem"), options.getValue("sha256"),
            options["source64"], options["sourceGzip64"],
        )
        out.resolve("source.xml").writeBytes(bytes)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val started = System.nanoTime()
        val source = Xcsp3.parse(bytes.decodeToString(), recoveryCancellation = Cancellation.after(30.seconds))
        val parsingNanos = System.nanoTime() - started
        val finite = source.problem.numRealVars == 0 &&
            source.problem.numBoolVars.toLong() + source.problem.numIntVars <= TRAJECTORY_COORDINATE_LIMIT &&
            source.problem.numFactors <= TRAJECTORY_FACTOR_LIMIT
        out.resolve("source-capability.json").writeText(buildJsonObject {
            put("sourceSha256", sha256(bytes))
            put("sourceBytes", bytes.size)
            put("parsingNanos", parsingNanos)
            put("integers", source.problem.numIntVars)
            put("booleans", source.problem.numBoolVars)
            put("factors", source.problem.numFactors)
            put("sense", source.sense.name)
            put("objectivePresent", source.objective != null)
            put("captureSizeAdmitted", finite)
            put("definitionKinds", buildJsonObject {
                source.definitionHints.groupingBy { it::class.simpleName ?: "unknown" }.eachCount()
                    .forEach { (kind, count) -> put(kind, count) }
            })
            put("declaredSemantics", source.semantics.declared.toString())
            put("effectiveSemantics", source.semantics.effective.toString())
            put("settings", source.problem.settings.toString())
            put("sourceVerdictClaim", false)
            if (!finite) put("decline", "capture-size-or-real-coordinate")
            if (source.sense != ObjectiveSense.MINIMIZE) put("decline", "minimization-only")
        }.toString())
        if (!finite || source.sense != ObjectiveSense.MINIMIZE || source.objective == null ||
            options["screen"] == "true"
        ) return
        val seed = options["seed"]?.toLong() ?: 3L
        val moves = (options["moves"]?.toLong() ?: 10000L).also { require(it in 1..1_000_000) }
        val timeout = (options["timeoutMillis"]?.toLong() ?: 10000L).also { require(it in 100..60000) }
        val recipe = options["recipe"] ?: "cbls-plateau/ils-basin"
        val greedy = options["greedy"]?.toBooleanStrict() ?: true
        out.resolve("policy.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha() ?: System.getenv("LAB_WORKTREE")?.let { File(it).name })
            put("recipe", recipe)
            put("seed", seed)
            put("moves", moves)
            put("timeoutMillisIncludingPreparation", timeout)
            put("greedy", greedy)
            put("normalizedClassWeights", true)
            put("costShaping", "linear lambda=1")
            put("maxSourcePoints", 6)
            put("maxCandidatesPerPair", 32)
            put("probeTiming", "outside walk allowance")
            put("walkTiming", "instrumented; parsing common and excluded")
            put("proposalEquivalenceClaim", false)
            put("sourceVerdictClaim", false)
        }.toString())
        val states = ArrayList<TrajectoryView>()
        val boundaries = ArrayList<Boundary>()
        for (view in View.entries) {
            val directory = out.resolve(view.name.lowercase()).apply { mkdirs() }
            val prepared = prepare(source, view, seed, timeout, directory) ?: continue
            directory.resolve("model.json").writeText(buildJsonObject {
                put("preparationNanos", prepared.nanos)
                put("preparationAllocatedBytes", prepared.allocatedBytes)
                put("objective", prepared.pipeline.objective.toString())
                put("functionalObjective", prepared.gradient?.javaClass?.name)
                put("sweepPresent", prepared.sweep != null)
                put("transformObjective", view == View.SUBSTITUTED)
                put("functionalGrading", view == View.FUNCTIONAL)
                put("factors", buildJsonArray {
                    for (factor in prepared.pipeline.problem.factors) add(buildJsonObject {
                        put("kind", factor::class.simpleName)
                        put("key", factor.structuralKey().toString())
                    })
                })
            }.toString())
            if (prepared.coordinates == null) {
                directory.resolve("decline.json").writeText("{\"decline\":\"source-coordinate-map\"}")
                continue
            }
            if (view == View.FUNCTIONAL &&
                (prepared.gradient == null || !LocalSearchCatalog.byLabel(recipe).perMoveInvariants)
            ) {
                directory.resolve("decline.json").writeText("{\"decline\":\"no-maintained-functional-objective\"}")
                continue
            }
            val initial = run(source, prepared, view, recipe, seed, 1, false, 60000, directory, boundaries)
            if (initial != null) states += initial
            val remaining = timeout - (prepared.nanos + 999999) / 1000000
            if (remaining > 0) run(source, prepared, view, recipe, seed, moves, greedy, remaining,
                directory, boundaries, walk = true)
            else directory.resolve("walk-decline.json").writeText("{\"decline\":\"preparation-time-budget\"}")
        }
        out.resolve("paired.jsonl").bufferedWriter().use { writer ->
            check(boundaries.size <= 6)
            val unique = boundaries.distinctBy { trajectorySource(it.source) }
            val functionalState = states.firstOrNull { it.label == "functional" }
            val functional = functionalState?.objective
            val priority = functional?.javaClass?.methods?.singleOrNull {
                it.name.substringBefore('$') == "getLeafVars" && it.parameterCount == 0
            }?.invoke(functional) as? IntArray ?: IntArray(0)
            val sourcePriority = functionalState?.coordinates?.let { coordinates ->
                (0 until coordinates.intCount).filter { coordinates.int(it) in priority }.toIntArray()
            } ?: IntArray(0)
            for ((label, point) in unique) for (left in states.indices) for (right in left + 1 until states.size) {
                val candidates = trajectoryCandidates(point, listOf(states[left], states[right]),
                    priorityInts = sourcePriority)
                writer.appendLine(buildJsonObject {
                    put("origin", label)
                    put("candidatePolicy", "fixed common primitives; functional inputs before source order")
                    put("comparison", trajectoryPair(point, candidates, states[left], states[right]))
                }.toString())
                writer.flush()
            }
        }
    }

    private fun prepare(source: Xcsp3Problem, view: View, seed: Long, timeout: Long, out: File): Prepared? {
        val started = System.nanoTime()
        val allocation = allocationCounter()
        val before = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId())
        val stop = Cancellation.after(timeout.milliseconds)
        val objective = requireNotNull(source.objective).toLinearObjective()
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            if (source.definedVars.isEmpty()) SourceAttachment(source.problem, source.definitionHints)
            else SourceAttachment(source.problem, source.definedVars, emptyList(), source.definitionHints),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, objective = objective, cancellation = stop,
        ))
        val plan = invocation.preparedPlan
        if (plan == null || stop()) {
            out.resolve("decline.json").writeText("{\"decline\":\"source-preparation-unavailable\"}")
            return null
        }
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(
            requireNotNull(plan.model), FiniteEngine.MIXED, objective,
            cancellation = stop, solveCancellation = stop, presolveBudget = PresolveBudget(1_375_000_000L),
            preparedPlan = plan, transformObjective = view == View.SUBSTITUTED, randomSeed = seed,
        ))
        if (stop() || prepared.presolve?.infeasible == true) {
            out.resolve("decline.json").writeText(buildJsonObject {
                put("decline", if (stop()) "preparation-time-budget" else "native-presolve-terminal")
                put("elapsedNanos", System.nanoTime() - started)
                put("sourceVerdictClaim", false)
            }.toString())
            return null
        }
        val hints = LocalSearchTrajectory.heuristicHints(invocation, prepared, stop)
        val sweep = hints?.let { LocalSearchTrajectory.getter(it, "getSweep") as DefinitionalSweep? }
        val gradient = hints?.let { LocalSearchTrajectory.getter(it, "getObjective") as IncrementalObjective? }
        val coordinates = LocalSearchTrajectory.trajectoryCoordinates(invocation, prepared, stop)
        return Prepared(prepared, coordinates, sweep, gradient, System.nanoTime() - started,
            allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before)
    }

    private fun run(
        source: Xcsp3Problem,
        prepared: Prepared,
        view: View,
        recipeName: String,
        seed: Long,
        moves: Long,
        greedy: Boolean,
        timeout: Long,
        out: File,
        boundaries: MutableList<Boundary>,
        walk: Boolean = false,
    ): TrajectoryView? {
        val problem = prepared.pipeline.problem as BakedProblem
        val objective = requireNotNull(prepared.pipeline.objective)
        val sourceObjective = requireNotNull(source.objective).toLinearObjective()
        val label = if (walk) "walk" else "initial"
        var first: LocalSearchState? = null
        var initialWeights: DoubleArray? = null
        var picks = 0L
        var samples = 0
        val stop = Cancellation.after(timeout.milliseconds)
        val started = System.nanoTime()
        val allocation = allocationCounter()
        val before = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId())
        out.resolve("$label.jsonl").bufferedWriter().use { writer ->
            val observer = object : MoveSource {
                override val id = MoveSourceId("objective-view-observation")
                override val phase = Phase.Any
                override val pool = Pool.NoiseEligible
                override fun generate(state: LocalSearchState, sink: MoveSink) {
                    val pick = picks++
                    if (pick == 0L) {
                        first = state
                        initialWeights = if (state.weights.allocated) state.weights.factorWeights.copyOf() else null
                        boundaries += Boundary("${view.name.lowercase()}-$label",
                            prepared.pipeline.reconstruct(state.assignment.snapshot()))
                    }
                    if (samples < 16 && (pick < 8 || pick % maxOf(1, moves / 8) == 0L)) {
                        samples++
                        val point = prepared.pipeline.reconstruct(state.assignment.snapshot())
                        writer.appendLine(buildJsonObject {
                            put("pick", pick)
                            put("step", state.step)
                            put("cost", state.cost)
                            put("source", trajectorySource(point))
                            put("sourceExpressionValue", sourceObjective.evaluateExact(point).toString())
                            put("random", trajectoryRandomBoundary(state))
                            put("sourceVerdictClaim", false)
                        }.toString())
                        writer.flush()
                    }
                    if (!walk) throw InitialCaptured
                }
            }
            val recipe = LocalSearchCatalog.byLabel(recipeName).withSources { listOf(ConfiguredSource(observer)) + it }
            val solver = LocalSearchSolver(
                problem, strategy = recipe.strategy, optimizeStrategy = recipe.optimizeStrategy,
                greedyRepairOnRestart = walk && greedy, definitionalSweep = prepared.sweep,
                perMoveInvariants = prepared.sweep != null && recipe.perMoveInvariants,
                seedImplicitOnRestart = recipe.seedImplicitOnRestart,
            )
            val params = LocalSearchParams(
                randomSeed = seed, maxFlips = moves, cancellation = stop,
                costShaping = CostShaping.Linear(lambda = 1.0),
                lsObjective = prepared.gradient.takeIf { view == View.FUNCTIONAL },
                normalizeWeightsByClass = true, reportResiduals = true, reportPhaseTimes = true,
            )
            out.resolve("$label-incumbents.jsonl").bufferedWriter().use { incumbents ->
                var improvements = 0
                solver.resumable(objective, params).use { run ->
                    val onIncumbent: (MinimizeResult.WithSample) -> Unit = { candidate ->
                        if (improvements++ < 16) {
                            val point = prepared.pipeline.reconstruct(candidate.sample)
                            incumbents.appendLine(buildJsonObject {
                                put("elapsedNanos", System.nanoTime() - started)
                                put("objective", sourceObjective.evaluateExact(point).toString())
                                put("source", trajectorySource(point))
                                put("independentWitnessCheck", false)
                            }.toString())
                            incumbents.flush()
                        }
                    }
                    val observed = try {
                        var result = run.runSlice(stop, Long.MAX_VALUE, Long.MAX_VALUE, onIncumbent)
                        while (result == null && !stop()) {
                            result = run.runSlice(stop, Long.MAX_VALUE, Long.MAX_VALUE, onIncumbent)
                        }
                        result
                    } catch (_: InitialCaptured) {
                        null
                    }
                    out.resolve("$label-result.json").writeText(buildJsonObject {
                        put("nativeResult", observed?.javaClass?.simpleName)
                        put("cancelled", stop())
                        put("requestedMoves", moves)
                        put("picks", picks)
                        put("moves", run.stats.ls.moves.sum)
                        put("restarts", run.stats.search.restarts.sum)
                        put("elapsedNanos", System.nanoTime() - started)
                        put("allocatedBytes", allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before)
                        put("modelPreparationNanos", prepared.nanos)
                        put("modelPreparationAllocatedBytes", prepared.allocatedBytes)
                        put("sourceVerdictClaim", false)
                        put("cliRoutingEquivalence", false)
                        observed?.assignment?.let { point ->
                            put("source", trajectorySource(prepared.pipeline.reconstruct(point)))
                            put("objective", sourceObjective.evaluateExact(prepared.pipeline.reconstruct(point)).toString())
                        }
                    }.toString())
                    out.resolve("$label-stats.txt").writeText(run.stats.toString())
                }
            }
        }
        if (walk) return null
        val captured = first ?: return null
        val shadow = LocalSearchState(captured.model, Random(seed), captured.assumptions, captured.projection)
        val activeSweep = prepared.sweep?.takeIf { captured.invariants != null }
        shadow.invariants = activeSweep?.network(problem.numIntVars, problem.numBoolVars)
        initialWeights?.copyInto(shadow.weights.factorWeights)
        return TrajectoryView(
            view.name.lowercase(), shadow,
            requireNotNull(prepared.coordinates),
            captured.shaping.objective, prepared.pipeline.reconstruct,
            { activeSweep?.sweep(shadow.assignment, shadow.rootDomains) }, captured.seeding.ownerInt,
        )
    }

    private fun allocationCounter(): ThreadMXBean =
        (ManagementFactory.getThreadMXBean() as ThreadMXBean).apply {
            check(isThreadAllocatedMemorySupported)
            isThreadAllocatedMemoryEnabled = true
        }
}
