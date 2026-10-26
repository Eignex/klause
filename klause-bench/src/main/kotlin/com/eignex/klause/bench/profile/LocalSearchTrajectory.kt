package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.CostShaping
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.GreedyInit
import com.eignex.klause.localsearch.movesource.MoveSource
import com.eignex.klause.localsearch.movesource.MoveSourceId
import com.eignex.klause.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.localsearch.movesource.Phase
import com.eignex.klause.localsearch.movesource.Pool
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
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
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LocalSearchTrajectory {
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
        val required = setOf("suite", "problem", "sha256", "recipe", "seed", "moves")
        val optional = setOf("sliceMillis", "sliceWork", "greedy", "repairs", "controls", "center", "source64", "channel")
        require(options.keys.containsAll(required) && options.keys.all {
            it in required || it in optional
        })
        val bytes = trajectorySourceBytes(options.getValue("suite"), options.getValue("problem"),
            options.getValue("sha256"), options["source64"])
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("source.xml").writeBytes(bytes)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        val parsed = Xcsp3.parse(bytes.decodeToString())
        require(parsed.problem.numBoolVars.toLong() + parsed.problem.numIntVars <= TRAJECTORY_COORDINATE_LIMIT)
        require(parsed.problem.numFactors <= TRAJECTORY_FACTOR_LIMIT)
        val objective = parsed.objective?.toLinearObjective()
        require(parsed.sense == ObjectiveSense.MINIMIZE)
        val channel = options["channel"]?.let { ChannelView.valueOf(it) }
        require(channel != null || objective != null)
        val engine = if (channel == null) FiniteEngine.MIXED else FiniteEngine.LOCAL_SEARCH
        val variants = if (channel == null) listOf(
            ViewSpec("false", false, ChannelView.Maintained), ViewSpec("true", true, ChannelView.Maintained),
        ) else listOf(ViewSpec("maintained", false, ChannelView.Maintained), ViewSpec(channel.name, false, channel))
        val seed = options.getValue("seed").toLong()
        val moves = options.getValue("moves").toLong().also { require(it in 1..1_000_000) }
        val sliceMillis = options["sliceMillis"]?.toLong()?.also { require(it > 0L) } ?: Long.MAX_VALUE
        val sliceWork = options["sliceWork"]?.toLong()?.also { require(it > 0L) } ?: Long.MAX_VALUE
        val repairGreedy = options["greedy"]?.toBooleanStrict() ?: true
        val repairCapture = options["repairs"]?.toBooleanStrict() ?: false
        require(!repairCapture || objective != null)
        val exactControls = options["controls"]?.toBooleanStrict() ?: false
        val sourceCheck = if (repairCapture) parsed.problem.bake() else null
        require(!exactControls || repairCapture)
        val sourceInputs = parsed.intVarNames.values.distinct().filter { it !in parsed.definedVars }.toIntArray()
        out.resolve("source-inputs.json").writeText(buildJsonObject {
            put("declaredInputs", JsonArray(sourceInputs.map(::JsonPrimitive)))
            put("declaredDefinitions", JsonArray(parsed.definedVars.map(::JsonPrimitive)))
            put("scope", "named source declarations excluding frontend-declared definitions")
        }.toString())
        val initial = options["center"]?.split(',')?.map(String::toLong)?.toLongArray()?.let {
            require(it.size == parsed.problem.numIntVars && parsed.problem.numBoolVars == 0)
            Sample(booleanArrayOf(), it)
        }
        require(initial == null || parsed.definedVars.isEmpty())
        require(initial == null || channel == null)
        val views = ArrayList<TrajectoryView>()
        val observations = ArrayList<Observation>()
        out.resolve("capture-policy.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha() ?: System.getenv("LAB_WORKTREE")?.let(::File)?.name
                ?.takeIf { it.matches(Regex("[0-9a-f]{40}")) })
            put("sourceSha256", sha256(bytes))
            put("sourceMode", if ("source64" in options) "embedded" else "catalog")
            put("declaredSemantics", parsed.semantics.declared.toString())
            put("effectiveSemantics", parsed.semantics.effective.toString())
            put("settings", parsed.problem.settings.toString())
            put("javaRuntime", System.getProperty("java.runtime.version"))
            put("os", System.getProperty("os.name"))
            put("architecture", System.getProperty("os.arch"))
            put("channel", channel?.name)
            put("preparationEngine", engine.name)
            put("objectiveTreatment", when {
                objective == null -> "none"
                channel == null -> "protected versus substituted"
                else -> "protected in both views"
            })
            put("pairedStateLimit", 12)
            put("candidatesPerPairLimit", 32)
            put("coordinateLimit", TRAJECTORY_COORDINATE_LIMIT)
            put("factorLimit", TRAJECTORY_FACTOR_LIMIT)
            put("pairing", "complete source-coordinate round trip; primitive common destinations")
            put("diagnosticWork", "separate private states; two full loads and at most 64 reversible probes per pair")
            put("sourceVerdictClaim", false)
        }.toString())
        for (variant in if (initial == null) variants else variants.take(1)) {
            val rewrite = variant.label
            val preparationStarted = System.nanoTime()
            val stop = Cancellation.after(60.seconds)
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                if (parsed.definedVars.isEmpty()) SourceAttachment(parsed.problem, parsed.definitionHints)
                else SourceAttachment(parsed.problem, parsed.definedVars, emptyList(), parsed.definitionHints),
                if (objective == null) ExecutionOperation.SATISFY else ExecutionOperation.OPTIMIZE,
                engine, objective = objective, cancellation = stop,
            ))
            val plan = requireNotNull(invocation.preparedPlan)
            val prepared = FinitePipeline.prepare(FinitePipelineRequest(
                requireNotNull(plan.model), engine, objective,
                cancellation = stop, solveCancellation = stop, presolveBudget = PresolveBudget(1_375_000_000L),
                presolveConfig = if (initial == null) PresolveConfig.DEFAULT else PresolveConfig.NONE,
                preparedPlan = plan, transformObjective = variant.transformObjective, randomSeed = seed,
            ))
            check(!stop() && prepared.presolve?.infeasible != true)
            val preparedProblem = prepared.problem as BakedProblem
            val problem = if (variant.channel == ChannelView.InverseAssignment) {
                val inverse = inverseAssignmentView(preparedProblem, stop)
                if (inverse == null) {
                    out.resolve("decline-$rewrite.txt").writeText("unsupported-inverse-assignment\ncancelled=${stop()}\n")
                    continue
                }
                inverse.bake(stop)
            } else preparedProblem
            val workingObjective = prepared.objective
            require(initial == null || (initial.numIntVars == problem.numIntVars &&
                prepared.reconstruct(initial) == initial)) { "explicit center needs an unchanged coordinate frame" }
            val hints = heuristicHints(invocation, prepared, stop)
            val maintained = hints?.let { getter(it, "getSweep") as DefinitionalSweep? }
            val sweep = variant.channel.sweep(maintained, stop)
            check(!stop())
            val gradient = hints?.let { getter(it, "getObjective") as IncrementalObjective? }
            out.resolve("model-$rewrite.json").writeText(buildJsonObject {
                put("preparationNanos", System.nanoTime() - preparationStarted)
                put("objective", workingObjective.toString())
                put("channel", variant.channel.name)
                put("maintainedDefinitions", maintained?.size ?: 0)
                put("retainedDefinitions", sweep?.size ?: 0)
                put("functionalObjectiveTreatment", "same source functional objective; channel filtering changes ownership")
                put("factors", buildJsonArray {
                    for (factor in problem.factors) add(buildJsonObject {
                        put("kind", factor::class.simpleName)
                        put("key", factor.structuralKey().toString())
                    })
                })
                put("domains", JsonArray(problem.rootIntDomains().map { JsonPrimitive(it.toString()) }))
                put("sourceBooleanSlots", JsonArray((0 until parsed.problem.numBoolVars).map {
                    JsonPrimitive(if (it < problem.numBoolVars) it else -1)
                }))
                put("sourceIntegerSlots", JsonArray((0 until parsed.problem.numIntVars).map {
                    JsonPrimitive(if (it < problem.numIntVars) it else -1)
                }))
                put("sourceIntegerNames", buildJsonObject {
                    for ((name, variable) in parsed.intVarNames) put(name, variable)
                })
                put("correspondenceAdmission", "slot-preserving load plus complete source round trip")
                put("sourceVerdictClaim", false)
            }.toString())
            var baseline: List<Any?>? = null
            for (mode in if (repairCapture) listOf("initial", "walk", "repair") else listOf("initial", "walk")) {
                val walk = mode != "initial"
                val label = if (mode == "walk") rewrite.toString() else "$mode-$rewrite"
                out.resolve("walk-$label.jsonl").bufferedWriter().use { writer ->
                    var picks = 0L
                    var captured: LocalSearchState? = null
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
                                put("randomState", trajectoryRandomBoundary(state))
                                put("workingIntegers", JsonArray((0 until problem.numIntVars).map {
                                    JsonPrimitive(state.assignment.intValue(it))
                                }))
                                put("weightsAllocated", state.weights.allocated)
                                if (state.weights.allocated) {
                                    put("weights", JsonArray(state.weights.factorWeights.map(::JsonPrimitive)))
                                }
                                if (pick == 0L) {
                                    put("unreadIntegers", buildJsonArray {
                                        for (variable in 0 until problem.numIntVars) {
                                            if (state.projection.intOccurrences[variable].isEmpty()) {
                                                add(JsonPrimitive(variable))
                                            }
                                        }
                                    })
                                    put("definedIntegers", buildJsonArray {
                                        for (variable in 0 until problem.numIntVars) {
                                            if (state.invariants?.isDefinedInt(variable) == true) {
                                                add(JsonPrimitive(variable))
                                            }
                                        }
                                    })
                                }
                                put("sourceIntegers", buildJsonObject {
                                    for ((name, variable) in parsed.intVarNames) put(name, sample.intValue(variable))
                                })
                                put("degrees", JsonArray(state.factorDegree.map(::JsonPrimitive)))
                                put("violated", JsonArray(state.violated.toIntArray().map(::JsonPrimitive)))
                            }.toString())
                            writer.flush()
                            if (!walk) {
                                captured = state
                                observations += Observation("initial-$rewrite", sample)
                                throw Captured
                            }
                        }
                    }
                    val recipeName = options.getValue("recipe")
                    val selected = if (recipeName == "source-coordinate-descent") LocalSearchRecipe(recipeName,
                        SourceDrivenStrategy(sources = listOf(ConfiguredSource(ObjectiveSeed())),
                            acceptance = AcceptanceRule.GreedyDescent, feasibleDescent = FeasibleDescent.SelfOwned))
                    else LocalSearchCatalog.byLabel(recipeName)
                    val recipe = selected.withSources {
                        listOf(ConfiguredSource(recorder)) + it
                    }
                    val solver = LocalSearchSolver(
                        problem, strategy = recipe.strategy, optimizeStrategy = recipe.optimizeStrategy,
                        greedyRepairOnRestart = walk && repairGreedy,
                        definitionalSweep = sweep, perMoveInvariants = sweep != null && recipe.perMoveInvariants,
                        seedImplicitOnRestart = recipe.seedImplicitOnRestart,
                    )
                    val trace = if (mode == "repair") RepairTrajectory.attach(solver,
                        "${options.getValue("recipe")}; linear shaping 1; seeded tie stream $seed") else null
                    val params = LocalSearchParams(
                        randomSeed = seed, maxFlips = if (walk) moves else 1L, cancellation = stop,
                        costShaping = CostShaping.Linear(lambda = 1.0), lsObjective = gradient,
                        normalizeWeightsByClass = true, reportResiduals = true,
                        initialAssignment = initial,
                    )
                    if (workingObjective == null) solver.resumableSolve(params).use { run ->
                        val result = try {
                            var observed = run.runSlice(stop, sliceMillis, sliceWork)
                            while (observed == null && !stop()) observed = run.runSlice(stop, sliceMillis, sliceWork)
                            observed
                        } catch (_: Captured) {
                            null
                        }
                        out.resolve("result-$label.txt").writeText(
                            "picks=$picks\ncancelled=${stop()}\nresult=$result\nstats=${run.stats}\n",
                        )
                    } else solver.resumable(workingObjective, params).use { run ->
                        val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
                        check(allocation.isThreadAllocatedMemorySupported)
                        allocation.isThreadAllocatedMemoryEnabled = true
                        val thread = Thread.currentThread().threadId()
                        val beforeBytes = allocation.getThreadAllocatedBytes(thread)
                        val started = System.nanoTime()
                        val result = try {
                            var observed = run.runSlice(stop, sliceMillis, sliceWork) {}
                            while (observed == null && !stop()) {
                                observed = run.runSlice(stop, sliceMillis, sliceWork) {}
                            }
                            observed
                        } catch (_: Captured) {
                            null
                        }
                        val runNanos = System.nanoTime() - started
                        val runBytes = allocation.getThreadAllocatedBytes(thread) - beforeBytes
                        if (walk && result != null && sourceCheck != null) {
                            RepairTrajectory.writeResult(out.resolve("witness-$label.json"), result, prepared,
                                sourceCheck, picks, runNanos, runBytes)
                        }
                        out.resolve("result-$label.txt").writeText(
                            "picks=$picks\ncancelled=${stop()}\nresult=$result\nstats=${run.stats}\n",
                        )
                        if (walk && result != null && !stop()) {
                            val stats = run.stats.ls
                            val signature = listOf(result.assignment, result.objectiveValue, picks, stats.moves.sum,
                                run.stats.search.restarts.sum, stats.stalls.sum, stats.boardRestartChanges.sum,
                                stats.boardRestartDeclines.sum)
                            if (mode == "walk") baseline = signature
                            if (mode == "repair") {
                                check(baseline == signature) { "repair observation changed the fixed-work walk" }
                                out.resolve("parity-$rewrite.txt").writeText(
                                    "witness/objective/picks/work/restarts/stalls/board counts match\n")
                            }
                        }
                    }
                    if (trace != null) {
                        RepairTrajectory.write(trace, prepared, sourceInputs, out.resolve("repairs-$rewrite.jsonl"))
                        if (exactControls) RepairTrajectory.controls(trace, problem, checkNotNull(workingObjective),
                            out.resolve("controls-$rewrite.jsonl"), seed, prepared, checkNotNull(sourceCheck))
                    }
                    if (!walk && captured != null) {
                        val initial = checkNotNull(captured)
                        val shadow = LocalSearchState(
                            initial.model, Random(seed), initial.assumptions, initial.projection,
                        )
                        val activeSweep = sweep?.takeIf { initial.invariants != null }
                        shadow.invariants = activeSweep?.network(problem.numIntVars, problem.numBoolVars)
                        if (initial.weights.allocated) {
                            initial.weights.factorWeights.copyInto(shadow.weights.factorWeights)
                        }
                        views += TrajectoryView(
                            rewrite.toString(), shadow,
                            VarRemap(
                                IntArray(parsed.problem.numBoolVars) { if (it < problem.numBoolVars) it else -1 },
                                IntArray(parsed.problem.numIntVars) { if (it < problem.numIntVars) it else -1 },
                            ),
                            initial.shaping.objective, prepared.reconstruct,
                            { activeSweep?.sweep(shadow.assignment, shadow.rootDomains) },
                            initial.seeding.ownerInt,
                        )
                        traceGreedy(initial, out.resolve("greedy-$rewrite.jsonl")) { position, slot ->
                            val boundary = if (position == problem.numBoolVars + problem.numIntVars) {
                                "greedy-complete-$rewrite"
                            } else "greedy-before-$rewrite-$position"
                            observations += Observation(
                                boundary, prepared.reconstruct(initial.assignment.snapshot()), slot,
                            )
                        }
                    }
                }
            }
        }
        out.resolve("paired.jsonl").bufferedWriter().use { writer ->
            check(observations.size <= 12)
            if (views.size != 2) {
                writer.appendLine(buildJsonObject {
                    put("decline", if (initial != null) "single-representation" else "two-initial-states-unavailable")
                    put("capturedViews", views.size)
                    put("sourceVerdictClaim", false)
                }.toString())
                return@use
            }
            for ((label, source, focus) in observations) {
                val candidates = ArrayList<Move>()
                for (variable in 0 until source.numBoolVars) {
                    if (candidates.size == 32) break
                    val move = Move.BoolFlip(variable)
                    if (views.all { it.admits(move) }) candidates += move
                }
                val first = focus - parsed.problem.numBoolVars
                val integerOrder = (listOf(first).filter { it in 0 until source.numIntVars } +
                    (0 until source.numIntVars)).distinct()
                for (variable in integerOrder) {
                    if (candidates.size == 32) break
                    val current = source.intValue(variable)
                    val values = views.flatMap { view ->
                        val target = view.coordinates.int(variable)
                        if (target < 0) emptyList() else {
                            val domain = view.state.rootDomains[target]
                            buildList {
                                add(domain.min)
                                add(domain.max)
                                if (current > domain.min) add(domain.lower(current))
                                if (current < domain.max) add(domain.higher(current))
                            }
                        }
                    }
                    for (value in values.distinct()) {
                        val move = Move.IntSet(variable, value)
                        if (value != current && candidates.size < 32 && views.all { it.admits(move) }) {
                            candidates += move
                        }
                    }
                }
                writer.appendLine(buildJsonObject {
                    put("observation", label)
                    put(
                        "candidatePolicy",
                        "common admissible root endpoints and adjacent values; focus coordinate first",
                    )
                    put("comparison", trajectoryPair(source, candidates, views[0], views[1]))
                }.toString())
                writer.flush()
            }
        }
    }

    private object Captured : RuntimeException(null, null, false, false)

    private data class ViewSpec(val label: String, val transformObjective: Boolean, val channel: ChannelView)

    private data class Observation(val label: String, val source: Sample, val focus: Int = -1)

    private fun traceGreedy(state: LocalSearchState, file: File, capture: (Int, Int) -> Unit) {
        val initializer = GreedyInit()
        val pass = checkNotNull(initializer.javaClass.methods.single {
            it.name.substringBefore('$') == "pass" && it.parameterCount == 1
        }.invoke(initializer, state))
        val order = pass.javaClass.getDeclaredField("order").apply { isAccessible = true }.get(pass) as IntArray
        var position = 0
        file.bufferedWriter().use { writer ->
            val observe = {
                val slot = order[position++]
                if (position - 1 in setOf(0, 49, 68, order.lastIndex)) capture(position - 1, slot)
                writer.appendLine(buildJsonObject {
                    put("position", position - 1)
                    put("slot", slot)
                    put("cost", state.cost)
                    put("integers", JsonArray((0 until state.problem.numIntVars).map {
                        JsonPrimitive(state.assignment.intValue(it))
                    }))
                    put("randomState", trajectoryRandomBoundary(state))
                }.toString())
                writer.flush()
                false
            }
            val completed = pass.javaClass.methods.single { it.name == "advance" }.invoke(pass, observe)
            check(completed == true && position == order.size)
            capture(order.size, -1)
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
