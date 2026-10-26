package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FinitePipelinePreparation
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

internal object SemanticRepairComparison {
    fun capture(problem: BakedProblem, objective: LinearObjective, center: Sample, sweep: DefinitionalSweep?,
        inputs: IntArray, prepared: FinitePipelinePreparation, source: BakedProblem, out: File, seed: Long,
        evaluateRepairs: Boolean = true,
    ) {
        require(prepared.reconstruct(center) == center)
        RepairTrajectory.checkSource(source, center)
        val stop = Cancellation.after(15.seconds)
        val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(allocation.isThreadAllocatedMemorySupported)
        allocation.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val setupBytes = allocation.getThreadAllocatedBytes(thread)
        val setupStarted = System.nanoTime()
        val network = sweep?.network(problem.numIntVars, problem.numBoolVars)
        val type = Class.forName("com.eignex.klause.meta.alns.SemanticBlockRemoval")
        val kindType = Class.forName("com.eignex.klause.meta.alns.SemanticBlockRemoval\$Kind")
        val kind = when {
            problem.factors.any { it is AllDifferent } -> "Assignment"
            problem.factors.any { it is Cumulative } -> "Resource"
            problem.factors.any { it is Diffn } -> "Placement"
            else -> error("no supported relation")
        }
        val selector = type.getDeclaredConstructor(kindType, InvariantNetwork::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(kindType.enumConstants.single { it.toString() == kind }, network, 32, 4096, 65_536)
        val destroy = type.methods.single { it.name == "destroy" && it.parameterCount == 7 }
        val close = type.methods.single { it.name == "closeControl" }
        val operator = Class.forName("com.eignex.klause.meta.alns.DestroyOperator")
        val companion = operator.getField("Companion").get(null)
        val oldDestroy = operator.methods.single { it.name == "destroy" && it.parameterCount == 7 }
        val baselines = listOf("Random", "WorstObjective", "AdjacencyRelated").associateWith {
            checkNotNull(getter(companion, it))
        }
        val freedType = Class.forName("com.eignex.klause.meta.alns.FreedVars")
        val freedConstructor = freedType.getDeclaredConstructor(IntArray::class.java, IntArray::class.java)
        val scope = Class.forName("com.eignex.klause.meta.alns.RepairScope").getField("INSTANCE").get(null)
        val effective = scope.javaClass.methods.single { it.name == "effective" }
        val pins = scope.javaClass.methods.single { it.name == "pins" }
        val setupNanos = System.nanoTime() - setupStarted
        val setupAllocated = allocation.getThreadAllocatedBytes(thread) - setupBytes
        val eligible = inputs.filter { !problem.rootIntDomain(it).isFixed }
        var attempts = 0
        var planned = 0
        out.resolve("selectors.jsonl").bufferedWriter().use { writer ->
            repeat(16) { repetition ->
                val arms = listOf("semantic", "Random", "WorstObjective", "AdjacencyRelated", "eligible-matched")
                var target = 0
                planned += arms.size
                for (arm in arms) {
                    if (stop()) break
                    val random = Random(seed + repetition)
                    val beforeBytes = allocation.getThreadAllocatedBytes(thread)
                    val started = System.nanoTime()
                    val raw = when (arm) {
                        "semantic" -> destroy.invoke(selector, random, problem, center, objective, 0.5,
                            Assumptions.None, stop)
                        "eligible-matched" -> freedConstructor.newInstance(intArrayOf(),
                            eligible.shuffled(random).take(target).toIntArray())
                        else -> oldDestroy.invoke(baselines.getValue(arm), random, problem, center, objective,
                            (target + 0.25) / (problem.numBoolVars + problem.numIntVars), Assumptions.None, stop)
                    }
                    val closed = if (arm == "semantic") raw else close.invoke(selector, problem, raw,
                        Assumptions.None, stop)
                    val selected = checkNotNull(effective.invoke(scope, closed, Assumptions.None, sweep, stop))
                    val selectionNanos = System.nanoTime() - started
                    val selectionBytes = allocation.getThreadAllocatedBytes(thread) - beforeBytes
                    val variables = getter(selected, "Ints") as IntArray
                    val bools = getter(selected, "Bools") as IntArray
                    val sourceCount = variables.count { it in inputs }
                    if (arm == "semantic") target = sourceCount
                    val decline = getter(selector, "LastDecline")?.toString()
                    val visits = getter(selector, "LastVisits") as Int
                    val searched = getter(selector, "LastSearchedInputs") as Int
                    val requested = if (arm == "semantic") getter(selector, "LastRequested") else raw
                    val repairBytes = allocation.getThreadAllocatedBytes(thread)
                    val repairStarted = System.nanoTime()
                    val result = if (!evaluateRepairs || (variables.isEmpty() && bools.isEmpty())) null else {
                        val assumptions = pins.invoke(scope, problem, center, selected) as Assumptions
                        BacktrackSolver(problem).minimize(objective, BacktrackParams(assumptions = assumptions,
                            maxDecisions = 10_000, cancellation = stop, randomSeed = seed + repetition))
                    }
                    val repairNanos = System.nanoTime() - repairStarted
                    val repairAllocated = allocation.getThreadAllocatedBytes(thread) - repairBytes
                    attempts++
                    writer.appendLine(buildJsonObject {
                        put("repetition", repetition)
                        put("seed", seed + repetition)
                        put("selector", arm)
                        put("kind", kind)
                        put("center", RepairTrajectory.sample(center))
                        put("nominalSourceTarget", target)
                        put("effectiveSourceInputs", sourceCount)
                        put("sourceCountMatched", sourceCount == target)
                        put("searchedInputs", searched)
                        put("decline", decline)
                        put("modeledVisits", visits)
                        put("modeledVisitScope", if (arm == "semantic") "construction-and-closure" else "closure-only")
                        put("selectionElapsedNanos", selectionNanos)
                        put("selectionThreadAllocatedBytes", selectionBytes)
                        put("repairElapsedNanos", repairNanos)
                        put("repairThreadAllocatedBytes", repairAllocated)
                        put("producerRepairEvaluated", result != null)
                        put("requestedInts", JsonArray(((requested?.let { getter(it, "Ints") }) as IntArray?
                            ?: intArrayOf()).map(::JsonPrimitive)))
                        put("effectiveInts", JsonArray(variables.map(::JsonPrimitive)))
                        put("effectiveBools", JsonArray(bools.map(::JsonPrimitive)))
                        put("centerObjective", objective.evaluate(center))
                        put("result", result?.let { it::class.simpleName })
                        put("reason", when (result) {
                            is MinimizeResult.Unknown -> result.reason.toString()
                            is MinimizeResult.BestFound -> result.reason.toString()
                            else -> null
                        })
                        put("completedLocalExhaustion", result is MinimizeResult.Optimal ||
                            result is MinimizeResult.Infeasible)
                        put("strictImprovement", result?.assignment?.let {
                            objective.evaluateExact(it) < objective.evaluateExact(center)
                        } ?: false)
                        put("nodes", result?.stats?.search?.nodes?.sum)
                        result?.assignment?.let {
                            put("repairedInts", JsonArray((0 until it.numIntVars).map { index ->
                                JsonPrimitive(it.exactIntValue(index).toString())
                            }))
                            put("repairedObjective", objective.evaluate(it))
                            put("nativeSourceAccepted", RepairTrajectory.checkSource(source, prepared.reconstruct(it)))
                        }
                        put("globalProofClaim", false)
                    }.toString())
                    writer.flush()
                }
            }
        }
        out.resolve("selectors-limits.json").writeText(buildJsonObject {
            put("setupElapsedNanos", setupNanos)
            put("setupThreadAllocatedBytes", setupAllocated)
            put("setupIncludes", "definition network, selector, reflection bindings")
            put("maxDecisionsPerRepair", 10_000)
            put("producerRepairsEnabled", evaluateRepairs)
            put("containingSeconds", 15)
            put("maxInputs", 32)
            put("maxVariables", 4096)
            put("maxVisitsPerSelection", 65_536)
            put("planned", planned)
            put("attempted", attempts)
            put("cancelled", stop())
            put("completed", attempts == planned && !stop())
            put("sourceVerdictClaim", false)
            put("independentWitnessCheck", false)
        }.toString())
    }

    private fun getter(owner: Any, property: String): Any? = owner.javaClass.methods.single {
        it.name.substringBefore('$') == "get$property" && it.parameterCount == 0
    }.invoke(owner)
}
