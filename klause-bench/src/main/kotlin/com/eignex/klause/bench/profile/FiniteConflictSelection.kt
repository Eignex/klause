package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.CostShaping
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.pipeline.FinitePipelinePreparation
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object FiniteConflictSelection {
    private const val DECISIONS = 2000L
    private const val ACQUISITION_DECISIONS = 128L
    private const val REPAIR_DECISIONS = 1000L
    private const val DESCENT_MOVES = 128L
    private const val DESCENT_RULE = "first source-coordinate greedy descent endpoint; seeded ties; no greedy init"

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        if (args.size == 1 && args[0].startsWith("batch64=")) {
            val requests = Json.decodeFromString<List<List<String>>>(
                Base64.getDecoder().decode(args[0].substringAfter('=')).decodeToString())
            require(requests.size in 1..32)
            for ((index, request) in requests.withIndex()) {
                capture(request.toTypedArray(), out.resolve("$index").apply { mkdirs() })
            }
        } else capture(args, out)
    }

    private fun capture(args: Array<String>, out: File) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "sha256", "center", "seed"))
        val reference = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use {
            it.readNBytes(64 * 1024 * 1024 + 1)
        }
        require(bytes.size <= 64 * 1024 * 1024 && sha256(bytes) == options.getValue("sha256"))
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("source.xml").writeBytes(bytes)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        val setupStop = Cancellation.after(30.seconds)
        val parsed = Xcsp3.parse(bytes.decodeToString(), recoveryCancellation = setupStop)
        require(parsed.problem.numBoolVars == 0 && parsed.definedVars.isEmpty())
        require(parsed.problem.numIntVars <= 1024 && parsed.problem.numFactors <= 4096)
        require(parsed.intVarNames.values.toSet() == (0 until parsed.problem.numIntVars).toSet())
        val problem = parsed.problem.bake(setupStop)
        check(!setupStop())
        val objective = requireNotNull(parsed.objective).toLinearObjective().let {
            if (parsed.sense == ObjectiveSense.MAXIMIZE) it.negated() else it
        }
        val center = Sample(booleanArrayOf(), options.getValue("center").split(',').map(String::toLong).toLongArray())
        require(center.numIntVars == problem.numIntVars)
        out.resolve("model.json").writeText(buildJsonObject {
            put("sourceSha256", sha256(bytes))
            put("sourceCoordinateMapping", "identity")
            put("nativeChecker", "finiteWitnessVerifier with original lowered source and no assumptions")
            put("independentChecker", "none")
            put("semantics", problem.semantics.toString())
            put("settings", problem.settings.toString())
            put("sourceSense", parsed.sense.name)
            put("minimizingObjective", objective.toString())
            put("rootDomains", JsonArray(problem.rootIntDomains().map { JsonPrimitive(it.toString()) }))
            put("namedInputs", buildJsonObject {
                for ((name, coordinate) in parsed.intVarNames) put(name, coordinate)
            })
        }.toString())
        compare(problem, objective, center, options.getValue("seed").toLong(), null,
            out.resolve("selections.jsonl")) { sample, stop -> checkSource(problem, sample, stop) }
    }

    fun compare(
        problem: BakedProblem,
        objective: LinearObjective,
        center: Sample,
        seed: Long,
        sweep: DefinitionalSweep?,
        file: File,
        checkSource: (Sample, Cancellation) -> Boolean,
    ) {
        require(problem.numRealVars == 0 && problem.numBoolVars.toLong() + problem.numIntVars <= 1024)
        require(checkSource(center, Cancellation.after(5.seconds)))
        val destroy = Class.forName("com.eignex.klause.meta.alns.DestroyOperator")
        val companion = destroy.getField("Companion").get(null)
        val destroyMethod = destroy.methods.single { it.name == "destroy" }
        val guidanceType = Class.forName("com.eignex.klause.meta.alns.ConflictSupportedRemoval")
        val freedType = Class.forName("com.eignex.klause.meta.alns.FreedVars")
        val scope = Class.forName("com.eignex.klause.meta.alns.RepairScope").getField("INSTANCE").get(null)
        val rootFixed = problem.baked as? PropagationResult.Implied
        val eligible = (0 until problem.numBoolVars + problem.numIntVars).filter { coordinate ->
            if (coordinate < problem.numBoolVars) rootFixed?.boolValueOrNull(coordinate) == null else
                !problem.rootIntDomain(coordinate - problem.numBoolVars).isFixed
        }
        val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(allocation.isThreadAllocatedMemorySupported)
        allocation.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        file.bufferedWriter().use { writer ->
            for (requestedCount in listOf(2, 4, 8).filter { it <= eligible.size }) {
                for (arm in listOf("random", "objective", "adjacency", "conflict", "acquisition-ablated")) {
                    val stop = Cancellation.after(5.seconds)
                    val allowance = NodeBudget(DECISIONS)
                    val started = System.nanoTime()
                    val startedBytes = allocation.getThreadAllocatedBytes(thread)
                    val guidance = guidanceType.getDeclaredConstructor(BakedProblem::class.java,
                        LinearObjective::class.java, Assumptions::class.java, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType).newInstance(problem, objective, Assumptions.None, 4096, 32)
                    try {
                        val base = BacktrackParams(randomSeed = seed, cancellation = stop,
                            objectiveBoundSupplier = { objective.evaluate(center) }, maxDecisions = DECISIONS,
                            nodeBudget = allowance)
                        val observe = guidanceType.methods.single { it.name == "observe" }
                        val acquisitionStarted = System.nanoTime()
                        var acquisitionNodes = 0L
                        var acquisitionWork = 0L
                        var acquisitionSample: Sample? = null
                        var acquisitionSourceAccepted: Boolean? = null
                        var acquisitionResult: MinimizeResult? = null
                        var acquisitionImproved = false
                        if (arm == "conflict" || arm == "acquisition-ablated") {
                            guidanceType.methods.single { it.name == "begin" }
                                .invoke(guidance, center, Assumptions.None, objective.evaluate(center), stop)
                            val acquiring = (observe.invoke(guidance, base) as BacktrackParams)
                                .copy(maxDecisions = ACQUISITION_DECISIONS)
                            val result = BacktrackSolver(problem).minimize(objective, acquiring)
                            acquisitionResult = result
                            guidanceType.methods.single { it.name == "end" }.invoke(guidance)
                            acquisitionNodes = result.stats.search.nodes.sum.toLong()
                            acquisitionWork = allowance.spent
                            acquisitionSample = result.assignment
                            acquisitionImproved = acquisitionSample?.let {
                                acquisitionSourceAccepted = checkSource(it, stop)
                                check(acquisitionSourceAccepted == true || stop())
                                acquisitionSourceAccepted == true &&
                                    objective.evaluateExact(it) < objective.evaluateExact(center)
                            } ?: false
                        }
                        val acquisitionNanos = System.nanoTime() - acquisitionStarted
                        val operator = when (arm) {
                            "conflict" -> guidance
                            "objective" -> companion.javaClass.methods.single { it.name == "getWorstObjective" }
                                .invoke(companion)
                            "adjacency" -> companion.javaClass.methods.single { it.name == "getAdjacencyRelated" }
                                .invoke(companion)
                            else -> companion.javaClass.methods.single { it.name == "getRandom" }.invoke(companion)
                        }
                        val selectionStarted = System.nanoTime()
                        var selectionAttempts = 1
                        val total = problem.numBoolVars + problem.numIntVars
                        var raw = checkNotNull(destroyMethod.invoke(operator, Random(seed), problem, center, objective,
                            (requestedCount + 0.25) / total))
                        fun coordinates(value: Any): List<Int> =
                            (get(value, "Bools") as IntArray).toList() +
                                (get(value, "Ints") as IntArray).map { problem.numBoolVars + it }
                        var rawCount = requestedCount
                        while (coordinates(raw).count { it in eligible } < requestedCount && rawCount < total &&
                            !stop()) {
                            rawCount++
                            selectionAttempts++
                            raw = checkNotNull(destroyMethod.invoke(operator, Random(seed), problem, center,
                                objective, (rawCount + 0.25) / total))
                        }
                        val selected = coordinates(raw).filter { it in eligible }
                        val requestedBools = selected.filter { it < problem.numBoolVars }.toIntArray()
                        val requestedInts = selected.filter { it >= problem.numBoolVars }
                            .map { it - problem.numBoolVars }.toIntArray()
                        val requested = freedType.getDeclaredConstructor(IntArray::class.java, IntArray::class.java)
                            .newInstance(requestedBools, requestedInts)
                        val effective = scope.javaClass.methods.single { it.name == "effective" }
                            .invoke(scope, requested, Assumptions.None, sweep, stop)
                        val bools = effective?.let { get(it, "Bools") as IntArray } ?: intArrayOf()
                        val ints = effective?.let { get(it, "Ints") as IntArray } ?: intArrayOf()
                        val matched = effective != null && bools.size + ints.size == requestedCount
                        val pins = effective?.let { scopeVars ->
                            scope.javaClass.methods.single { it.name == "pins" }
                                .invoke(scope, problem, center, scopeVars)
                                as Assumptions
                        }
                        val selectionNanos = System.nanoTime() - selectionStarted
                        val remaining = minOf(REPAIR_DECISIONS, (DECISIONS - allowance.spent).coerceAtLeast(0L))
                        val result = if (matched && remaining > 0L && !stop()) {
                            BacktrackSolver(problem).minimize(objective, base.copy(
                                assumptions = checkNotNull(pins), maxDecisions = remaining))
                        } else null
                        val repairSourceAccepted = result?.assignment?.let { checkSource(it, stop) }
                        check(repairSourceAccepted != false || stop())
                        val improved = repairSourceAccepted == true && result?.assignment?.let {
                            objective.evaluateExact(it) < objective.evaluateExact(center)
                        } == true
                        val exhausted = result != null && reason(result) == TerminationReason.SearchExhausted
                        val descent = if (matched && !stop() && !allowance.exhausted()) {
                            descend(problem, objective, result?.assignment ?: center, seed, sweep, stop, allowance,
                                file.resolveSibling("descent-$arm-k$requestedCount.jsonl"))
                        } else null
                        val descentAccepted = descent?.let { checkSource(it.sample, stop) }
                        check(descentAccepted != false || stop())
                        val outcome = when {
                            !matched -> "effective-count-mismatch"
                            improved -> "checked-improvement"
                            exhausted -> "no-improving-completion-under-pins-and-cutoff"
                            stop() -> "cancelled"
                            result == null -> "allowance-spent-before-repair"
                            else -> "unfinished"
                        }
                        val weights = guidanceType.methods.single { it.name == "weights" }
                            .invoke(guidance) as IntArray
                        writer.appendLine(buildJsonObject {
                            put("selector", arm)
                            put("repairOwner", "finite-cp-cold")
                            put("seed", seed)
                            put("sourceCoordinateMapping", "identity")
                            put("center", sample(center))
                            put("centerObjective", objective.evaluateExact(center).toString())
                            put("requestedCount", requestedCount)
                            put("effectiveCountMatched", matched)
                            put("requestedBools", JsonArray(requestedBools.map(::JsonPrimitive)))
                            put("requestedInts", JsonArray(requestedInts.map(::JsonPrimitive)))
                            put("effectiveBools", JsonArray(bools.map(::JsonPrimitive)))
                            put("effectiveInts", JsonArray(ints.map(::JsonPrimitive)))
                            put("guidanceStatus", get(guidance, "Status").toString())
                            put("guidanceConsumed", arm == "conflict" &&
                                get(guidance, "Status").toString() == "Supported")
                            put("capturedConflicts", get(guidance, "Conflicts") as Int)
                            put("captureCapped", get(guidance, "Capped") as Boolean)
                            put("captureAndSelectionVisits", get(guidance, "Visits") as Long)
                            put("supportWeightsBoolThenInt", JsonArray(weights.map(::JsonPrimitive)))
                            put("originScope", "same model, no assumptions, strict objective cutoff per attribution")
                            put("branchPremisesAvailable", false)
                            put("logicalConflictClaim", false)
                            put("initialQueryCutoff", get(guidance, "OriginCutoff") as Double?)
                            val attributions = guidanceType.methods.single { it.name == "attributions" }
                                .invoke(guidance) as List<*>
                            put("scopedAttributions", JsonArray(attributions.map { entry ->
                                val attribution = checkNotNull(entry)
                                buildJsonObject {
                                    put("bools", JsonArray(
                                        (get(attribution, "Bools") as IntArray).map(::JsonPrimitive)))
                                    put("ints", JsonArray(
                                        (get(attribution, "Ints") as IntArray).map(::JsonPrimitive)))
                                    put("cutoff", get(attribution, "Cutoff") as Double)
                                    (get(attribution, "LocalIncumbent") as Sample?)?.let {
                                        put("localIncumbentObjective", objective.evaluateExact(it).toString())
                                    }
                                }
                            }))
                            put("acquisitionNodes", acquisitionNodes)
                            put("acquisitionWork", acquisitionWork)
                            put("acquisitionImproved", acquisitionImproved)
                            put("acquisitionResult", acquisitionResult?.let { it::class.simpleName })
                            put("acquisitionReason", reason(acquisitionResult)?.name)
                            acquisitionSample?.let {
                                put("acquired", sample(it))
                                put("acquiredObjective", objective.evaluateExact(it).toString())
                                put("acquiredNativeSourceAccepted", acquisitionSourceAccepted)
                            }
                            put("acquisitionNanos", acquisitionNanos)
                            put("selectionNanos", selectionNanos)
                            put("selectionAttempts", selectionAttempts)
                            put("remainingRepairDecisions", remaining)
                            put("repairNodes", result?.stats?.search?.nodes?.sum ?: 0.0)
                            put("containingWork", DECISIONS)
                            put("maxRepairDecisions", REPAIR_DECISIONS)
                            put("maxDescentMoves", DESCENT_MOVES)
                            put("containingSeconds", 5)
                            put("totalWork", allowance.spent)
                            put("elapsedNanos", System.nanoTime() - started)
                            put("allocatedBytes", allocation.getThreadAllocatedBytes(thread) - startedBytes)
                            put("cancelled", stop())
                            put("repairResult", result?.let { it::class.simpleName })
                            put("repairReason", reason(result)?.name)
                            put("repairOutcome", outcome)
                            put("completedLocalExhaustion", exhausted)
                            put("strictImprovement", improved)
                            result?.assignment?.let {
                                put("repaired", sample(it))
                                put("repairedObjective", objective.evaluateExact(it).toString())
                                put("nativeSourceAccepted", repairSourceAccepted)
                            }
                            put("globalProofClaim", false)
                            put("declaredDescentRule", DESCENT_RULE)
                            put("descentEndpoint", descent?.endpoint)
                            put("descentEndpointClassified", descent?.endpoint in
                                setOf("PolicyLocalOptimum", "FeasibleStall"))
                            put("basinClassified", false)
                            descent?.let {
                                put("afterDescent", sample(it.sample))
                                put("afterDescentObjective", objective.evaluateExact(it.sample).toString())
                                put("descentNativeSourceAccepted", descentAccepted)
                                put("descentMoves", it.moves)
                                put("repairToDescentInputChanges", (result?.assignment ?: center)
                                    .hammingDistanceTo(it.sample))
                                put("centerToDescentInputChanges", center.hammingDistanceTo(it.sample))
                            }
                        }.toString())
                        writer.flush()
                    } finally {
                        (guidance as AutoCloseable).close()
                    }
                }
            }
        }
    }

    private fun get(owner: Any, property: String): Any? = owner.javaClass.methods.single {
        it.name.substringBefore('$') == "get$property" && it.parameterCount == 0
    }.invoke(owner)

    private fun sample(value: Sample) = buildJsonObject {
        put("bools", JsonArray(value.bools.map(::JsonPrimitive)))
        put("ints", JsonArray(value.ints.map(::JsonPrimitive)))
    }

    private fun reason(result: MinimizeResult?): TerminationReason? = when (result) {
        is MinimizeResult.BestFound -> result.reason
        is MinimizeResult.Unknown -> result.reason
        is MinimizeResult.Optimal, is MinimizeResult.Infeasible -> TerminationReason.SearchExhausted
        else -> null
    }

    private class Descent(val sample: Sample, val endpoint: String, val moves: Double)

    private fun descend(
        problem: BakedProblem,
        objective: LinearObjective,
        initial: Sample,
        seed: Long,
        sweep: DefinitionalSweep?,
        stop: Cancellation,
        allowance: NodeBudget,
        file: File,
    ): Descent {
        val strategy = SourceDrivenStrategy(sources = listOf(ConfiguredSource(ObjectiveSeed())),
            acceptance = AcceptanceRule.GreedyDescent, feasibleDescent = FeasibleDescent.SelfOwned)
        val solver = LocalSearchSolver(problem, strategy = strategy, greedyRepairOnRestart = false,
            definitionalSweep = sweep, seedImplicitOnRestart = false)
        val trace = RepairTrajectory.attach(solver, DESCENT_RULE)
        fun endpoint(): Pair<String, Sample>? {
            for (entry in get(trace, "Attempts") as List<*>) {
                val attempt = checkNotNull(entry)
                val kind = get(attempt, "Endpoint").toString()
                if (kind !in setOf("PolicyLocalOptimum", "FeasibleStall")) continue
                val frame = (get(attempt, "Frames") as List<*>).lastOrNull() ?: continue
                return kind to (get(checkNotNull(frame), "Sample") as Sample)
            }
            return null
        }
        val token = stop or Cancellation { endpoint() != null }
        val params = LocalSearchParams(randomSeed = seed, initialAssignment = initial,
            maxFlips = DESCENT_MOVES, maxInstructions = DESCENT_MOVES, nodeBudget = allowance,
            cancellation = token, costShaping = CostShaping.Linear(lambda = 1.0))
        var best = initial
        var moves = 0.0
        solver.resumable(objective, params).use { run ->
            run.runSlice(token, Long.MAX_VALUE) { best = it.sample }?.assignment?.let { best = it }
            moves = run.stats.ls.moves.sum
        }
        RepairTrajectory.write(trace, FinitePipelinePreparation(problem, objective, { it }, null),
            IntArray(problem.numIntVars) { it }, file)
        val completed = endpoint()
        return Descent(completed?.second ?: best, completed?.first ?: "Censored", moves)
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkSource(problem: BakedProblem, sample: Sample, stop: Cancellation): Boolean {
        val verifier = Class.forName("com.eignex.klause.solver.validation.FiniteWitnessVerificationKt")
            .methods.single { it.name == "finiteWitnessVerifier" && it.parameterCount == 5 }
            .invoke(null, problem, null, Assumptions.None, stop, null) as CandidateVerifier<Sample, Double?>
        return verifier.verify(Candidate(sample, null)) is Verification.Accepted
    }
}
