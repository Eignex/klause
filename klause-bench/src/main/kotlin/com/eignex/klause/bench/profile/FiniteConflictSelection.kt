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
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.baked
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.management.ManagementFactory
import com.sun.management.ThreadMXBean
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object FiniteConflictSelection {
    private const val DECISIONS = 2000L
    private const val ACQUISITION_DECISIONS = 128L

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "sha256", "center", "seed"))
        val reference = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use {
            it.readNBytes(64 * 1024 * 1024 + 1)
        }
        require(bytes.size <= 64 * 1024 * 1024 && sha256(bytes) == options.getValue("sha256"))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
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
        compare(problem, objective, center, options.getValue("seed").toLong(), null,
            out.resolve("selections.jsonl")) { checkSource(problem, it) }
    }

    fun compare(
        problem: BakedProblem,
        objective: LinearObjective,
        center: Sample,
        seed: Long,
        sweep: DefinitionalSweep?,
        file: File,
        checkSource: (Sample) -> Boolean,
    ) {
        require(problem.numRealVars == 0 && problem.numBoolVars.toLong() + problem.numIntVars <= 1024)
        require(checkSource(center))
        val destroy = Class.forName("com.eignex.klause.meta.alns.DestroyOperator")
        val companion = destroy.getField("Companion").get(null)
        val destroyMethod = destroy.methods.single { it.name == "destroy" }
        val guidanceType = Class.forName("com.eignex.klause.meta.alns.ConflictSupportedRemoval")
        val freedType = Class.forName("com.eignex.klause.meta.alns.FreedVars")
        val scope = Class.forName("com.eignex.klause.meta.alns.RepairScope").getField("INSTANCE").get(null)
        val rootFixed = problem.baked as? com.eignex.klause.propagation.PropagationResult.Implied
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
                        var acquisitionImproved = false
                        if (arm == "conflict" || arm == "acquisition-ablated") {
                            guidanceType.methods.single { it.name == "begin" }
                                .invoke(guidance, center, Assumptions.None, objective.evaluate(center), stop)
                            val acquiring = (observe.invoke(guidance, base) as BacktrackParams)
                                .copy(maxDecisions = ACQUISITION_DECISIONS)
                            val result = BacktrackSolver(problem).minimize(objective, acquiring)
                            guidanceType.methods.single { it.name == "end" }.invoke(guidance)
                            acquisitionNodes = result.stats.search.nodes.sum.toLong()
                            acquisitionWork = allowance.spent
                            acquisitionSample = result.assignment
                            acquisitionImproved = acquisitionSample?.let {
                                check(checkSource(it))
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
                        val pins = effective?.let {
                            scope.javaClass.methods.single { it.name == "pins" }.invoke(scope, problem, center, it)
                                as Assumptions
                        }
                        val selectionNanos = System.nanoTime() - selectionStarted
                        val remaining = (DECISIONS - allowance.spent).coerceAtLeast(0L)
                        val result = if (matched && remaining > 0L && !stop()) {
                            BacktrackSolver(problem).minimize(objective, base.copy(
                                assumptions = checkNotNull(pins), maxDecisions = remaining))
                        } else null
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
                            put("capturedConflicts", get(guidance, "Conflicts") as Int)
                            put("captureCapped", get(guidance, "Capped") as Boolean)
                            put("captureAndSelectionVisits", get(guidance, "Visits") as Long)
                            put("supportWeightsBoolThenInt", JsonArray((guidanceType.methods.single {
                                it.name == "weights"
                            }.invoke(guidance) as IntArray).map(::JsonPrimitive)))
                            put("originScope", "same model, no assumptions, strict center objective cutoff")
                            put("acquisitionNodes", acquisitionNodes)
                            put("acquisitionWork", acquisitionWork)
                            put("acquisitionImproved", acquisitionImproved)
                            acquisitionSample?.let {
                                put("acquired", sample(it))
                                put("acquiredObjective", objective.evaluateExact(it).toString())
                                put("acquiredNativeSourceAccepted", true)
                            }
                            put("acquisitionNanos", acquisitionNanos)
                            put("selectionNanos", selectionNanos)
                            put("selectionAttempts", selectionAttempts)
                            put("remainingRepairDecisions", remaining)
                            put("repairNodes", result?.stats?.search?.nodes?.sum ?: 0.0)
                            put("containingDecisions", DECISIONS)
                            put("containingSeconds", 5)
                            put("totalWork", allowance.spent)
                            put("elapsedNanos", System.nanoTime() - started)
                            put("allocatedBytes", allocation.getThreadAllocatedBytes(thread) - startedBytes)
                            put("cancelled", stop())
                            put("repairResult", result?.let { it::class.simpleName })
                            put("strictImprovement", result?.assignment?.let {
                                objective.evaluateExact(it) < objective.evaluateExact(center)
                            } ?: false)
                            result?.assignment?.let {
                                put("repaired", sample(it))
                                put("repairedObjective", objective.evaluateExact(it).toString())
                                put("nativeSourceAccepted", checkSource(it))
                            }
                            put("globalProofClaim", false)
                            put("descentEndpointClassified", false)
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

    @Suppress("UNCHECKED_CAST")
    private fun checkSource(problem: BakedProblem, sample: Sample): Boolean {
        val verifier = Class.forName("com.eignex.klause.solver.validation.FiniteWitnessVerificationKt")
            .methods.single { it.name == "finiteWitnessVerifier" && it.parameterCount == 5 }
            .invoke(null, problem, null, Assumptions.None, Cancellation.Never, null) as CandidateVerifier<Sample, Double?>
        return verifier.verify(Candidate(sample, null)) is Verification.Accepted
    }
}
