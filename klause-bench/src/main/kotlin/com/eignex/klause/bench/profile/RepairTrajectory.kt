package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FinitePipelinePreparation
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

internal object RepairTrajectory {
    fun attach(solver: LocalSearchSolver, rule: String): Any {
        val type = Class.forName("com.eignex.klause.localsearch.RepairTrace")
        val trace = type.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).newInstance(rule, 32, 4096)
        val engine = checkNotNull(get(solver, "Engine"))
        engine.javaClass.methods.single { it.name.substringBefore('$') == "setRepairTrace" }.invoke(engine, trace)
        return trace
    }

    fun write(trace: Any, prepared: FinitePipelinePreparation, inputs: IntArray, file: File) {
        file.bufferedWriter().use { writer ->
            for (attempt in get(trace, "Attempts") as List<*>) {
                val entry = checkNotNull(attempt)
                val frames = get(entry, "Frames") as List<*>
                val before = get(checkNotNull(frames.first()), "Sample") as Sample
                val sourceBefore = prepared.reconstruct(before)
                val center = (get(entry, "Center") as Sample?)?.let(prepared.reconstruct)
                writer.appendLine(buildJsonObject {
                    put("id", get(entry, "Id") as Int)
                    put("owner", get(entry, "Owner").toString())
                    put("freedScopeAvailable", get(entry, "ScopeComplete") as Boolean)
                    put("seed", get(entry, "Seed") as Long?)
                    put("descentRule", get(trace, "DescentRule").toString())
                    put("endpoint", get(entry, "Endpoint").toString())
                    put("repairVerdict", get(entry, "RepairVerdict") as String?)
                    put("repairReason", get(entry, "RepairReason")?.toString())
                    put("bestObjectiveCutoff", get(entry, "BestObjectiveCutoff") as Double?)
                    put("exactBestObjective", get(entry, "ExactBestObjective")?.toString())
                    (get(entry, "BestSample") as Sample?)?.let {
                        put("sourceBest", sample(prepared.reconstruct(it)))
                    }
                    put("basinClassified", false)
                    for (scope in listOf("RequestedBools", "RequestedInts", "EffectiveBools", "EffectiveInts")) {
                        put(scope.replaceFirstChar { it.lowercase() },
                            JsonArray((get(entry, scope) as IntArray).map(::JsonPrimitive)))
                    }
                    if (center != null) put("sourceCenter", sample(center))
                    put("frames", JsonArray(frames.map { frame ->
                        val captured = checkNotNull(frame)
                        val working = get(captured, "Sample") as Sample
                        val source = prepared.reconstruct(working)
                        buildJsonObject {
                            put("stage", get(captured, "Stage").toString())
                            put("cost", get(captured, "Cost") as Long?)
                            put("working", sample(working))
                            put("source", sample(source))
                            put("fromBefore", distance(sourceBefore, source, inputs))
                            if (center != null) put("fromCenter", distance(center, source, inputs))
                            put("workingDiscreteChanges", before.hammingDistanceTo(working))
                        }
                    }))
                }.toString())
                writer.flush()
            }
        }
        file.resolveSibling("${file.nameWithoutExtension}-cost.json").writeText(buildJsonObject {
            put("coordinateReads", get(trace, "CoordinateReads") as Long)
            put("declinedAttempts", get(trace, "Declined") as Long)
            put("maxAttempts", get(trace, "MaxAttempts") as Int)
            put("maxCoordinates", get(trace, "MaxCoordinates") as Int)
            put("sourceVerdictClaim", false)
        }.toString())
    }

    fun controls(trace: Any, problem: BakedProblem, objective: LinearObjective, file: File, seed: Long,
        prepared: FinitePipelinePreparation, source: BakedProblem,
    ) {
        val scope = Class.forName("com.eignex.klause.meta.alns.RepairScope").getField("INSTANCE").get(null)
        val freedType = Class.forName("com.eignex.klause.meta.alns.FreedVars")
        val pins = scope.javaClass.methods.single { it.name == "pins" }
        val stop = Cancellation.after(10.seconds)
        val random = Random(seed)
        var centers = 0
        var planned = 0
        var attempted = 0
        var completedControls = 0
        file.bufferedWriter().use { writer ->
            for (attempt in get(trace, "Attempts") as List<*>) {
                val entry = checkNotNull(attempt)
                if (get(entry, "Endpoint").toString() !in setOf("PolicyLocalOptimum", "FeasibleStall")) continue
                val frame = (get(entry, "Frames") as List<*>).lastOrNull() ?: continue
                val center = get(frame, "Sample") as Sample
                if (get(frame, "Cost") != 0L) continue
                if (centers >= 4 || stop()) break
                centers++
                checkSource(source, prepared.reconstruct(center))
                val blocks = problem.factors.asSequence().mapNotNull { factor ->
                    val label = when (factor) {
                        is AllDifferent -> if (factor.presents.isEmpty() && factor.exceptSet.isEmpty())
                            "assignment-block" else return@mapNotNull null
                        is Cumulative -> if (factor.presents.isEmpty()) "resource-block" else return@mapNotNull null
                        is Diffn -> "placement-block"
                        else -> return@mapNotNull null
                    }
                    val variables = factor.intVars.distinct()
                        .filterNot { problem.rootIntDomain(it).isFixed }.toIntArray()
                    if (variables.size !in 2..6) null else label to variables
                }.take(4).toList()
                val candidates = mutableListOf<Pair<String, IntArray>>()
                for ((label, variables) in blocks) {
                    for (subset in properSubsets(variables)) {
                        candidates.add((if (subset.size == 1) "individual" else "proper-subset") to subset)
                    }
                    candidates.add(label to variables)
                    val eligible = (0 until problem.numIntVars).filter { !problem.rootIntDomain(it).isFixed }
                    repeat(4) {
                        if (eligible.size >= variables.size) candidates.add("size-matched-sample" to
                            eligible.shuffled(random).take(variables.size).toIntArray())
                    }
                }
                planned += candidates.size
                for ((candidateIndex, candidate) in candidates.withIndex()) {
                    if (stop()) break
                    val (label, variables) = candidate
                    attempted++
                    val freed = freedType.getDeclaredConstructor(IntArray::class.java, IntArray::class.java)
                        .newInstance(intArrayOf(), variables)
                    val assumptions = pins.invoke(scope, problem, center, freed) as Assumptions
                    val started = System.nanoTime()
                    val result = BacktrackSolver(problem).minimize(objective, BacktrackParams(
                        assumptions = assumptions, maxDecisions = 10_000, cancellation = stop, randomSeed = seed))
                    val elapsed = System.nanoTime() - started
                    val completed = result is MinimizeResult.Optimal || result is MinimizeResult.Infeasible
                    if (completed) completedControls++
                    writer.appendLine(buildJsonObject {
                        put("centerId", get(entry, "Id") as Int)
                        put("candidateIndex", candidateIndex)
                        put("candidateCount", candidates.size)
                        put("selector", label)
                        put("requestedInts", JsonArray(variables.map(::JsonPrimitive)))
                        put("effectiveInts", JsonArray(variables.map(::JsonPrimitive)))
                        put("center", sample(center))
                        put("centerObjective", objective.evaluate(center))
                        put("result", result::class.simpleName)
                        put("reason", when (result) {
                            is MinimizeResult.Unknown -> result.reason.toString()
                            is MinimizeResult.BestFound -> result.reason.toString()
                            else -> null
                        })
                        put("completedLocalExhaustion", completed)
                        put("strictImprovement", result.assignment?.let {
                            objective.evaluateExact(it) < objective.evaluateExact(center)
                        } ?: false)
                        put("nodes", result.stats.search.nodes.sum)
                        put("elapsedNanos", elapsed)
                        result.assignment?.let {
                            put("repaired", sample(it))
                            put("nativeSourceAccepted", checkSource(source, prepared.reconstruct(it)))
                        }
                        put("globalProofClaim", false)
                    }.toString())
                    writer.flush()
                }
            }
        }
        file.resolveSibling("${file.nameWithoutExtension}-limits.json").writeText(buildJsonObject {
            put("maxCenters", 4)
            put("maxBlocksPerCenter", 4)
            put("maxRelationBlockCoordinates", 6)
            put("optionalRelationBlocks", "declined")
            put("maxDecisionsPerRepair", 10_000)
            put("cancelled", stop())
            put("centersVisited", centers)
            put("plannedControls", planned)
            put("attemptedControls", attempted)
            put("completedControls", completedControls)
            put("boundedScanCompleted", !stop() && attempted == planned)
            put("wholeSourceExhaustionClaim", false)
        }.toString())
    }

    internal fun properSubsets(variables: IntArray): List<IntArray> {
        require(variables.size in 2..6)
        return (1 until (1 shl variables.size) - 1).map { mask ->
            variables.indices.filter { mask and (1 shl it) != 0 }.map { variables[it] }.toIntArray()
        }
    }

    fun writeResult(file: File, result: MinimizeResult, prepared: FinitePipelinePreparation,
        source: BakedProblem, picks: Long, runNanos: Long, allocatedBytes: Long,
    ) {
        file.writeText(buildJsonObject {
            put("objective", result.objectiveValue)
            put("picks", picks)
            put("moves", result.stats.ls.moves.sum)
            put("restarts", result.stats.search.restarts.sum)
            put("stalls", result.stats.ls.stalls.sum)
            put("runElapsedNanos", runNanos)
            put("runThreadAllocatedBytes", allocatedBytes)
            put("checker", "native finiteWitnessVerifier/composedFixpoint")
            put("independentWitnessCheck", false)
            result.assignment?.let {
                val reconstructed = prepared.reconstruct(it)
                put("working", sample(it))
                put("source", sample(reconstructed))
                put("nativeSourceAccepted", checkSource(source, reconstructed))
            }
        }.toString())
    }

    @Suppress("UNCHECKED_CAST")
    fun checkSource(problem: BakedProblem, sample: Sample): Boolean {
        val verifier = Class.forName("com.eignex.klause.solver.validation.FiniteWitnessVerificationKt")
            .methods.single { it.name == "finiteWitnessVerifier" && it.parameterCount == 5 }
            .invoke(null, problem, null, Assumptions.None, Cancellation.after(2.seconds), null)
            as CandidateVerifier<Sample, Double?>
        return (verifier.verify(Candidate(sample, null)) is Verification.Accepted).also {
            check(it) { "captured final point failed original-source checking" }
        }
    }

    private fun sample(value: Sample) = buildJsonObject {
        put("bools", JsonArray(value.bools.map(::JsonPrimitive)))
        put("ints", JsonArray((0 until value.numIntVars).map { JsonPrimitive(value.exactIntValue(it).toString()) }))
        put("approximateReals", JsonArray(value.reals.map(::JsonPrimitive)))
    }

    internal fun distance(before: Sample, after: Sample, inputs: IntArray) = buildJsonObject {
        val changed = inputs.count { before.exactIntValue(it) != after.exactIntValue(it) }
        put("sourceInputChanges", changed)
        put("sourceAuxiliaryChanges", before.hammingDistanceTo(after) - changed)
        put("approximateRealL1Displacement", (0 until before.numRealVars).sumOf {
            abs(before.approximateRealValue(it) - after.approximateRealValue(it))
        })
    }

    private fun get(owner: Any, property: String): Any? = owner.javaClass.methods.single {
        it.name.substringBefore('$') == "get$property" && it.parameterCount == 0
    }.invoke(owner)
}
