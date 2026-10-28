package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.satisfyUnderAssumptions
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal object CoreQueryProfile {
    private val type = Class.forName("com.eignex.klause.meta.coreguided.CoreGuidedSearch")
    private val constructor = type.declaredConstructors.single { it.parameterCount == 8 }.apply {
        isAccessible = true
    }
    private val cores = type.getDeclaredField("completedCores").apply { isAccessible = true }
    private val core = type.getDeclaredField("coreLiterals").apply { isAccessible = true }
    private val softs = type.getDeclaredField("querySofts").apply { isAccessible = true }
    private val query = type.getDeclaredField("query").apply { isAccessible = true }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        out.resolve("cores.jsonl").bufferedWriter().use { output ->
            for (unrelated in listOf(1, 8)) {
                val sourceText = "p cnf ${unrelated + 2} 1\n${unrelated + 1} ${unrelated + 2} 0\n"
                val weights = LongArray(unrelated + 2) { if (unrelated == 1 && it == 0) 1L else 100L }
                val objective = LinearObjective(weights)
                out.resolve("source-$unrelated.cnf").writeText(sourceText)
                out.resolve("objective-$unrelated.txt").writeText(weights.joinToString(","))
                val identity = sha256((sourceText + weights.joinToString(",")).encodeToByteArray())
                for (prepared in listOf(false, true)) {
                    val source = Dimacs.parse(sourceText).toProblem()
                    val sourceBaked = source.bake()
                    val preparationStart = System.nanoTime()
                    val beforePreparation = bean.getThreadAllocatedBytes(thread)
                    val ordinary = if (prepared) FinitePipeline.prepare(FinitePipelineRequest(
                        source, FiniteEngine.BACKTRACK, objective, cancellation = Cancellation.after(2.seconds),
                    )) else null
                    val preparationNs = System.nanoTime() - preparationStart
                    val preparationBytes = bean.getThreadAllocatedBytes(thread) - beforePreparation
                    val model = ordinary?.problem as? BakedProblem ?: sourceBaked
                    val cost = ordinary?.objective ?: objective
                    for (repeat in 0..2) for (policy in listOf("ordinary", "stratified", "root-fixed", "refined")) {
                        val rootCostStart = System.nanoTime()
                        val rootCostBytes = bean.getThreadAllocatedBytes(thread)
                        val root = model.rootDeductions as? PropagationResult.Implied
                        val coefficients = cost.boolWeights
                        var offset = cost.constant
                        var removed = 0
                        for (variable in coefficients.indices) {
                            val weight = coefficients[variable]
                            val fixed = root?.boolValueOrNull(variable)
                            if (weight != 0L && fixed == (weight < 0L)) {
                                if (fixed == true) offset += weight
                                coefficients[variable] = 0L
                                removed++
                            }
                        }
                        val queryCost = if (policy == "root-fixed") cost.copy(boolWeights = coefficients,
                            constant = offset) else cost
                        val rootCostNs = if (policy == "root-fixed") System.nanoTime() - rootCostStart else 0L
                        val rootCostAllocation = if (policy == "root-fixed") {
                            bean.getThreadAllocatedBytes(thread) - rootCostBytes
                        } else 0L
                        val history = ArrayList<JsonObject>()
                        var search: ResumableSearch? = null
                        var observedCores = 0L
                        var observedQuery: Any? = null
                        fun observe() {
                            val live = search ?: return
                            val count = cores.getLong(live)
                            if (count != observedCores) {
                                history += buildJsonObject {
                                    put("event", "core")
                                    put("index", count)
                                    put("literals", ints(core.get(live) as IntArray))
                                    put("floor", live.stats.coreGuided.lowerBound.toString())
                                }
                                observedCores = count
                            }
                            val handle = query.get(live)
                            if (handle != null && handle !== observedQuery) {
                                history += buildJsonObject {
                                    put("event", "query")
                                    put("softs", ints(softs.get(live) as IntArray))
                                }
                                observedQuery = handle
                            }
                        }
                        val timeout = Cancellation.after(2.seconds)
                        val stop = timeout or Cancellation { observe(); false }
                        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin,
                            cancellation = stop, nativeSat = false)
                        val started = System.nanoTime()
                        val before = bean.getThreadAllocatedBytes(thread)
                        val handle = constructor.newInstance(model, queryCost, params, policy == "stratified", null,
                            2_000L, TimeSource.Monotonic, policy == "refined") as ResumableSearch
                        search = handle
                        val result = handle.use { it.runSlice(stop, 2_000L, -1L) {} }
                        observe()
                        val elapsed = System.nanoTime() - started
                        val bytes = bean.getThreadAllocatedBytes(thread) - before
                        val candidate = (result as? MinimizeResult.WithSample)?.sample
                        val point = candidate?.let { ordinary?.reconstruct?.invoke(it) ?: it }
                        val sourceAccepted = point?.let { checkPoint(sourceBaked, it) }
                        if (point != null) check(sourceAccepted == true)
                        val record = buildJsonObject {
                            put("sourceSha256", identity)
                            put("unrelated", unrelated)
                            put("prepared", prepared)
                            put("policy", policy)
                            put("repeat", repeat)
                            put("warmup", repeat == 0)
                            put("preparationNs", preparationNs)
                            put("preparationThreadBytes", preparationBytes)
                            put("preparedBooleans", model.numBoolVars)
                            put("preparedFactors", model.numFactors)
                            put("rootSatisfiedCostPins", removed)
                            put("elapsedNsIncludingDisposal", elapsed + rootCostNs)
                            put("threadBytesIncludingDisposal", bytes + rootCostAllocation)
                            put("nativeResult", result?.javaClass?.simpleName)
                            put("sourceAccepted", sourceAccepted)
                            put("sourceObjective", point?.let { objective.evaluateLong(it) })
                            put("cores", handle.stats.coreGuided.cores)
                            put("largestCore", handle.stats.coreGuided.largestCore)
                            put("floor", handle.stats.coreGuided.lowerBound.toString())
                            put("work", handle.work)
                            put("nodes", handle.stats.search.nodes.sum)
                            put("propagation", handle.stats.search.propagationWork.sum)
                            put("history", JsonArray(history))
                            put("independentProofCheck", false)
                        }
                        output.appendLine(record.toString())
                        output.flush()
                    }
                    val pins = Assumptions(bools = (0 until model.numBoolVars).associateWith { false })
                    for (minimize in listOf(false, true)) {
                        val started = System.nanoTime()
                        val result = BacktrackSolver(model).satisfyUnderAssumptions(pins,
                            BacktrackParams(cancellation = Cancellation.after(2.seconds)), minimize)
                        out.resolve("helper-$unrelated-$prepared-$minimize.txt").writeText(
                            "elapsedNs=${System.nanoTime() - started}\n$result\n",
                        )
                    }
                }
            }
        }
    }

    private fun ints(values: IntArray) = JsonArray(values.map(::JsonPrimitive))

    internal fun checkPoint(problem: BakedProblem, sample: Sample): Boolean {
        val session = PropagationSession(problem)
        val pins = Assumptions(bools = (0 until problem.numBoolVars).associateWith { sample.boolValue(it) })
        return session.seed(pins) !is PropagationResult.Unsat && !session.fixpointCancelled
    }
}
