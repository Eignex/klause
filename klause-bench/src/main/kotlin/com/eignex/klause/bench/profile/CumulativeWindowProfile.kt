package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.MiniZincCompatibility
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object CumulativeWindowProfile {
    private val counters = listOf("calls", "zeroLoadCalls", "scans", "windows", "energyVisits", "targetVisits",
        "tightenings", "conflicts", "suffixVisits")

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("suite", "problem", "prepare", "lp", "seeds", "decisions") })
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val ref = Catalog.uncapped(options.getValue("suite")).problems.single {
            it.name == options.getValue("problem")
        }
        out.resolve("source.mzn").writeBytes(CorpusFetcher.resolve(ref.source).readBytes())
        ref.data?.let { out.resolve("source.dzn").writeBytes(CorpusFetcher.resolve(it).readBytes()) }
        Reports.writeJson(out.resolve("compiler.json").path, MiniZincCompatibility.compiler)
        val text = MiniZincRunner().compileFzn(ref).readText()
        out.resolve("source.fzn").writeText(text)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val decisions = options["decisions"]?.toLong() ?: 512L
        require(decisions in 1L..10_000L)
        val records = out.resolve("windows.jsonl").apply { writeText("") }
        for ((pass, seed) in (options["seeds"] ?: "3,7").split(',').map(String::toLong).withIndex()) {
            val modes = if (pass % 2 == 0) listOf(0, 1, 2, 3) else listOf(3, 2, 1, 0)
            for (mode in modes) {
                val directory = out.resolve("seed-$seed-mode-$mode").apply { mkdirs() }
                val parsed = parseFlatZinc(text)
                val budget = PresolveBudget(500_000_000L)
                val preparationStop = Cancellation.after(20.seconds)
                val preparation = FinitePipeline.prepare(FinitePipelineRequest(
                    parsed.problem, FiniteEngine.BACKTRACK, parsed.linearObjective(),
                    presolveConfig = PresolveConfig.parse(options["prepare"] ?: "default"),
                    explicitPresolveConfig = true, cancellation = preparationStop, solveCancellation = preparationStop,
                    presolveBudget = budget, randomSeed = seed,
                ))
                val baked = preparation.problem.bake(cancellation = preparationStop)
                check(!preparationStop()) { "incomplete preparation" }
                val trace = Observation(mode, directory)
                val base = BacktrackParams()
                val stop = Cancellation.after(20.seconds)
                val before = bean.getThreadAllocatedBytes(thread)
                val started = System.nanoTime()
                val result = BacktrackSolver(baked).minimize(requireNotNull(preparation.objective), base.copy(
                    maxDecisions = decisions, randomSeed = seed, cancellation = stop,
                    variableSelector = ObservedVariables(base.variableSelector, trace),
                    lpConfig = when (options["lp"] ?: "off") {
                        "off" -> null
                        "default" -> LpConfig.AUTO
                        "aggressive" -> LpConfig.AGGRESSIVE
                        else -> error("unknown LP emphasis")
                    },
                ))
                val record = buildJsonObject {
                    put("revision", Reports.readGitSha())
                    put("problem", ref.name)
                    put("fznSha256", sha256(text.encodeToByteArray()))
                    put("seed", seed)
                    put("mode", mode)
                    put("preparation", options["prepare"] ?: "default")
                    put("lp", options["lp"] ?: "off")
                    put("preparationWork", budget.spent())
                    put("decisionCap", decisions)
                    put("searchDeadlineSeconds", 20)
                    put("cancelled", stop())
                    put("result", result.javaClass.simpleName)
                    put("reportedObjective", result.objectiveValue)
                    put("sourceWitnessChecked", false)
                    put("sourceVerdictClaim", false)
                    put("elapsedNs", System.nanoTime() - started)
                    put("threadAllocatedBytes", bean.getThreadAllocatedBytes(thread) - before)
                    put("nodes", result.stats.search.nodes.sum)
                    put("fails", result.stats.search.fails.sum)
                    put("propagationWork", result.stats.search.propagationWork.sum)
                    put("attachedSessions", trace.sessions.size)
                    put("scope", "after first selector pick; preparation and initial root use guarded completion sweep")
                    put("counters", buildJsonObject {
                        for (index in counters.indices) put(counters[index], trace.metrics[index])
                    })
                }
                records.appendText("$record\n")
                println(record)
            }
        }
    }

    private class ObservedVariables(private val delegate: VariableSelector, private val observation: Observation) :
        VariableSelector by delegate {
        override fun fresh(): VariableSelector = ObservedVariables(delegate.fresh(), observation)

        override fun pick(session: PropagationSession, rng: Random): VarRef? {
            observation.fixpoint(session)
            return delegate.pick(session, rng)
        }

        override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
            observation.pending.clear()
            delegate.onConflict(varRef, unsat)
        }
    }

    private class Observation(private val mode: Int, private val directory: File) {
        val sessions = HashSet<PropagationSession>()
        val metrics = LongArray(counters.size)
        val pending = ArrayList<Pair<Int, Cumulative>>()
        private var events = 0

        fun fixpoint(session: PropagationSession) {
            for ((event, factor) in pending) append(buildJsonObject {
                put("kind", "subsequent-fixpoint")
                put("event", event)
                put("level", session.decisionLevel)
                put("channels", CumulativeChoiceProfile.channelRows(session.problem, factor, session))
            })
            pending.clear()
            if (!sessions.add(session)) return
            directory.resolve("root-${sessions.size}.json").writeText(snapshot(session).toString())
            for ((index, propagator) in session.projection.propagators.withIndex()) {
                if (propagator.javaClass.simpleName != "CumulativePropagator") continue
                val factor = session.problem.factors[index] as Cumulative
                fun set(name: String, value: Any) = propagator.javaClass.getDeclaredField(name)
                    .apply { isAccessible = true }.set(propagator, value)
                set("subwindowMode", mode)
                set("subwindowMetrics", metrics)
                val witness: (Int, Long, Long, Long) -> Unit = { task, left, right, bound ->
                    if (events < 32) {
                        val event = events++
                        append(buildJsonObject {
                            put("kind", "demand-bound")
                            put("event", event)
                            put("factor", index)
                            put("task", task)
                            put("demand", factor.resourceVars[task])
                            put("before", session.intDomain(factor.resourceVars[task]).toString())
                            put("bound", bound)
                            put("left", left)
                            put("right", right)
                            put("snapshot", snapshot(session))
                            put("channels", CumulativeChoiceProfile.channelRows(session.problem, factor, session))
                        })
                        pending.add(event to factor)
                    }
                }
                set("subwindowWitness", witness)
            }
        }

        private fun append(record: JsonObject) = directory.resolve("events.jsonl").appendText("$record\n")

        private fun snapshot(session: PropagationSession) = buildJsonObject {
            put("level", session.decisionLevel)
            put("integers", JsonArray((0 until session.problem.numIntVars).map {
                JsonPrimitive(session.intDomain(it).toString())
            }))
            put("booleans", JsonArray((0 until session.problem.numBoolVars).map {
                JsonPrimitive(session.boolValue(it))
            }))
            put("decisions", buildJsonArray {
                for (level in 1..session.decisionLevel) add(JsonPrimitive(session.decisionAt(level).toString()))
            })
        }
    }
}
