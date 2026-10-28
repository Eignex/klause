package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.formats.opb.Opb
import com.eignex.klause.formats.opb.toProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal object WeightedSearchProfile {
    private val type = Class.forName("com.eignex.klause.meta.coreguided.CoreGuidedSearch")
    private val constructor = type.declaredConstructors.single { it.parameterCount == 8 }.apply {
        isAccessible = true
    }
    private val fields = listOf("preparationVisits", "thresholdVisits", "queryCount", "verificationVisits")
        .associateWith { type.getDeclaredField(it).apply { isAccessible = true } }
    private val weight = type.getDeclaredField("coreWeight").apply { isAccessible = true }
    private val threshold = type.getDeclaredField("threshold").apply { isAccessible = true }
    private val softs = type.getDeclaredField("querySofts").apply { isAccessible = true }
    private val coreCount = type.getDeclaredField("completedCores").apply { isAccessible = true }

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("suite", "problem", "sha256", "format", "policy", "timeout-ms",
            "work", "slice-work", "seed") })
        val suite = requireNotNull(options["suite"])
        val problem = requireNotNull(options["problem"])
        val hash = requireNotNull(options["sha256"])
        val format = options["format"] ?: "wcnf"
        val policy = options["policy"] ?: "ordinary"
        require(format in setOf("wcnf", "wbo") && policy in setOf("ordinary", "stratified", "reasons", "deletion"))
        val timeout = (options["timeout-ms"] ?: "10000").toLong().also { require(it in 1..60_000L) }
        val maximumWork = (options["work"] ?: "250000").toLong().also { require(it in 1..10_000_000L) }
        val sliceWork = (options["slice-work"] ?: "200").toLong().also { require(it in 1..maximumWork) }
        val seed = (options["seed"] ?: "3").toLong()
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val opened = System.nanoTime()
        val stop = Cancellation.after(timeout.milliseconds)
        val bytes = trajectorySourceBytes(suite, problem, hash, null)
        out.resolve("source.$format").writeBytes(bytes)
        val source: Problem
        val objective: LinearObjective
        if (format == "wbo") {
            val parsed = Opb.parse(bytes.decodeToString()).toProblem()
            source = parsed.problem
            objective = requireNotNull(parsed.objective).toLinearObjective()
        } else {
            val parsed = Dimacs.parseWcnf(bytes.decodeToString()).toProblem()
            source = parsed.problem
            objective = parsed.objective.toLinearObjective()
        }
        require(source.numIntVars == 0 && source.numRealVars == 0)
        val budget = PresolveBudget(100_000_000L)
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(
            source, FiniteEngine.BACKTRACK, objective, cancellation = stop, solveCancellation = stop,
            presolveBudget = budget, randomSeed = seed,
        ))
        val model = prepared.problem as? BakedProblem
        val preparationNanos = System.nanoTime() - opened
        val original = source.bake(stop)
        out.resolve("trajectory.jsonl").bufferedWriter().use { output ->
            var handle: ResumableSearch? = null
            var previousCores = -1L
            var previousQueries = -1L
            var incumbent: String? = null
            fun observe(event: String, force: Boolean = false) {
                val live = handle ?: return
                val queries = fields.getValue("queryCount").getLong(live)
                val cores = coreCount.getLong(live)
                if (!force && cores == previousCores && queries == previousQueries) return
                val stats = live.stats.coreGuided
                val floor = stats.lowerBound.toString()
                previousCores = stats.cores
                previousQueries = queries
                output.appendLine(buildJsonObject {
                    put("event", event)
                    put("elapsedNs", System.nanoTime() - opened)
                    put("floor", floor)
                    put("cores", stats.cores)
                    put("largestCore", stats.largestCore)
                    put("coreWeight", weight.get(live).toString())
                    put("threshold", threshold.get(live)?.toString())
                    put("querySofts", (softs.get(live) as IntArray).size)
                    put("incumbent", incumbent)
                    put("work", live.work)
                    put("nodes", live.stats.search.nodes.sum)
                    put("propagationVisits", live.stats.search.propagationWork.sum)
                    for ((name, field) in fields) put(name, field.getLong(live))
                }.toString())
                output.flush()
            }
            val observing = stop or Cancellation { observe("bound"); false }
            val params = BacktrackParams(randomSeed = seed, cancellation = observing,
                objectiveLowerBoundSink = { observe("bound") })
            var result: MinimizeResult? = null
            var checked = 0
            if (model != null && !stop()) {
                val live = constructor.newInstance(model, prepared.objective, params, policy == "stratified",
                    maximumWork, timeout, TimeSource.Monotonic, CoreQueryProfile.refinementMode(when (policy) {
                        "reasons" -> "REASONS"
                        "deletion" -> "DELETION"
                        else -> "NONE"
                    })) as ResumableSearch
                handle = live
                live.use {
                    while (result == null && !stop()) {
                        result = live.runSlice(observing, timeout, sliceWork) { accepted ->
                            val point = prepared.reconstruct(accepted.sample)
                            check(CoreQueryProfile.checkPoint(original, point, stop))
                            incumbent = objective.evaluateExact(point).toString()
                            checked++
                            observe("accepted-incumbent", true)
                        }
                    }
                    observe("terminal", true)
                }
            }
            Reports.writeJson(out.resolve("summary.json").path, buildJsonObject {
                put("suite", suite)
                put("problem", problem)
                put("sourceSha256", sha256(bytes))
                put("policy", policy)
                put("seed", seed)
                put("maximumWork", maximumWork)
                put("sliceWork", sliceWork)
                put("containingMillis", timeout)
                put("sourcePreparationNs", preparationNanos)
                put("presolveVisits", budget.spent())
                put("preparedBooleans", model?.numBoolVars)
                put("preparedFactors", model?.numFactors)
                put("preparedIntegers", model?.numIntVars)
                put("result", result?.javaClass?.simpleName)
                put("acceptedIncumbents", checked)
                put("elapsedNsIncludingClose", System.nanoTime() - opened)
                put("sourceCheck", "native-Boolean-point-propagation")
                put("independentProofCheck", false)
            })
        }
    }
}
