package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
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
import com.eignex.klause.localsearch.movesource.MoveSourceCatalog
import com.eignex.klause.localsearch.movesource.MoveSourceId
import com.eignex.klause.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.localsearch.movesource.Phase
import com.eignex.klause.localsearch.movesource.Pool
import com.eignex.klause.localsearch.movesource.SatisfiedStructured
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
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LocalSearchTrajectory {
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
        val required = setOf("suite", "problem", "sha256", "recipe", "seed", "moves")
        val optional = setOf(
            "sliceMillis", "sliceWork", "greedy", "repairs", "controls", "center", "source64", "sourceGzip64",
            "channel", "presolve", "center64", "timeoutMillis", "traceCoordinates",
            "ownerReplay", "ownerWork", "ownerFragmentWork", "ownerSelectors", "selectorsFromWalk", "ownerConflicts",
            "ownerRecipes", "owners", "ownerSeed",
            "format", "preparationEngine",
        )
        require(options.keys.containsAll(required) && options.keys.all {
            it in required || it in optional || it == "selectors"
        })
        require(options["presolve"] == null || options["presolve"] in setOf("default", "off"))
        val bytes = trajectorySourceBytes(
            options.getValue("suite"), options.getValue("problem"), options.getValue("sha256"),
            options["source64"], options["sourceGzip64"],
        )
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val format = options["format"] ?: "xcsp3"
        require(format in setOf("xcsp3", "mps"))
        out.resolve(if (format == "mps") "source.mps" else "source.xml").writeBytes(bytes)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        val parsed = trajectoryModel(bytes, format)
        if (parsed.problem.numBoolVars.toLong() + parsed.problem.numIntVars > TRAJECTORY_COORDINATE_LIMIT ||
            parsed.problem.numFactors > TRAJECTORY_FACTOR_LIMIT
        ) {
            out.resolve("decline.json").writeText(buildJsonObject {
                put("decline", "source-size")
                put("sourceSha256", options.getValue("sha256"))
                put("declaredSemantics", parsed.semantics.declared.toString())
                put("effectiveSemantics", parsed.semantics.effective.toString())
                put("settings", parsed.problem.settings.toString())
                put("sourceBooleans", parsed.problem.numBoolVars)
                put("sourceIntegers", parsed.problem.numIntVars)
                put("sourceFactors", parsed.problem.numFactors)
                put("coordinateLimit", TRAJECTORY_COORDINATE_LIMIT)
                put("factorLimit", TRAJECTORY_FACTOR_LIMIT)
                put("sourceVerdictClaim", false)
            }.toString())
            return
        }
        val objective = parsed.objective?.toLinearObjective()
        require(parsed.sense == ObjectiveSense.MINIMIZE)
        val channel = options["channel"]?.let { ChannelView.valueOf(it) }
        require(channel != null || objective != null)
        val engine = when (options["preparationEngine"]) {
            null -> if (channel == null) FiniteEngine.MIXED else FiniteEngine.LOCAL_SEARCH
            "mixed" -> FiniteEngine.MIXED
            "ls" -> FiniteEngine.LOCAL_SEARCH
            else -> error("unsupported trajectory preparation engine")
        }
        val variants = if (channel == null) listOf(
            ViewSpec("false", false, ChannelView.Maintained), ViewSpec("true", true, ChannelView.Maintained),
        ) else listOf(ViewSpec("maintained", false, ChannelView.Maintained), ViewSpec(channel.name, false, channel))
        val seed = options.getValue("seed").toLong()
        val ownerSeed = options["ownerSeed"]?.toLong() ?: seed
        val moves = options.getValue("moves").toLong().also { require(it in 1..1_000_000) }
        val timeoutMillis = options["timeoutMillis"]?.toLong()?.also { require(it in 100..60000) } ?: 60000L
        val sliceMillis = options["sliceMillis"]?.toLong()?.also { require(it > 0L) } ?: Long.MAX_VALUE
        val sliceWork = options["sliceWork"]?.toLong()?.also { require(it > 0L) } ?: Long.MAX_VALUE
        val repairGreedy = options["greedy"]?.toBooleanStrict() ?: true
        val repairCapture = options["repairs"]?.toBooleanStrict() ?: false
        val traceCoordinates = options["traceCoordinates"]?.toInt()?.also {
            require(it in 1..TRAJECTORY_COORDINATE_LIMIT)
        } ?: 4096
        require(!repairCapture || objective != null)
        val exactControls = options["controls"]?.toBooleanStrict() ?: false
        val selectors = options["selectors"]?.toBooleanStrict() ?: false
        val ownerReplay = options["ownerReplay"]?.toBooleanStrict() ?: false
        val ownerSelectors = options["ownerSelectors"]?.toBooleanStrict() ?: false
        val selectorsFromWalk = options["selectorsFromWalk"]?.toBooleanStrict() ?: false
        val ownerConflicts = options["ownerConflicts"]?.toBooleanStrict() ?: false
        val ownerRecipes = options["ownerRecipes"]?.split(',').orEmpty()
        val ownerSequence = options["owners"]?.split(',')
        require((ownerRecipes.isEmpty() && ownerSequence == null) || ownerReplay || ownerSelectors)
        ownerRecipes.forEach(::trajectoryRecipe)
        val sourceCheck = if (repairCapture || "center" in options || "center64" in options) {
            parsed.problem.bake()
        } else null
        require(!exactControls || repairCapture)
        require(!selectors || repairCapture)
        require(!ownerReplay || exactControls)
        require(!ownerSelectors || selectors)
        require(!selectorsFromWalk || selectors)
        require(!ownerConflicts || selectorsFromWalk)
        val sourceInputs = parsed.intVarNames.values.distinct().filter { it !in parsed.definedVars }.toIntArray()
        out.resolve("source-inputs.json").writeText(buildJsonObject {
            put("declaredInputs", JsonArray(sourceInputs.map(::JsonPrimitive)))
            put("declaredDefinitions", JsonArray(parsed.definedVars.map(::JsonPrimitive)))
            put("scope", "named source declarations excluding frontend-declared definitions")
        }.toString())
        require(options["center"] == null || options["center64"] == null)
        val initial = options["center64"]?.let {
            require(it.length <= 524288)
            sourceCenter(Base64.getDecoder().decode(it).decodeToString())
        }
            ?: options["center"]?.split(',')?.map(String::toLong)?.toLongArray()?.let {
            require(it.size == parsed.problem.numIntVars && parsed.problem.numBoolVars == 0)
            Sample(booleanArrayOf(), it)
        }
        require(!selectorsFromWalk || initial == null)
        require(!selectorsFromWalk || channel == null)
        val skipPresolve = initial != null || selectorsFromWalk || options["presolve"] == "off"
        val views = ArrayList<TrajectoryView>()
        val observations = ArrayList<Observation>()
        out.resolve("capture-policy.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha() ?: System.getenv("LAB_WORKTREE")?.let(::File)?.name
                ?.takeIf { it.matches(Regex("[0-9a-f]{40}")) })
            put("sourceSha256", sha256(bytes))
            put("sourceFormat", format)
            put("sourceMode", if ("source64" in options || "sourceGzip64" in options) "embedded" else "catalog")
            put("declaredSemantics", parsed.semantics.declared.toString())
            put("effectiveSemantics", parsed.semantics.effective.toString())
            put("settings", parsed.problem.settings.toString())
            put("javaRuntime", System.getProperty("java.runtime.version"))
            put("os", System.getProperty("os.name"))
            put("architecture", System.getProperty("os.arch"))
            put("channel", channel?.name)
            put("preparationEngine", engine.name)
            put("presolve", if (skipPresolve) "off" else "default")
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
        for (variant in if ((initial != null && channel == null) || selectorsFromWalk) variants.take(1) else variants) {
            val rewrite = variant.label
            val preparationStarted = System.nanoTime()
            val stop = Cancellation.after(timeoutMillis.milliseconds)
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
                presolveConfig = if (skipPresolve) PresolveConfig.NONE else PresolveConfig.DEFAULT,
                preparedPlan = plan, transformObjective = variant.transformObjective, randomSeed = seed,
            ))
            check(!stop() && prepared.presolve?.infeasible != true)
            val preparedProblem = prepared.problem as BakedProblem
            val problem = if (variant.channel == ChannelView.InverseAssignment) {
                val inverse = inverseAssignmentView(preparedProblem, stop)
                if (inverse == null) {
                    out.resolve("decline-$rewrite.txt").writeText(
                        "unsupported-inverse-assignment\ncancelled=${stop()}\n",
                    )
                    continue
                }
                inverse.bake(stop)
            } else preparedProblem
            if (problem.numBoolVars.toLong() + problem.numIntVars > TRAJECTORY_COORDINATE_LIMIT ||
                problem.numFactors > TRAJECTORY_FACTOR_LIMIT
            ) {
                out.resolve("decline-$rewrite.json").writeText(buildJsonObject {
                    put("decline", "working-size")
                    put("workingBooleans", problem.numBoolVars)
                    put("workingIntegers", problem.numIntVars)
                    put("workingFactors", problem.numFactors)
                    put("coordinateLimit", TRAJECTORY_COORDINATE_LIMIT)
                    put("factorLimit", TRAJECTORY_FACTOR_LIMIT)
                    put("sourceVerdictClaim", false)
                }.toString())
                continue
            }
            val workingObjective = prepared.objective
            val hintsStarted = System.nanoTime()
            val hints = heuristicHints(invocation, prepared, stop)
            val maintained = hints?.let { getter(it, "getSweep") as DefinitionalSweep? }
            val sweep = variant.channel.sweep(maintained, stop)
            val gradient = hints?.let { getter(it, "getObjective") as IncrementalObjective? }
            val hintsNanos = System.nanoTime() - hintsStarted
            val coordinates = trajectoryCoordinates(invocation, prepared, stop)
            check(!stop())
            val structured = ownerRecipes.map { name ->
                StructuredInner.prepare(name, { trajectoryRecipe(name) }, problem, sweep, gradient, buildJsonObject {
                    put("definitionHintsSweepAndGradientNs", hintsNanos)
                    put("reusedFromAcquisitionWalk", true)
                    put("channel", variant.channel.name)
                })
            }
            val descent = if (structured.isEmpty()) null else
                RepairDescent(sweep, out.resolve("post-repair-descents-$rewrite"))
            out.resolve("model-$rewrite.json").writeText(buildJsonObject {
                put("preparationNanos", System.nanoTime() - preparationStarted)
                put("objective", workingObjective.toString())
                put("workingBooleans", problem.numBoolVars)
                put("workingIntegers", problem.numIntVars)
                put("workingFactors", problem.numFactors)
                put("channel", variant.channel.name)
                put("maintainedDefinitions", maintained?.size ?: 0)
                put("retainedDefinitions", sweep?.size ?: 0)
                put("coordinateProjection", if (coordinates == null) "declined" else "retained production coordinates")
                put("functionalObjectiveTreatment", if (channel == null) "view-specific" else "held fixed")
                put("factors", buildJsonArray {
                    for (factor in problem.factors) add(buildJsonObject {
                        put("kind", factor::class.simpleName)
                        put("key", factor.structuralKey().toString())
                    })
                })
                put("domains", JsonArray(problem.rootIntDomains().map { JsonPrimitive(it.toString()) }))
                put("sourceBooleanSlots", JsonArray((0 until parsed.problem.numBoolVars).map {
                    JsonPrimitive(coordinates?.bool(it) ?: -1)
                }))
                put("sourceIntegerSlots", JsonArray((0 until parsed.problem.numIntVars).map {
                    JsonPrimitive(coordinates?.int(it) ?: -1)
                }))
                put("sourceIntegerNames", buildJsonObject {
                    for ((name, variable) in parsed.intVarNames) put(name, variable)
                })
                put("correspondenceAdmission", "prepared coordinate map plus complete source round trip")
                put("sourceVerdictClaim", false)
            }.toString())
            if (coordinates == null) {
                out.resolve("decline-$rewrite.txt").writeText("unsupported-source-coordinate-projection\n")
                continue
            }
            val centerBytes = trajectoryThreadBytes()
            val centerStarted = System.nanoTime()
            val centerProjection = initial?.let { point ->
                if (point.numBoolVars != parsed.problem.numBoolVars || point.numIntVars != parsed.problem.numIntVars ||
                    !FiniteConflictSelection.checkSource(checkNotNull(sourceCheck), point, stop)
                ) {
                    null to "source-infeasible-or-check-incomplete"
                } else projectedSourceCenter(point, problem, coordinates, sweep, prepared.reconstruct)
            }
            val workingInitial = centerProjection?.first
            if ((selectors || exactControls) && workingInitial != null) {
                require(parsed.definedVars.isEmpty() && parsed.problem.numBoolVars == 0 &&
                    sameSource(checkNotNull(initial), workingInitial))
            }
            val centerNanos = System.nanoTime() - centerStarted
            if (initial != null) {
                out.resolve("center-$rewrite.json").writeText(buildJsonObject {
                    put("source", trajectorySource(initial))
                    put("working", workingInitial?.let(::trajectorySource) ?: JsonNull)
                    put("decline", centerProjection?.second)
                    put("mappingAndNativeCheckNanos", centerNanos)
                    put("mappingAndNativeCheckBytes", trajectoryThreadBytes() - centerBytes)
                    put("nativeSourceAccepted", workingInitial != null)
                    put("independentWitnessCheck", false)
                    put("learnedState", "fresh weights, tabu, configuration and worker RNG")
                }.toString())
                if (workingInitial == null) continue
            }
            val preparationNanos = System.nanoTime() - preparationStarted
            if (selectors && !selectorsFromWalk) SemanticRepairComparison.capture(problem,
                checkNotNull(workingObjective), requireNotNull(initial), sweep, sourceInputs, prepared,
                checkNotNull(sourceCheck), out, seed,
                evaluateRepairs = !ownerSelectors)
            if (ownerSelectors && !selectorsFromWalk) RepairOwnerReplay.replay(out.resolve("selectors.jsonl"),
                problem, checkNotNull(workingObjective),
                prepared, checkNotNull(sourceCheck), sourceInputs, out.resolve("owners-selectors.jsonl"), ownerSeed,
                options["ownerWork"]?.toLong() ?: 200_000L,
                options["ownerFragmentWork"]?.toLong() ?: 2_000L,
                structured = structured, descent = descent, ownerSequence = ownerSequence)
            var baseline: List<Any?>? = null
            var walkCenter: Sample? = null
            var walkNanos = 0L
            var walkBytes = 0L
            for (mode in if (repairCapture) listOf("initial", "walk", "repair") else listOf("initial", "walk")) {
                val walk = mode != "initial"
                val remainingNanos = timeoutMillis * 1_000_000 - preparationNanos
                if (walk && remainingNanos <= 0) {
                    out.resolve("censored-$mode-$rewrite.txt").writeText("preparation-time-budget\n")
                    continue
                }
                val walkStop = if (walk) Cancellation.after(remainingNanos.coerceAtLeast(1).nanoseconds) else stop
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
                            if (pick == 0L && initial != null) {
                                check(sameSource(initial, prepared.reconstruct(state.assignment.snapshot()))) {
                                    "initialization changed the checked source center"
                                }
                            }
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
                    val recipe = trajectoryRecipe(options.getValue("recipe")).withSources {
                        listOf(ConfiguredSource(recorder)) + it
                    }
                    if (workingInitial != null && recipe.seedImplicitOnRestart) {
                        out.resolve("decline-$label.txt").writeText("center-implicit-ownership-unsupported\n")
                        return@use
                    }
                    val solver = LocalSearchSolver(
                        problem, strategy = recipe.strategy, optimizeStrategy = recipe.optimizeStrategy,
                        greedyRepairOnRestart = walk && repairGreedy,
                        definitionalSweep = sweep, perMoveInvariants = sweep != null && recipe.perMoveInvariants,
                        seedImplicitOnRestart = recipe.seedImplicitOnRestart,
                    )
                    val trace = if (mode == "repair") RepairTrajectory.attach(solver,
                        "${options.getValue("recipe")}; linear shaping 1; seeded tie stream $seed",
                        traceCoordinates) else null
                    val params = LocalSearchParams(
                        randomSeed = seed, maxFlips = if (walk) moves else 1L, cancellation = walkStop,
                        costShaping = CostShaping.Linear(lambda = 1.0), lsObjective = gradient,
                        normalizeWeightsByClass = true, reportResiduals = true,
                        initialAssignment = workingInitial,
                    )
                    if (workingObjective == null) solver.resumableSolve(params).use { run ->
                        val result = try {
                            var observed = run.runSlice(walkStop, sliceMillis, sliceWork)
                            while (observed == null && !walkStop()) {
                                observed = run.runSlice(walkStop, sliceMillis, sliceWork)
                            }
                            observed
                        } catch (_: Captured) {
                            null
                        }
                        out.resolve("result-$label.txt").writeText(
                            "picks=$picks\ncancelled=${walkStop()}\nresult=$result\nstats=${run.stats}\n",
                        )
                    } else solver.resumable(workingObjective, params).use { run ->
                        val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
                        check(allocation.isThreadAllocatedMemorySupported)
                        allocation.isThreadAllocatedMemoryEnabled = true
                        val thread = Thread.currentThread().threadId()
                        val beforeBytes = allocation.getThreadAllocatedBytes(thread)
                        val started = System.nanoTime()
                        var lastIncumbent: MinimizeResult.WithSample? = null
                        val onIncumbent: (MinimizeResult.WithSample) -> Unit = {
                            lastIncumbent = it
                        }
                        val result = try {
                            var observed = run.runSlice(walkStop, sliceMillis, sliceWork, onIncumbent)
                            while (observed == null && !walkStop()) {
                                observed = run.runSlice(walkStop, sliceMillis, sliceWork, onIncumbent)
                            }
                            observed
                        } catch (_: Captured) {
                            null
                        }
                        val runNanos = System.nanoTime() - started
                        val runBytes = allocation.getThreadAllocatedBytes(thread) - beforeBytes
                        val runCancelled = walkStop()
                        if (mode == "walk") {
                            walkCenter = result?.assignment
                            walkNanos = runNanos
                            walkBytes = runBytes
                        }
                        val retainedResult = result ?: lastIncumbent
                        if (walk && retainedResult != null && sourceCheck != null) {
                            RepairTrajectory.writeResult(out.resolve("witness-$label.json"), retainedResult, prepared,
                                sourceCheck, picks, runNanos, runBytes)
                        }
                        out.resolve("summary-$label.json").writeText(buildJsonObject {
                            put("moves", run.stats.ls.moves.sum)
                            put("restarts", run.stats.search.restarts.sum)
                            put("picks", picks)
                            put("cancelled", runCancelled)
                            put("runElapsedNanos", runNanos)
                            put("runAllocatedBytes", runBytes)
                            put("preparationNanos", preparationNanos)
                            retainedResult?.assignment?.let { endpoint ->
                                val point = prepared.reconstruct(endpoint)
                                put("source", trajectorySource(point))
                                put("sourceInputChanges", initial?.let { start ->
                                    sourceInputs.count { start.intValue(it) != point.intValue(it) }
                                })
                                put("sourceBooleanChanges", initial?.let { start ->
                                    (0 until start.numBoolVars).count { start.boolValue(it) != point.boolValue(it) }
                                })
                                put("sourceObjective", objective?.evaluateExact(point).toString())
                                put("centerObjective", initial?.let { objective?.evaluateExact(it).toString() })
                            }
                        }.toString())
                        out.resolve("result-$label.txt").writeText(
                            "picks=$picks\ncancelled=$runCancelled\nresult=$result\nstats=${run.stats}\n",
                        )
                        if (walk && result != null && !runCancelled) {
                            val stats = run.stats.ls
                            val signature = listOf(result.assignment, result.objectiveValue, picks, stats.moves.sum,
                                run.stats.search.restarts.sum, stats.stalls.sum, stats.boardRestartChanges.sum,
                                stats.boardRestartDeclines.sum)
                            if (mode == "walk") baseline = signature
                            if (mode == "repair" && baseline != null) {
                                check(baseline == signature) { "repair observation changed the fixed-work walk" }
                                out.resolve("parity-$rewrite.txt").writeText(
                                    "witness/objective/picks/work/restarts/stalls/board counts match\n")
                            } else if (mode == "repair") {
                                out.resolve("parity-$rewrite.txt").writeText(
                                    "unavailable: unobserved walk censored or incomplete\n")
                            }
                        }
                    }
                    if (trace != null) {
                        RepairTrajectory.write(trace, prepared, sourceInputs, out.resolve("repairs-$rewrite.jsonl"))
                        if (exactControls) RepairTrajectory.controls(trace, problem, checkNotNull(workingObjective),
                            out.resolve("controls-$rewrite.jsonl"), seed, prepared, checkNotNull(sourceCheck))
                        if (ownerReplay) RepairOwnerReplay.replay(out.resolve("controls-$rewrite.jsonl"),
                            problem, checkNotNull(workingObjective), prepared, checkNotNull(sourceCheck), sourceInputs,
                            out.resolve("owners-$rewrite.jsonl"), ownerSeed,
                            options["ownerWork"]?.toLong() ?: 100_000L,
                            options["ownerFragmentWork"]?.toLong() ?: 2_000L,
                            structured = structured, descent = descent, ownerSequence = ownerSequence)
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
                            coordinates,
                            initial.shaping.objective, prepared.reconstruct,
                            { activeSweep?.sweep(shadow.assignment, shadow.rootDomains) },
                            initial.seeding.ownerInt,
                        )
                        if (workingInitial == null) traceGreedy(initial,
                            out.resolve("greedy-$rewrite.jsonl")) { position, slot ->
                            val boundary = if (position == problem.numBoolVars + problem.numIntVars) {
                                "greedy-complete-$rewrite"
                            } else "greedy-before-$rewrite-$position"
                            val focus = if (channel == null) slot else if (slot >= problem.numBoolVars) {
                                (0 until coordinates.intCount).firstOrNull {
                                    coordinates.int(it) == slot - problem.numBoolVars
                                }?.plus(parsed.problem.numBoolVars) ?: -1
                            } else -1
                            observations += Observation(
                                boundary, prepared.reconstruct(initial.assignment.snapshot()), focus,
                            )
                        }
                    }
                }
            }
            if (selectorsFromWalk) {
                val center = walkCenter
                val unavailable = when {
                    center == null -> "bounded walk returned no feasible incumbent"
                    plan.sourceBoolCount != problem.numBoolVars ||
                        (0 until plan.sourceBoolCount).any { plan.booleanCoordinate(it) != it } ||
                        plan.sourceIntCount != problem.numIntVars ||
                        (0 until plan.sourceIntCount).any { plan.integerCoordinate(it) != it } ||
                        prepared.reconstruct(center) != center -> "semantic capture requires an unchanged source frame"
                    problem.factors.none { it is AllDifferent || it is Cumulative || it is Diffn } ->
                        "no supported semantic relation"
                    else -> null
                }
                val witness = out.resolve("witness-false.json")
                out.resolve("selector-center.json").writeText(buildJsonObject {
                    put("origin", "bounded walk incumbent")
                    put("recipe", options.getValue("recipe"))
                    put("sourceSha256", sha256(bytes))
                    put("walkWitnessSha256", if (witness.exists()) sha256(witness.readBytes()) else null)
                    put("status", if (unavailable == null) "available" else "unavailable")
                    put("reason", unavailable)
                    put("center", center?.let(RepairTrajectory::sample) ?: JsonNull)
                    put("nativeSourceAccepted", center != null)
                    put("acquisitionElapsedNs", walkNanos)
                    put("acquisitionThreadBytes", walkBytes)
                    put("acquisitionIncludedInOwnerAllowance", false)
                    put("completedDescentEvidence", "repairs-false.jsonl contains separate native descent events")
                    put("independentWitnessCheck", false)
                    put("globalProofClaim", false)
                }.toString())
                if (unavailable == null) {
                    val checkedCenter = checkNotNull(center)
                    val sourceModel = checkNotNull(sourceCheck)
                    val repairObjective = checkNotNull(workingObjective)
                    SemanticRepairComparison.capture(problem, repairObjective, checkedCenter, sweep,
                        sourceInputs, prepared, sourceModel, out, seed,
                        evaluateRepairs = !ownerSelectors)
                    if (ownerSelectors) RepairOwnerReplay.replay(out.resolve("selectors.jsonl"), problem,
                        repairObjective, prepared, sourceModel, sourceInputs,
                        out.resolve("owners-selectors.jsonl"), ownerSeed,
                        options["ownerWork"]?.toLong() ?: 200_000L,
                        options["ownerFragmentWork"]?.toLong() ?: 2_000L,
                        structured = structured, descent = descent, ownerSequence = ownerSequence)
                    if (ownerConflicts) for (mode in listOf("source-root", "sampled-fragment")) {
                        val directory = out.resolve("conflict-$mode").apply { mkdirs() }
                        val selections = directory.resolve("selections.jsonl")
                        FiniteConflictSelection.compare(problem, repairObjective, checkedCenter, seed, sweep,
                            selections, acquisitionMode = mode) { sample, token ->
                            FiniteConflictSelection.checkSource(sourceModel,
                                prepared.reconstruct(sample), token)
                        }
                        RepairOwnerReplay.replay(selections, problem, repairObjective, prepared, sourceModel,
                            sourceInputs, directory.resolve("owners.jsonl"), ownerSeed,
                            options["ownerWork"]?.toLong() ?: 200_000L,
                            options["ownerFragmentWork"]?.toLong() ?: 2_000L,
                            structured = structured, descent = descent, ownerSequence = ownerSequence)
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
            val inputColumns = HashSet<Int>()
            if (channel != null) views[0].state.invariants?.let { network ->
                for (node in 0 until network.size) inputColumns.addAll(network.node(node).intInputs.asList())
            }
            val namedInputs = if (channel == null) emptyList() else parsed.intVarNames.values.filter {
                views[0].coordinates.int(it) in inputColumns
            }
            for ((label, source, focus) in observations) {
                val candidates = ArrayList<Move>()
                for (variable in 0 until (if (channel == null) source.numBoolVars else 0)) {
                    if (candidates.size == 32) break
                    val move = Move.BoolFlip(variable)
                    if (views.all { it.admits(move) }) candidates += move
                }
                val first = focus - parsed.problem.numBoolVars
                val integerOrder = (listOf(first).filter { it in 0 until source.numIntVars } + namedInputs +
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
                if (channel != null) for (variable in 0 until source.numBoolVars) {
                    if (candidates.size == 32) break
                    val move = Move.BoolFlip(variable)
                    if (views.all { it.admits(move) }) candidates += move
                }
                writer.appendLine(buildJsonObject {
                    put("observation", label)
                    put(
                        "candidatePolicy",
                        if (channel == null) {
                            "common admissible root endpoints and adjacent values; focus coordinate first"
                        } else {
                            "named source definition inputs first; common admissible endpoints and neighbors"
                        },
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

    // `cbls-extended/fixed` names the trajectory's plateau-plus-sampled-extended walk; `catalog:` selects the arm.
    // `cbls-implicit-unused/fixed` is the implicit catalog arm plus the opt-in unused-value owner moves.
    private fun trajectoryRecipe(name: String): LocalSearchRecipe = when (name) {
        "source-coordinate-descent" -> LocalSearchRecipe(name,
            SourceDrivenStrategy(sources = listOf(ConfiguredSource(ObjectiveSeed())),
                acceptance = AcceptanceRule.GreedyDescent, feasibleDescent = FeasibleDescent.SelfOwned))
        "cbls-extended/fixed" -> LocalSearchCatalog.byLabel("cbls-plateau/fixed").withSources {
            it + ConfiguredSource(SatisfiedStructured.sampledExtended(8))
        }
        "cbls-implicit-unused/fixed" -> LocalSearchCatalog.byLabel("cbls-implicit/fixed").withSources {
            it + MoveSourceCatalog.configured("unused-values")
        }
        else -> LocalSearchCatalog.byLabel(name.removePrefix("catalog:"))
    }

    internal fun getter(owner: Any, name: String): Any? = owner.javaClass.methods.single {
        it.name.substringBefore('$') == name && it.parameterCount == 0
    }.invoke(owner)

    internal fun trajectoryCoordinates(
        invocation: PreparedSourceInvocation,
        prepared: FinitePipelinePreparation,
        stop: Cancellation,
    ): VarRemap? {
        val mapping = getter(prepared, "getSourceMapping") ?: return null
        val plan = getter(invocation, "getEnginePlan") ?: return null
        val sourceMethods = Class.forName("com.eignex.klause.solver.pipeline.SourceHintMappingKt").methods
        val planned = sourceMethods.single { it.name == "sourceCoordinates" }.invoke(null, plan, stop)
            as VarRemap? ?: return null
        val projected = Class.forName("com.eignex.klause.presolve.SourceMappingKt").methods.single {
            it.name == "retainedCoordinates"
        }.invoke(null, mapping, stop) as VarRemap? ?: return null
        return sourceMethods.single { it.name == "followedBy" }.invoke(null, planned, projected, stop) as VarRemap?
    }

    internal fun heuristicHints(
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
