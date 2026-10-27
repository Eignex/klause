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
import com.eignex.klause.localsearch.ObjectiveGuidance
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.MoveSource
import com.eignex.klause.localsearch.movesource.MoveSourceId
import com.eignex.klause.localsearch.movesource.Phase
import com.eignex.klause.localsearch.movesource.Pool
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ObjectiveViewComparison {
    private enum class View(val leafHotspots: Boolean = false, val geometricSteps: Boolean = false) {
        RETAINED, SUBSTITUTED, FUNCTIONAL, WORKER_GEOMETRIC,
        TERMS_ADJACENT, LEAVES_ADJACENT(true), TERMS_GEOMETRIC(false, true), LEAVES_GEOMETRIC(true, true);

        val geometry: Boolean get() = ordinal >= TERMS_ADJACENT.ordinal
    }

    private data class Prepared(
        val pipeline: FinitePipelinePreparation,
        val coordinates: VarRemap?,
        val sweep: DefinitionalSweep?,
        val gradient: IncrementalObjective?,
        val nanos: Long,
        val allocatedBytes: Long,
    )

    private data class Boundary(val label: String, val source: Sample)

    private object UnsupportedCones : RuntimeException(null, null, false, false)

    private object InitialCaptured : RuntimeException(null, null, false, false)

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val requests = trajectoryBatch(args)
        if (requests != null) {
            for ((index, request) in requests.withIndex()) {
                capture(request.toTypedArray(), out.resolve("$index").apply { mkdirs() })
            }
        } else capture(args, out)
        compressTrajectoryOutput(out)
    }

    private fun capture(args: Array<String>, out: File) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val required = setOf("suite", "problem", "sha256")
        val optional = setOf(
            "source64", "sourceGzip64", "recipe", "seed", "moves", "timeoutMillis", "screen", "greedy",
            "preparationEngine", "basins", "geometry", "center64", "retainedWorker",
        )
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
        val basins = options["basins"]?.toBooleanStrict() ?: false
        val preparationEngine = when (options["preparationEngine"] ?: "mixed") {
            "mixed" -> FiniteEngine.MIXED
            "ls" -> FiniteEngine.LOCAL_SEARCH
            else -> error("preparationEngine must be mixed or ls")
        }
        out.resolve("policy.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha() ?: System.getenv("LAB_WORKTREE")?.let { File(it).name })
            put("recipe", recipe)
            put("preparationEngine", preparationEngine.id)
            put("seed", seed)
            put("moves", moves)
            put("timeoutMillisIncludingPreparation", timeout)
            put("greedy", greedy)
            put("basinTrace", basins)
            put("basinPartitionClaim", false)
            if (basins) put("basinIntegerInputs", JsonArray(source.intVarNames.values.distinct()
                .filter { it !in source.definedVars }.map(::JsonPrimitive)))
            put("normalizedClassWeights", true)
            put("costShaping", "linear lambda=1")
            put("maxSourcePoints", 6)
            put("maxCandidatesPerPair", 32)
            put("probeTiming", "outside walk allowance")
            put("walkTiming", "instrumented; parsing common and excluded")
            put("proposalEquivalenceClaim", false)
            put("proposalGeometryControls", options["geometry"] == "true")
            put("initialization", if ("center64" in options) "checked source warm start" else "ordinary recipe")
            put("sourceVerdictClaim", false)
        }.toString())
        val suppliedCenter = options["center64"]?.let {
            require(it.length <= 524288)
            sourceCenter(Base64.getDecoder().decode(it).decodeToString())
        }
        val commonBytes = trajectoryThreadBytes()
        val commonStarted = System.nanoTime()
        val sourceCheck = source.problem.bake()
        out.resolve("source-check-preparation.json").writeText(buildJsonObject {
            put("elapsedNanos", System.nanoTime() - commonStarted)
            put("allocatedBytes", trajectoryThreadBytes() - commonBytes)
            put("scope", "common original source checker; outside native walk allowance")
        }.toString())
        val geometry = options["geometry"]?.toBooleanStrict() ?: false
        if (suppliedCenter != null && LocalSearchCatalog.byLabel(recipe).seedImplicitOnRestart) {
            out.resolve("decline.json").writeText("{\"decline\":\"center-implicit-ownership-unsupported\"}")
            return
        }
        val states = ArrayList<TrajectoryView>()
        val boundaries = ArrayList<Boundary>()
        for (view in View.entries.filter {
            if (it == View.WORKER_GEOMETRIC) options["retainedWorker"] == "true" else geometry || !it.geometry
        }) {
            val directory = out.resolve(view.name.lowercase()).apply { mkdirs() }
            val prepared = prepare(source, view, preparationEngine, seed, timeout, directory,
                suppliedCenter != null) ?: continue
            directory.resolve("model.json").writeText(buildJsonObject {
                put("preparationNanos", prepared.nanos)
                put("preparationAllocatedBytes", prepared.allocatedBytes)
                put("objective", prepared.pipeline.objective.toString())
                put("functionalObjective", prepared.gradient?.javaClass?.name)
                put("sweepPresent", prepared.sweep != null)
                put("transformObjective", view == View.SUBSTITUTED)
                put("functionalGrading", view == View.FUNCTIONAL)
                put("retainedGeometricConsumer", view == View.WORKER_GEOMETRIC)
                put("hotspots", if (view.geometry) {
                    if (view.leafHotspots) "cone leaves" else "canonical terms"
                } else "grading objective")
                put("seedSteps", if (view.geometry) {
                    if (view.geometricSteps) "geometric leaves" else "adjacent leaves"
                } else "grading objective")
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
            if ((view == View.FUNCTIONAL || view.geometry) &&
                (prepared.gradient == null || !LocalSearchCatalog.byLabel(recipe).perMoveInvariants)
            ) {
                directory.resolve("decline.json").writeText("{\"decline\":\"no-maintained-functional-objective\"}")
                continue
            }
            val centerBytes = trajectoryThreadBytes()
            val centerStarted = System.nanoTime()
            val projection = suppliedCenter?.let { point ->
                if (point.numBoolVars != source.problem.numBoolVars || point.numIntVars != source.problem.numIntVars ||
                    !FiniteConflictSelection.checkSource(sourceCheck, point, Cancellation.after(10.seconds))
                ) null to "source-infeasible-or-check-incomplete"
                else projectedSourceCenter(point, prepared.pipeline.problem as BakedProblem,
                    prepared.coordinates, prepared.sweep, prepared.pipeline.reconstruct)
            }
            val centerNanos = System.nanoTime() - centerStarted
            if (suppliedCenter != null) {
                directory.resolve("center.json").writeText(buildJsonObject {
                    put("source", trajectorySource(suppliedCenter))
                    put("decline", projection?.second)
                    put("mappingAndNativeCheckNanos", centerNanos)
                    put("mappingAndNativeCheckBytes", trajectoryThreadBytes() - centerBytes)
                    put("nativeSourceAccepted", projection?.first != null)
                    put("initialization", "checked source warm start; generic greedy bypassed")
                    put("independentWitnessCheck", false)
                }.toString())
                if (projection?.first == null) continue
            }
            val initial = try {
                run(source, prepared, view, recipe, seed, 1, false, 60000, directory, boundaries,
                    initial = projection?.first)
            } catch (_: UnsupportedCones) {
                directory.resolve("decline.json").writeText("{\"decline\":\"unsupported-objective-cones\"}")
                continue
            }
            if (initial != null) {
                states += initial
                val qualified = captureObjectiveProposals(initial, boundaries.last().source, prepared.gradient,
                    prepared.pipeline.objective, requireNotNull(source.objective).toLinearObjective(), sourceCheck,
                    directory.resolve("proposals.json"))
                if (view.geometry && !qualified) continue
            }
            val remaining = timeout - (prepared.nanos + centerNanos + 999999) / 1000000
            if (remaining > 0) run(source, prepared, view, recipe, seed, moves, greedy, remaining,
                directory, boundaries, walk = true, basins = basins, initial = projection?.first)
            else directory.resolve("walk-decline.json").writeText("{\"decline\":\"preparation-time-budget\"}")
        }
        out.resolve("paired.jsonl").bufferedWriter().use { writer ->
            check(boundaries.size <= View.entries.size * 2)
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

    private fun prepare(
        source: Xcsp3Problem,
        view: View,
        engine: FiniteEngine,
        seed: Long,
        timeout: Long,
        out: File,
        explicitCenter: Boolean,
    ): Prepared? {
        val started = System.nanoTime()
        val allocation = allocationCounter()
        val before = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId())
        val stop = Cancellation.after(timeout.milliseconds)
        val objective = requireNotNull(source.objective).toLinearObjective()
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            if (source.definedVars.isEmpty()) SourceAttachment(source.problem, source.definitionHints)
            else SourceAttachment(source.problem, source.definedVars, emptyList(), source.definitionHints),
            ExecutionOperation.OPTIMIZE, engine, objective = objective, cancellation = stop,
        ))
        val plan = invocation.preparedPlan
        if (plan == null || stop()) {
            out.resolve("decline.json").writeText("{\"decline\":\"source-preparation-unavailable\"}")
            return null
        }
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(
            requireNotNull(plan.model), engine, objective,
            cancellation = stop, solveCancellation = stop, presolveBudget = PresolveBudget(1_375_000_000L),
            preparedPlan = plan, transformObjective = view == View.SUBSTITUTED, randomSeed = seed,
            presolveConfig = if (explicitCenter) PresolveConfig.NONE else PresolveConfig.DEFAULT,
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
        basins: Boolean = false,
        initial: Sample? = null,
    ): TrajectoryView? {
        val problem = prepared.pipeline.problem as BakedProblem
        val objective = requireNotNull(prepared.pipeline.objective)
        val sourceObjective = requireNotNull(source.objective).toLinearObjective()
        val label = if (walk) "walk" else "initial"
        var first: LocalSearchState? = null
        var initialWeights: DoubleArray? = null
        var firstSource: Sample? = null
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
                        if (initial != null) check(sameSource(prepared.pipeline.reconstruct(initial),
                            prepared.pipeline.reconstruct(state.assignment.snapshot())))
                        if (view.geometry && !configureObjectiveProposals(state, requireNotNull(prepared.gradient),
                            view.leafHotspots, view.geometricSteps)) throw UnsupportedCones
                        firstSource = prepared.pipeline.reconstruct(state.assignment.snapshot())
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
            val trace = if (walk && basins) RepairTrajectory.attach(solver,
                "${view.name.lowercase()} $recipeName; normalized linear shaping 1; worker seed $seed") else null
            val params = LocalSearchParams(
                randomSeed = seed, maxFlips = moves, cancellation = stop,
                costShaping = CostShaping.Linear(lambda = 1.0),
                lsObjective = prepared.gradient.takeIf { view == View.FUNCTIONAL || view == View.WORKER_GEOMETRIC },
                objectiveGuidance = if (view == View.WORKER_GEOMETRIC) {
                    ObjectiveGuidance.Geometric
                } else ObjectiveGuidance.Functional,
                normalizeWeightsByClass = true, reportResiduals = true, reportPhaseTimes = true,
                initialAssignment = initial,
            )
            out.resolve("$label-incumbents.jsonl").bufferedWriter().use { incumbents ->
                var improvements = 0
                var firstIncumbentNanos: Long? = null
                var lastIncumbent: MinimizeResult.WithSample? = null
                var lastIncumbentNanos: Long? = null
                solver.resumable(objective, params).use { run ->
                    val onIncumbent: (MinimizeResult.WithSample) -> Unit = { candidate ->
                        val elapsed = System.nanoTime() - started
                        if (firstIncumbentNanos == null) firstIncumbentNanos = elapsed
                        lastIncumbent = candidate
                        lastIncumbentNanos = elapsed
                        if (improvements++ < 16) {
                            val point = prepared.pipeline.reconstruct(candidate.sample)
                            incumbents.appendLine(buildJsonObject {
                                put("elapsedNanos", elapsed)
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
                        put("allocatedBytes",
                            allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before)
                        put("modelPreparationNanos", prepared.nanos)
                        put("modelPreparationAllocatedBytes", prepared.allocatedBytes)
                        put("sourceVerdictClaim", false)
                        put("cliRoutingEquivalence", false)
                        put("nativeIncumbentsObserved", improvements)
                        put("firstNativeIncumbentNanos", firstIncumbentNanos)
                        put("lastNativeIncumbentNanos", lastIncumbentNanos)
                        lastIncumbent?.let { candidate ->
                            val point = prepared.pipeline.reconstruct(candidate.sample)
                            put("lastObservedSource", trajectorySource(point))
                            put("lastObservedSourceObjective", sourceObjective.evaluateExact(point).toString())
                            put("independentWitnessCheck", false)
                            put("sourceInputChanges", firstSource?.let { center ->
                                source.intVarNames.values.distinct().filter { it !in source.definedVars }
                                    .count { center.intValue(it) != point.intValue(it) }
                            })
                            put("sourceBooleanChanges", firstSource?.let { center ->
                                (0 until center.numBoolVars).count { center.boolValue(it) != point.boolValue(it) }
                            })
                            put("centerObjective", firstSource?.let { sourceObjective.evaluateExact(it).toString() })
                            val checkStarted = System.nanoTime()
                            put("nativeSourceAccepted", FiniteConflictSelection.checkSource(source.problem.bake(),
                                point, Cancellation.after(10.seconds)))
                            put("nativeSourceCheckNanos", System.nanoTime() - checkStarted)
                        }
                        observed?.assignment?.let { point ->
                            put("source", trajectorySource(prepared.pipeline.reconstruct(point)))
                            put("objective",
                                sourceObjective.evaluateExact(prepared.pipeline.reconstruct(point)).toString())
                        }
                    }.toString())
                    out.resolve("$label-stats.txt").writeText(run.stats.toString())
                }
                if (trace != null) {
                    val inputs = source.intVarNames.values.distinct().filter { it !in source.definedVars }.toIntArray()
                    RepairTrajectory.write(trace, prepared.pipeline, inputs, out.resolve("walk-basins.jsonl"))
                }
            }
        }
        if (walk) return null
        val captured = first ?: return null
        out.resolve("initial-policy.json").writeText(buildJsonObject {
            put("activeInvariantNetwork", captured.invariants != null)
            put("heuristicObjective", captured.shaping.objective?.javaClass?.name)
            put("objectiveIntegerHotspots", JsonArray(captured.shaping.objectiveIntVars.map(::JsonPrimitive)))
            put("observationPhase", "after private initialization stop; outside walk")
        }.toString())
        val shadow = LocalSearchState(captured.model, Random(seed), captured.assumptions, captured.projection)
        val activeSweep = prepared.sweep?.takeIf { captured.invariants != null }
        shadow.invariants = activeSweep?.network(problem.numIntVars, problem.numBoolVars)
        initialWeights?.copyInto(shadow.weights.factorWeights)
        run {
            val setter = shadow.shaping.javaClass.methods.single {
                it.name.substringBefore('$') == "setObjective" && it.parameterCount == 1
            }
            setter.invoke(shadow.shaping, captured.shaping.objective)
            if (view.geometry) check(configureObjectiveProposals(shadow, requireNotNull(prepared.gradient),
                view.leafHotspots, view.geometricSteps))
        }
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
