package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.checkExactRealSample
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds

internal object HistoricalTableReasonProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val records = out.resolve("historical-table.jsonl").apply { writeText("") }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        for (variant in 0..2) {
            val source = source(variant)
            out.resolve("source-$variant.fzn").writeText(source)
            for (pass in 0..1) for (prepared in listOf(false, true)) {
                for (enabled in if (pass == 0) listOf(false, true) else listOf(true, false)) {
                    val before = bean.getThreadAllocatedBytes(thread)
                    val start = System.nanoTime()
                    val observation = invoke(source, prepared, enabled)
                    val elapsed = System.nanoTime() - start
                    val allocated = bean.getThreadAllocatedBytes(thread) - before
                    val record = buildJsonObject {
                        put("variant", variant)
                        put("sourceSha256", sha256(source.encodeToByteArray()))
                        put("prepared", prepared)
                        put("enabled", enabled)
                        put("warmup", pass == 0)
                        put("elapsedNs", elapsed)
                        put("threadAllocatedBytes", allocated)
                        put("measurement", "instrumented parse, preparation, replay, application, sibling and disposal")
                        put("independentWitnessOrProofCheck", false)
                        put("physicalRssOrGcMeasured", false)
                        put("javaVersion", System.getProperty("java.version"))
                        put("osArch", System.getProperty("os.arch"))
                        put("observation", observation)
                    }
                    records.appendText("$record\n")
                    println(record)
                }
            }
            val program = parseFlatZinc(source)
            val stop = Cancellation.after(2.seconds)
            val preparation = FinitePipeline.prepare(FinitePipelineRequest(
                program.problem, FiniteEngine.BACKTRACK, cancellation = stop, solveCancellation = stop))
            val result = BacktrackSolver(preparation.problem as BakedProblem).solve(BacktrackParams(
                randomSeed = 3L, maxDecisions = 128, cancellation = stop))
            val point = (result as? SolveResult.Sat)?.assignment?.let(preparation.reconstruct)
            val record = buildJsonObject {
                put("variant", variant)
                put("control", "ordinary prepared backtrack with automatic LP")
                put("verdict", result::class.simpleName)
                put("stats", result.stats.toString())
                put("originalSourceNativeCheck", point?.let { program.problem.checkExactRealSample(it, stop) })
                put("independentWitnessOrProofCheck", false)
                put("sourceSha256", sha256(source.encodeToByteArray()))
                put("cancelled", stop())
            }
            records.appendText("$record\n")
            println(record)
        }
    }

    private fun invoke(source: String, prepare: Boolean, enabled: Boolean): JsonObject {
        val stop = Cancellation.after(2.seconds)
        val program = parseFlatZinc(source)
        val original = program.problem
        val preparation = if (prepare) FinitePipeline.prepare(FinitePipelineRequest(
            original, FiniteEngine.BACKTRACK, cancellation = stop, solveCancellation = stop)) else null
        val problem = preparation?.problem ?: original
        val mapping = if (preparation == null) VarRemap(IntArray(original.numBoolVars) { it },
            IntArray(original.numIntVars) { it }) else {
            val captured = preparation.javaClass.getDeclaredField("sourceMapping").apply { isAccessible = true }
                .get(preparation)
            if (captured == null) null else Class.forName("com.eignex.klause.presolve.SourceMappingKt")
                .getDeclaredMethod("retainedCoordinates", captured.javaClass, Cancellation::class.java)
                .invoke(null, captured, stop) as VarRemap?
        }
        val b = mapping?.int(program.intVarsByName.getValue("b")) ?: -1
        val a = mapping?.int(program.intVarsByName.getValue("a")) ?: -1
        val q = mapping?.bool(program.boolVarsByName.getValue("q")) ?: -1
        val codec = ProblemJson()
        val native = PropagationSession(problem, cancellation = stop)
        val state = native.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(native)
        val flag = state.javaClass.getDeclaredField("historicalTableReasons").apply { isAccessible = true }
        flag.setBoolean(state, enabled)
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(cp), cancellation = stop, atoms = SearchAtomRegistry(problem.numBoolVars))
        val outcomes = ArrayList<String>()
        var target: Int? = null
        var size: Int? = null
        var lbd: Int? = null
        var application: String? = null
        var siblingQ: Boolean? = null
        val initialized = shared.initialize()
        if (b >= 0 && a >= 0 && q >= 0 && initialized is ComponentResult.Consistent) {
            val decisions = listOf(SearchDecision.IntAtLeast(b, 1), SearchDecision.IntAtLeast(a, 1),
                SearchDecision.Bool(Lit.make(q, true)))
            for (decision in decisions) {
                val result = shared.push(decision)
                outcomes.add(result::class.simpleName ?: "unknown")
                if (result is ComponentResult.Conflict) {
                    val learned = cp.resolveConflict(shared)
                    if (learned is SearchConflictResolution.Backjump) {
                        target = learned.conflict.decisionLevel
                        size = learned.conflict.guardLiterals.size
                        lbd = learned.conflict.lbd
                        shared.popTo(target)
                        application = learned.conflict.apply(shared)::class.simpleName
                        if (target == 1) {
                            shared.push(SearchDecision.IntAtMost(a, 0))
                            siblingQ = native.boolValue(q)
                        }
                    }
                    break
                }
            }
        }
        val learnedCount = native.learnedClauseCount
        val work = state.javaClass.declaredMethods.single { it.name.startsWith("getWork") && it.parameterCount == 0 }
            .invoke(state) as Long
        shared.popTo(0)
        flag.setBoolean(state, false)
        return buildJsonObject {
            put("original", codec.encodeToString(original))
            put("prepared", codec.encodeToString(problem))
            put("presolve", preparation?.presolve.toString())
            put("sourceCoordinatesRetained", b >= 0 && a >= 0 && q >= 0)
            put("initialized", initialized::class.simpleName)
            put("decisions", outcomes.joinToString())
            put("backjump", target)
            put("lbd", lbd)
            put("learnedSize", size)
            put("application", application)
            put("aZeroSiblingQ", siblingQ)
            put("learnedCount", learnedCount)
            put("finiteWork", work)
            put("cancelled", stop())
        }
    }

    private fun source(variant: Int): String {
        val rows = (0 until 8).map { listOf(0, it / 4, it / 2 % 2, it % 2) } +
            listOf(listOf(1, 0, 0, 1), listOf(1, 1, 1, 0))
        val order = if (variant == 2) listOf(0, 2, 1, 3) else listOf(0, 1, 2, 3)
        val cells = rows.flatMap { row -> order.map { row[it] } }.joinToString()
        val names = order.map { listOf("x", "a", "b", "c")[it] }.joinToString()
        return """
            var 0..1: x;
            var 0..1: a;
            var 0..1: b;
            var 0..1: c;
            var bool: q;
            var bool: x1;
            var bool: b0;
            ${if (variant == 0) "" else "var bool: d;"}
            constraint table_int([$names], [$cells]);
            constraint int_eq_reif(x, 1, x1);
            constraint int_eq_reif(b, 0, b0);
            constraint int_eq_imp(c, 1, q);
            ${if (variant == 0) "constraint bool_clause([x1,b0], [q]);" else
                "constraint bool_clause([d], [q]); constraint bool_clause([x1,b0], [d]);"}
            solve satisfy;
        """.trimIndent()
    }
}
