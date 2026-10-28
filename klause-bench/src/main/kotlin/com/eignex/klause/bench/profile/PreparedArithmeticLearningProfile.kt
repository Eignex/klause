package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.formats.opb.Opb
import com.eignex.klause.formats.opb.toProblem
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object PreparedArithmeticLearningProfile {
    private val sources = listOf(
        """
        * #variable= 5 #constraint= 3
        +1 ~x1 +1 x3 +1 x4 +1 x5 >= 2 ;
        +1 x2 -1 x3 >= 0 ;
        +1 x2 -1 x4 >= 0 ;
        """.trimIndent(),
        """
        var 0..6: x;
        var 0..12: y;
        var 0..24: z;
        constraint int_lin_le([2,1,-1],[x,y,z],0);
        constraint int_lin_le([2,-1],[x,y],0);
        solve satisfy;
        """.trimIndent(),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        sources.forEachIndexed { index, source -> out.resolve("source-$index.${if (index == 0) "opb" else "fzn"}")
            .writeText(source) }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val records = out.resolve("prepared-learning.jsonl").apply { writeText("") }
        val cells = (0..1).flatMap { source -> (0..2).flatMap { route ->
            listOf(false, true).map { learning -> Triple(source, route, learning) }
        } }
        for (pass in 0..3) {
            val order = if (pass % 2 == 0) cells else cells.reversed()
            for ((source, route, learning) in order) {
                val before = bean.getThreadAllocatedBytes(thread)
                val started = System.nanoTime()
                val observation = invoke(source, route, learning)
                val finished = System.nanoTime()
                val allocated = bean.getThreadAllocatedBytes(thread) - before
                val record = buildJsonObject {
                    put("revision", Reports.readGitSha())
                    put("sourceSha256", sha256(sources[source].encodeToByteArray()))
                    put("source", if (source == 0) "pb" else "integer")
                    put("route", listOf("retained-script", "prepared-script", "prepared-production-lp")[route])
                    put("learning", learning)
                    put("warmup", pass < 2)
                    put("pass", pass)
                    put("intervalStartNs", started)
                    put("intervalEndNs", finished)
                    put("invocationElapsedNs", finished - started)
                    put("invocationThreadAllocatedBytes", allocated)
                    put("measurement", "disjoint; parse through native point check, release and capture serialization; JFR enabled")
                    put("physicalGcOrRssMeasured", false)
                    put("independentWitnessOrProofCheck", false)
                    put("osName", System.getProperty("os.name"))
                    put("osArch", System.getProperty("os.arch"))
                    put("javaVersion", System.getProperty("java.version"))
                    put("observation", observation)
                }
                records.appendText("$record\n")
                println(record)
            }
        }
    }

    private fun invoke(source: Int, route: Int, learning: Boolean): JsonObject {
        val stop = Cancellation.after(2.seconds)
        val original = if (source == 0) Opb.parse(sources[source]).toProblem().problem
            else parseFlatZinc(sources[source]).problem
        val codec = ProblemJson()
        val originalJson = codec.encodeToString(original)
        val prepared = if (route == 0) null else FinitePipeline.prepare(FinitePipelineRequest(
            original, FiniteEngine.BACKTRACK, cancellation = stop, solveCancellation = stop, randomSeed = 3L,
        ))
        val problem = prepared?.problem ?: original
        val preparedJson = codec.encodeToString(problem)
        if (route == 2) {
            val result = BacktrackSolver(problem as BakedProblem).solve(BacktrackParams(
                pbLearning = source == 0 && learning, integerRowLearning = source == 1 && learning,
                lpConfig = LpConfig.AUTO, randomSeed = 3L, maxDecisions = 1024, cancellation = stop,
            ))
            val sample = (result as? SolveResult.Sat)?.assignment?.let { prepared!!.reconstruct(it) }
            val checked = sample?.let { checkPoint(original, it, stop) } ?: false
            return buildJsonObject {
                put("original", originalJson)
                put("prepared", preparedJson)
                put("presolve", prepared?.presolve.toString())
                put("verdict", result::class.simpleName)
                put("nativeOriginalPointCheck", checked)
                put("witness", sample?.let { "bools=${it.bools.joinToString()};ints=${it.ints.joinToString()}" })
                put("changedTrajectory", true)
                put("stats", result.stats.toString())
                put("cancelled", stop())
            }
        }
        val native = PropagationSession(problem, cancellation = stop, pbLearning = source == 0 && learning)
        if (source == 1 && learning) native.enableIntegerRowLearning()
        val capture = Capture(native)
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(cp), cancellation = stop, atoms = SearchAtomRegistry(problem.numBoolVars))
        val initialized = shared.initialize()
        val identity = originalJson == preparedJson
        var conflict = false
        var resolution = "not-run"
        var siblingUpper: Long? = null
        if (initialized is ComponentResult.Consistent && identity) {
            val first = if (source == 0) SearchDecision.Bool(Lit.make(0, true)) else SearchDecision.IntAtMost(2, 7)
            val second = if (source == 0) SearchDecision.Bool(Lit.make(1, false)) else SearchDecision.IntAtLeast(0, 2)
            if (shared.push(first) is ComponentResult.Consistent) {
                conflict = shared.push(second) is ComponentResult.Conflict
                if (conflict) {
                    val learned = cp.resolveConflict(shared)
                    resolution = learned::class.simpleName ?: "unknown"
                    if (learned is SearchConflictResolution.Backjump) {
                        shared.popTo(learned.conflict.decisionLevel)
                        learned.conflict.apply(shared)
                        if (source == 1) {
                            shared.popTo(0)
                            shared.push(SearchDecision.IntAtMost(2, 11))
                            siblingUpper = native.intDomain(0).max
                        }
                    }
                }
            }
        }
        val point = if (source == 0) Sample(booleanArrayOf(true, true, false, true, true), longArrayOf())
            else Sample(booleanArrayOf(), longArrayOf(2, 4, 11))
        val checked = checkPoint(original, point, stop)
        val stores = native.learnedClauseCount
        shared.popTo(0)
        val trace = capture.export()
        capture.release()
        return buildJsonObject {
            put("original", originalJson)
            put("prepared", preparedJson)
            put("presolve", prepared?.presolve.toString())
            put("fixedSourceCoordinates", identity)
            put("scriptDecline", if (identity) "none" else "preparation-changed-source-identity; no guessed remap")
            put("initialized", initialized::class.simpleName)
            put("conflict", conflict)
            put("resolution", resolution)
            put("siblingUpper", siblingUpper)
            put("nativeStores", stores)
            put("finiteWork", capture.work())
            put("nativeOriginalPointCheck", checked)
            put("checkedPoint", "bools=${point.bools.joinToString()};ints=${point.ints.joinToString()}")
            put("checkedPointIsSearchWitness", false)
            put("trace", trace)
            put("cancelled", stop())
        }
    }

    private fun checkPoint(problem: Problem, sample: Sample, stop: Cancellation): Boolean {
        val pinned = problem.withIntDomains(Array(problem.numIntVars) {
            val value = sample.intValue(it)
            check(value in requireNotNull(problem.intDomainOrNull(it)))
            IntDomain(value, value)
        })
        val checker = PropagationSession(pinned, cancellation = stop)
        if (checker.isUnsatAtRoot) return false
        for (variable in 0 until problem.numBoolVars) {
            if (checker.pinBool(variable, sample.boolValue(variable)) !is PropagationResult.Implied) return false
        }
        return !checker.fixpointCancelled && !stop()
    }

    private class Capture(native: PropagationSession) {
        private val state = native.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(native)
        private val field = state.javaClass.getDeclaredField("arithmeticLearningTrace").apply { isAccessible = true }
        private val trace = Class.forName("com.eignex.klause.propagation.ArithmeticLearningTrace")
            .getConstructor().newInstance()

        init { field.set(state, trace) }

        fun work(): Long = state.javaClass.declaredMethods.single {
            it.name.startsWith("getWork") && it.parameterCount == 0
        }.invoke(state) as Long

        fun export(): JsonObject = buildJsonObject {
            put("truncated", trace.javaClass.getMethod("getTruncated").invoke(trace) as Boolean)
            put("words", trace.javaClass.getMethod("getWords").invoke(trace) as Int)
            put("events", buildJsonArray {
                for (event in trace.javaClass.getMethod("getEvents").invoke(trace) as List<*>) {
                    requireNotNull(event)
                    add(buildJsonObject {
                        put("kind", event.javaClass.getMethod("getKind").invoke(event) as String)
                        put("data", JsonArray((event.javaClass.getMethod("getData").invoke(event) as LongArray)
                            .map(::JsonPrimitive)))
                    })
                }
            })
        }

        fun release() { field.set(state, null) }
    }
}
