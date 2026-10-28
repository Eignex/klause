package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.codec.FactorCodecs
import com.eignex.klause.factor.codec.MissingFactorCodecException
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds

internal object HistoricalTableReasonProfile {
    private val routes = listOf("original", "prepared-satisfaction", "prepared-solution-set")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty() || args.contentEquals(arrayOf("controlsOnly=true")))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val records = out.resolve("historical-table.jsonl").apply { writeText("") }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        for (variant in 0..2) {
            val source = source(variant)
            out.resolve("source-$variant.fzn").writeText(source)
            if (args.isEmpty()) {
                for (pass in 0..1) {
                    for (route in 0..2) {
                for (enabled in if (pass == 0) listOf(false, true) else listOf(true, false)) {
                    val before = bean.getThreadAllocatedBytes(thread)
                    val start = System.nanoTime()
                    val observation = invoke(source, route, enabled)
                    val elapsed = System.nanoTime() - start
                    val allocated = bean.getThreadAllocatedBytes(thread) - before
                    val record = buildJsonObject {
                        put("variant", variant)
                        put("sourceSha256", sha256(source.encodeToByteArray()))
                        put("route", routes[route])
                        put("enabled", enabled)
                        put("warmup", pass == 0)
                        put("elapsedNs", elapsed)
                        put("threadAllocatedBytes", allocated)
                        put("measurement", "parse/preparation/replay/application/sibling/retraction; JFR")
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
                }
            }
            val controlStart = System.nanoTime()
            val controlBefore = bean.getThreadAllocatedBytes(thread)
            val program = parseFlatZinc(source)
            val stop = Cancellation.after(2.seconds)
            val preparation = FinitePipeline.prepare(
                FinitePipelineRequest(
                program.problem,
                FiniteEngine.BACKTRACK,
                cancellation = stop,
                solveCancellation = stop,
            )
            )
            val result = BacktrackSolver(preparation.problem as BakedProblem).solve(
                BacktrackParams(
                lpConfig = LpConfig.AUTO,
                randomSeed = 3L,
                maxDecisions = 128,
                cancellation = stop,
            )
            )
            val point = (result as? SolveResult.Sat)?.assignment?.let(preparation.reconstruct)
            val checked = point?.let { sample ->
                val fixed = program.problem.withIntDomains(
                    Array(program.problem.numIntVars) {
                    val value = sample.intValue(it)
                    IntDomain(value, value)
                }
                )
                val checker = PropagationSession(fixed, cancellation = stop)
                !checker.isUnsatAtRoot && (0 until program.problem.numBoolVars).all {
                    checker.pinBool(it, sample.boolValue(it)) is PropagationResult.Implied
                } && !checker.fixpointCancelled && !stop()
            }
            val record = buildJsonObject {
                put("variant", variant)
                put("control", "ordinary prepared backtrack with automatic LP")
                put("lpPolicy", "AUTO")
                put("elapsedNs", System.nanoTime() - controlStart)
                put("threadAllocatedBytes", bean.getThreadAllocatedBytes(thread) - controlBefore)
                put("verdict", result::class.simpleName)
                put("stats", result.stats.toString())
                put("originalSourceNativeCheck", checked)
                put("independentWitnessOrProofCheck", false)
                put("sourceSha256", sha256(source.encodeToByteArray()))
                put("cancelled", stop())
            }
            records.appendText("$record\n")
            println(record)
        }
    }

    private fun invoke(source: String, route: Int, enabled: Boolean): JsonObject {
        val stop = Cancellation.after(2.seconds)
        val program = parseFlatZinc(source)
        val original = program.problem
        val preparation = if (route > 0) {
            FinitePipeline.prepare(
                FinitePipelineRequest(
            original,
            FiniteEngine.BACKTRACK,
            cancellation = stop,
            solveCancellation = stop,
            solutionSetSensitive = route == 2,
        )
            )
        } else {
            null
        }
        val problem = preparation?.problem ?: original
        val mapping = if (preparation == null) {
            VarRemap(
            IntArray(original.numBoolVars) { it },
            IntArray(original.numIntVars) { it },
        )
        } else {
            val mappingField = preparation.javaClass.getDeclaredField("sourceMapping")
                .apply { isAccessible = true }
            val captured = mappingField.get(preparation)
            if (captured == null) {
                null
            } else {
                Class.forName("com.eignex.klause.presolve.SourceMappingKt")
                .getDeclaredMethod("retainedCoordinates", captured.javaClass, Cancellation::class.java)
                .invoke(null, captured, stop) as VarRemap?
            }
        }
        val b = mapping?.int(program.intVarsByName.getValue("b")) ?: -1
        val a = mapping?.int(program.intVarsByName.getValue("a")) ?: -1
        val q = mapping?.bool(program.boolVarsByName.getValue("q")) ?: -1
        val codec = ProblemJson()
        val native = PropagationSession(problem, cancellation = stop)
        val state = native.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(native)
        val flag = state.javaClass.getDeclaredField("historicalTableReasons").apply { isAccessible = true }
        flag.setBoolean(state, enabled)
        val traceField = state.javaClass.getDeclaredField("arithmeticLearningTrace")
            .apply { isAccessible = true }
        val trace = Class.forName("com.eignex.klause.propagation.ArithmeticLearningTrace")
            .getConstructor().newInstance()
        traceField.set(state, trace)
        val cp = CpSearchComponent(native)
        val atoms = SearchAtomRegistry(problem.numBoolVars)
        val shared = SearchSession(listOf(cp), cancellation = stop, atoms = atoms)
        val outcomes = ArrayList<String>()
        var target: Int? = null
        var size: Int? = null
        var lbd: Int? = null
        var application: String? = null
        var prefixQ: Boolean? = null
        var siblingQ: Boolean? = null
        val initialized = shared.initialize()
        val initialQ = if (q >= 0) native.boolValue(q) else null
        var resolution: String? = null
        if (b >= 0 && a >= 0 && q >= 0 && initialized is ComponentResult.Consistent) {
            val decisions = listOf(
                SearchDecision.IntAtLeast(b, 1),
                SearchDecision.IntAtLeast(a, 1),
                SearchDecision.Bool(Lit.make(q, true)),
            )
            for (decision in decisions) {
                val result = shared.push(decision)
                outcomes.add(result::class.simpleName ?: "unknown")
                if (result is ComponentResult.Conflict) {
                    val learned = cp.resolveConflict(shared)
                    resolution = learned::class.simpleName
                    if (learned is SearchConflictResolution.Backjump) {
                        target = learned.conflict.decisionLevel
                        size = learned.conflict.guardLiterals.size
                        lbd = learned.conflict.lbd
                        shared.popTo(target)
                        application = learned.conflict.apply(shared)::class.simpleName
                        shared.popTo(1)
                        prefixQ = native.boolValue(q)
                        shared.push(SearchDecision.IntAtMost(a, 0))
                        siblingQ = native.boolValue(q)
                    }
                    break
                }
            }
        }
        val learnedCount = native.learnedClauseCount
        val workGetter = state.javaClass.declaredMethods.single {
            it.name.startsWith("getWork") && it.parameterCount == 0
        }
        val work = workGetter.invoke(state) as Long
        shared.popTo(0)
        flag.setBoolean(state, false)
        traceField.set(state, null)
        return buildJsonObject {
            put("original", codec.encodeToString(original))
            put(
                "prepared",
                buildJsonObject {
                put("bools", problem.numBoolVars)
                put(
                    "ints",
                    buildJsonArray {
                    for (v in 0 until problem.numIntVars) {
                        val domain = problem.intDomainOrNull(v)
                        add(JsonPrimitive(domain.toString()))
                    }
                }
                )
                put(
                    "factors",
                    buildJsonArray {
                    for (factor in problem.factors) {
                        add(
                            try {
                            FactorCodecs.builtin.encodeToJsonElement(factor)
                        } catch (_: MissingFactorCodecException) {
                            check(factor.javaClass.simpleName == "DifferenceSystem")
                            val kind = factor.javaClass
                            val edges = kind.getMethod("getEdges").invoke(factor) as List<*>
                            val edgeFields = listOf("Source", "Target", "Bound", "Guard", "DomainBound")
                            buildJsonObject {
                                put("derivedClass", factor.javaClass.name)
                                val privateVertices = kind.getMethod("getPrivateVertices").invoke(factor)
                                val indexGuards = kind.getMethod("getIndexGuards").invoke(factor)
                                put("privateVertices", privateVertices.toString())
                                put("indexGuards", indexGuards.toString())
                                put(
                                    "edges",
                                    buildJsonArray {
                                    for (edge in edges) {
                                        val value = requireNotNull(edge)
                                        add(
                                            buildJsonObject {
                                            for (name in edgeFields) {
                                                val getter = value.javaClass.getMethod("get$name")
                                                put(name, getter.invoke(value).toString())
                                            }
                                        }
                                        )
                                    }
                                }
                                )
                            }
                        }
                        )
                    }
                }
                )
            }
            )
            put("presolve", preparation?.presolve.toString())
            put("sourceCoordinatesRetained", b >= 0 && a >= 0 && q >= 0)
            put("scriptCoordinates", "a=$a b=$b q=$q")
            put("initialized", initialized::class.simpleName)
            put("initialQ", initialQ)
            put("resolution", resolution)
            put("decisions", outcomes.joinToString())
            put("backjump", target)
            put("lbd", lbd)
            put("learnedSize", size)
            put("application", application)
            put("aFreePrefixQ", prefixQ)
            put("aZeroSiblingQ", siblingQ)
            put("learnedCount", learnedCount)
            put("finiteWork", work)
            put(
                "trace",
                buildJsonArray {
                for (event in trace.javaClass.getMethod("getEvents").invoke(trace) as List<*>) {
                    requireNotNull(event)
                    add(
                        buildJsonObject {
                        put("kind", event.javaClass.getMethod("getKind").invoke(event) as String)
                        put(
                            "data",
                            JsonArray(
                            (event.javaClass.getMethod("getData").invoke(event) as LongArray)
                                .map(::JsonPrimitive),
                        )
                        )
                    }
                    )
                }
            }
            )
            put("cancelled", stop())
        }
    }

    private fun source(variant: Int): String {
        val rows = (0 until 8).map { listOf(0, it / 4, it / 2 % 2, it % 2) } +
            listOf(listOf(1, 0, 0, 1), listOf(1, 1, 1, 0))
        val order = if (variant == 2) listOf(0, 2, 1, 3) else listOf(0, 1, 2, 3)
        val cells = rows.flatMap { row -> order.map { row[it] } }.joinToString()
        val names = order.map { listOf("x", "a", "b", "c")[it] }.joinToString()
        val declarations = if (variant == 0) "var bool: x1; var bool: b0;" else "var bool: d;"
        val aliases = if (variant == 0) {
            "constraint int_eq_reif(x, 1, x1); constraint int_eq_reif(b, 0, b0);"
        } else {
            ""
        }
        val implication = if (variant == 0) {
            "constraint bool_clause([x1,b0], [q]);"
        } else {
            "constraint bool_clause([d], [q]); " + "constraint int_lin_le_imp([1,-1], [b,x], 0, d);"
        }
        return """
            var 0..1: x;
            var 0..1: a;
            var 0..1: b;
            var 0..1: c;
            var bool: q;
            $declarations
            constraint table_int([$names], [$cells]);
            $aliases
            constraint int_eq_imp(c, 1, q);
            $implication
            solve satisfy;
        """.trimIndent()
    }
}
