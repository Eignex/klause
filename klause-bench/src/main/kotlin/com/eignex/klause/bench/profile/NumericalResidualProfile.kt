package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.IdentityHashMap
import kotlin.time.Duration.Companion.milliseconds

internal object NumericalResidualProfile {
    private const val ENGINE = "com.eignex.klause.lp.engine."
    private const val BASIS = "com.eignex.klause.simplex.basis."

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args.single().startsWith("request64="))
        val request = Base64.getDecoder().decode(args.single().substringAfter('='))
        require(request.size <= 48 * 1024)
        val sources = Reports.json.parseToJsonElement(request.decodeToString()).jsonArray
        require(sources.size in 1..8)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("request.json").writeBytes(request)
        sources.forEachIndexed { index, source ->
            capture(source, out.resolve(index.toString()).apply { mkdirs() })
        }
    }

    private fun capture(source: JsonElement, out: File) {
        val input = source.jsonObject
        if (input.keys == setOf("control")) {
            require(input.getValue("control").jsonPrimitive.content == "near-dependent-40")
            captureConditioningControl(source, out)
            return
        }
        val supplied = input["mps64"]?.jsonPrimitive?.content
        val catalogKeys = setOf("suite", "problem", "sha256")
        val suppliedKeys = setOf("mps64", "problem", "sha256", "root")
        require(input.keys == catalogKeys || input.keys == catalogKeys + "root" ||
            input.keys == catalogKeys + setOf("root", "unbaked") ||
            input.keys == suppliedKeys || input.keys == suppliedKeys + "exact")
        val unbaked = input["unbaked"]?.jsonPrimitive?.content?.toBooleanStrict() == true
        require(!unbaked || input["root"]?.jsonPrimitive?.content?.toBooleanStrict() == true)
        val suite = input["suite"]?.jsonPrimitive?.content ?: "self-authored"
        val name = input.getValue("problem").jsonPrimitive.content
        val reference = if (supplied == null) Catalog.uncapped(suite).problems.single { it.name == name } else null
        require(reference == null || reference.format == Format.MPS)
        val bytes = if (supplied == null) {
            CorpusFiles.open(CorpusFetcher.resolve(requireNotNull(reference).source)).use {
                it.readNBytes(4 * 1024 * 1024 + 1)
            }
        } else {
            Base64.getDecoder().decode(supplied)
        }
        require(bytes.size <= 4 * 1024 * 1024 && sha256(bytes) == input.getValue("sha256").jsonPrimitive.content)
        out.resolve("source.mps").writeBytes(bytes)
        val entry = reference?.takeUnless { unbaked }?.let { Runners.resolve(it) }
        val overrides = if (input["exact"]?.jsonPrimitive?.content?.toBooleanStrict() == true) {
            SemanticOverrides(feasibilityTolerance = SourceTolerance())
        } else SemanticOverrides()
        val compiled = if (entry == null) {
            Mps.parse(bytes.decodeToString()).toProblem(semanticOverrides = overrides)
        } else null
        val problem = entry?.problem ?: requireNotNull(compiled).model
        val sourceObjective = entry?.objective ?: compiled?.objective?.toLinearObjective()?.let {
            if (compiled.maximize) it.negated() else it
        }
        if (input["root"]?.jsonPrimitive?.content?.toBooleanStrict() == true) {
            captureRoot(problem, sourceObjective, source, out)
            return
        }
        val objective = requireNotNull(sourceObjective)
        val probe = Probe()
        val solver = BacktrackSolver(problem.bake())
        set(solver, "lpSolveContext", probe.context)
        val stop = Cancellation.after(2000.milliseconds)
        val params = BacktrackPresets.satOptimized(randomSeed = 3, cancellation = stop)
        val started = System.nanoTime()
        val result = solver.minimize(objective, params)
        val elapsed = System.nanoTime() - started
        val captureHashes = probe.captures.mapIndexed { index, state ->
            val capturedBytes = encode(state)
            out.resolve("residual-$index.klexact").writeBytes(capturedBytes)
            sha256(capturedBytes)
        }
        val report = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("source", source)
            put("entry", "direct satOptimized worker, seed3, 2s, default exact leaf acceptance; diagnostic")
            put("sourceVerdictClaim", false)
            put("cliRoutingEquivalence", false)
            put("processors", Runtime.getRuntime().availableProcessors())
            put("runtimeDebug", System.getenv("KLAUSE_DEBUG"))
            put("syntheticWorkEnabled", probe.syntheticWorkEnabled)
            put("elapsedNs", elapsed)
            put("rawResult", result.javaClass.simpleName)
            put("objective", result.objectiveValue?.let(::JsonPrimitive) ?: JsonNull)
            put("phaseNs", json(probe.phaseNs))
            put("phaseCalls", json(probe.phaseCalls))
            put("transformEntries", json(probe.transformEntries))
            put("basisSolveWork", json(probe.basisSolveWork))
            put("minimumPivotSpread", probe.minimumPivotSpread?.let(::JsonPrimitive) ?: JsonNull)
            put("solves", JsonArray(probe.solves))
            put("builds", JsonArray(probe.builds))
            put("captureHashes", JsonArray(captureHashes.map(::JsonPrimitive)))
        }
        out.resolve("capture.json").writeText(report.toString())
        println("captured $suite/$name: ${probe.solves.size} float invocations, ${probe.captures.size} residuals")
    }

    private fun captureRoot(problem: Problem, objective: LinearObjective?, source: JsonElement, out: File) {
        val prefix = "com.eignex.klause.lp.relaxation."
        val type = Class.forName(prefix + "CpToLpRelaxation")
        val constructor = type.constructors.single {
            it.parameterTypes.last().simpleName == "DefaultConstructorMarker"
        }
        val count = constructor.parameterCount - 2
        require(count in 3..31)
        val arguments = arrayOfNulls<Any>(constructor.parameterCount)
        arguments[0] = problem
        arguments[1] = objective
        for (index in 2 until count) {
            if (constructor.parameterTypes[index] == Boolean::class.javaPrimitiveType) arguments[index] = false
        }
        arguments[count] = -4
        @Suppress("SpreadOperator")
        val builder = constructor.newInstance(*arguments)
        val stop = Cancellation.after(2000.milliseconds)
        val domains = PropagationSession(problem, cancellation = stop)
        val build = type.methods.single {
            it.name == "build" && it.parameterCount == 3 && it.parameterTypes.first().isInstance(domains)
        }
        val relaxation = requireNotNull(build.invoke(builder, domains, emptyList<Any>(), stop))
        val model = requireNotNull(call(relaxation, "getModel"))
        captureModel(
            model, source, out, "diagnostic root relaxation after propagation; no traversal or optional presolve",
        )
    }

    private fun captureConditioningControl(source: JsonElement, out: File) {
        val builder = Class.forName(ENGINE + "LpBuilder").getConstructor().newInstance()
        val equal = Class.forName(ENGINE + "Relation").enumConstants.single { it.toString() == "EQ" }
        repeat(20) {
            val x = call(builder, "addRealVar", 0.0, 2.0, 1.0, -1) as Int
            val y = call(builder, "addRealVar", 0.0, 2.0, 1.0, -1) as Int
            call(
                builder, "addRow", intArrayOf(x, y), longArrayOf(1_000_000L, 1_000_000L), equal,
                2_000_000L, true, null,
            )
            call(
                builder, "addRow", intArrayOf(x, y), longArrayOf(1_000_000L, 1_000_001L), equal,
                2_000_001L, true, null,
            )
        }
        val sense = Class.forName(ENGINE + "Sense").enumConstants.single { it.toString() == "MINIMIZE" }
        val model = requireNotNull(call(builder, "build", sense))
        captureModel(model, source, out, "self-authored exact LP model control; no MPS compilation or source verdict")
    }

    private fun captureModel(model: Any, source: JsonElement, out: File, entry: String) {
        val authority = Class.forName(ENGINE + "LegacyLpModelKt").methods.single {
            it.name == "authoritativeModel" && it.parameterCount == 1
        }
        val exact = requireNotNull(authority.invoke(null, model))
        val trail = Class.forName(ENGINE + "LpBoundTrail").constructors.single {
            it.parameterCount == 1 && it.parameterTypes.single().isInstance(exact)
        }.newInstance(exact)
        val state = requireNotNull(call(trail, "getState"))
        val bytes = encode(state)
        out.resolve("residual-0.klexact").writeBytes(bytes)
        val report = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("source", source)
            put("entry", entry)
            put("sourceVerdictClaim", false)
            put("cliRoutingEquivalence", false)
            put("captureHashes", JsonArray(listOf(JsonPrimitive(sha256(bytes)))))
            put("shape", shape(state))
        }
        out.resolve("capture.json").writeText(report.toString())
    }

    internal class Probe(
        private val denseLimit: Int = 6,
        private val observed: Boolean = true,
        val syntheticWorkEnabled: Boolean = false,
        private val auditBasis: Boolean = false,
    ) {
        val phaseNs = linkedMapOf<String, Long>()
        val phaseCalls = linkedMapOf<String, Long>()
        val transformEntries = linkedMapOf<String, Long>()
        val basisSolveWork = linkedMapOf<String, MutableMap<String, Long>>()
        var minimumPivotSpread: Double? = null
            private set
        val solves = ArrayList<JsonElement>()
        val builds = ArrayList<JsonElement>()
        val captures = ArrayList<Any>()
        val basisResiduals = ArrayList<JsonElement>()
        val basisSlices = ArrayList<JsonElement>()
        private var owners = 0
        private val originals = IdentityHashMap<Any, Any>()
        val context: Any

        fun unwrapped(owner: Any): Any = originals[owner] ?: owner

        init {
            val production = singleton(ENGINE + "ProductionLpEngineFactory")
            val factoryType = Class.forName(ENGINE + "LpEngineFactory")
            val factory = Proxy.newProxyInstance(factoryType.classLoader, arrayOf(factoryType)) { _, method, values ->
                val arguments = values.orEmpty()
                val owner = if (observed) {
                    measured("owner-setup") { invoke(production, method, arguments) }
                } else {
                    invoke(production, method, arguments)
                }
                val numericalOwner = owner != null && method.name.startsWith("new") &&
                    owner.javaClass.simpleName.startsWith("RevisedSimplex")
                if (!numericalOwner) {
                    owner
                } else {
                    wrapEngine(requireNotNull(owner), owners++)
                }
            }
            context = Class.forName(ENGINE + "LpSolveContext").getConstructor().newInstance()
            set(context, "engineFactory", factory)
        }

        private fun wrapEngine(owner: Any, id: Int): Any {
            if (observed) call(owner, "enableProgressTrace", true)
            set(owner, "syntheticWorkEnabled", syntheticWorkEnabled)
            set(field(field(owner, "refactorPolicy"), "config"), "syntheticWorkEnabled", syntheticWorkEnabled)
            val basisFactory: (Any) -> Any = letBackend@{ matrix ->
                val type = Class.forName(BASIS + "KotlinBasisSolver")
                val constructor = type.constructors.single { it.parameterCount == 10 }
                val backend = measured("basis-setup") {
                    constructor.newInstance(
                        matrix, null, 0, 0.0, 0.0, false,
                        denseLimit, if (denseLimit == 64) 0.0 else 0.5, 62, null,
                    )
                }
                if (!observed) return@letBackend backend
                val pointers = call(matrix, "copyColumnPointers") as IntArray
                val coefficients = call(matrix, "getValues") as DoubleArray
                val basisType = Class.forName(BASIS + "BasisSolver")
                Proxy.newProxyInstance(basisType.classLoader, arrayOf(basisType)) { _, method, values ->
                    val arguments = values.orEmpty()
                    val phase = method.name
                    val transform = phase == "ftran" || phase == "btran"
                    val inputSupport = if (transform) call(requireNotNull(arguments[0]), "getCount") as Int else 0
                    val rhs = if (transform && auditBasis && basisResiduals.size < 128) {
                        call(requireNotNull(arguments[0]), "toDoubleArray") as DoubleArray
                    } else null
                    val observation = if (phase == "refactorize" && builds.size < 4096) {
                        buildJsonObject {
                            put("owner", id)
                            put("dimension", call(backend, "getN") as Int)
                            put("updates", call(backend, "getUpdateCount") as Int)
                            val headings = arguments.single() as IntArray
                            var entries = 0
                            for (column in headings) {
                                for (entry in pointers[column] until pointers[column + 1]) {
                                    if (coefficients[entry] != 0.0) entries++
                                }
                            }
                            put("basisEntries", entries)
                            put("basisDensity", entries.toDouble() / (headings.size.toDouble() * headings.size))
                            put("policy", json(call(owner, "getLastRefactorPolicyMetrics")))
                            put("backendWorkBefore", json(call(backend, "getBasisWork")))
                        }
                    } else {
                        null
                    }
                    val timed = setOf(
                        "refactorize", "ftran", "btran", "update", "close", "refactorizeRepairing", "solveQuality",
                    )
                    val result = if (phase in timed) {
                        measured(phase) { invoke(backend, method, arguments) }
                    } else {
                        invoke(backend, method, arguments)
                    }
                    if (transform) {
                        val work = requireNotNull(call(backend, "getLastSolveWork"))
                        val entries = call(work, "getTransformEntries") as Long
                        transformEntries[phase] = (transformEntries[phase] ?: 0L) + entries
                        recordSolveWork(phase, arguments, inputSupport, work)
                        if (rhs != null) {
                            basisResiduals += buildJsonObject {
                                put("phase", phase)
                                put("updates", call(backend, "getUpdateCount") as Int)
                                put("quality", json(call(backend, "solveQuality", rhs, arguments[0], phase == "btran")))
                            }
                        }
                    }
                    if (auditBasis && ((phase == "refactorize" && result == true) ||
                        (phase == "update" && result.toString() != "SINGULAR"))) recordBasisSlices(backend, phase)
                    if (phase == "update" && result.toString() != "SINGULAR") {
                        val spread = call(backend, "getRcond") as Double
                        if (spread.isFinite() && spread >= 0.0) {
                            minimumPivotSpread = minimumPivotSpread?.let { minOf(it, spread) } ?: spread
                        }
                    }
                    if (observation != null) {
                        builds += buildJsonObject {
                            observation.forEach { (key, value) -> put(key, value) }
                            put("accepted", result as Boolean)
                            put("factorNnz", call(backend, "getNnz") as Int)
                            put("kernel", json(call(backend, "getKernel")))
                            put("backendWorkAfter", json(call(backend, "getBasisWork")))
                        }
                    }
                    result
                }
            }
            set(owner, "basisSolverFactory", basisFactory)
            if (!observed) return owner
            val interfaces = owner.javaClass.interfaces
            return Proxy.newProxyInstance(owner.javaClass.classLoader, interfaces) { _, method, values ->
                val operation = method.name.substringBefore('$')
                val isSolve = operation in setOf("solve", "resolveBounds", "resolveGated", "solvePrimal")
                val state = if (isSolve) call(field(owner, "model"), "getExactState") else null
                val started = System.nanoTime()
                val result = invoke(owner, method, values.orEmpty())
                if (isSolve && solves.size < 256) {
                    solves += buildJsonObject {
                        put("owner", id)
                        put("operation", operation)
                        put("elapsedNs", System.nanoTime() - started)
                        put("metrics", json(call(owner, "getLastMetrics")))
                        put("policy", json(call(owner, "getLastRefactorPolicyMetrics")))
                        put("progress", json(call(owner, "getProgressTrace")))
                        put("policyMetricsScope", "owner lifetime; replaced on unscaled fallback")
                        put("result", json(result))
                    }
                    val pivots = call(requireNotNull(call(owner, "getLastMetrics")), "getPivots") as Int
                    if (state != null && pivots >= 8 && captures.size < 2) captures += state
                }
                result
            }.also { originals[it] = owner }
        }

        private fun <T> measured(phase: String, block: () -> T): T {
            val start = System.nanoTime()
            try {
                return block()
            } finally {
                phaseNs[phase] = (phaseNs[phase] ?: 0L) + System.nanoTime() - start
                phaseCalls[phase] = (phaseCalls[phase] ?: 0L) + 1L
            }
        }

        private fun recordBasisSlices(backend: Any, phase: String) {
            if (basisSlices.size >= 16) return
            val cache = field(backend, "cache")
            val upper = requireNotNull(call(requireNotNull(call(cache, "getFt")), "getUpper"))
            val columns = call(upper, "getColumns") as Array<*>
            if (columns.isEmpty()) return
            for (j in listOf(0, columns.size / 3, 2 * (columns.size / 3), columns.lastIndex).distinct()) {
                if (basisSlices.size >= 16) break
                val slice = requireNotNull(columns[j])
                val indices = call(slice, "getIndices") as IntArray
                val values = call(slice, "getValues") as DoubleArray
                val offset = call(slice, "getOffset") as Int
                val count = call(slice, "getCount") as Int
                basisSlices += buildJsonObject {
                    put("phase", phase)
                    put("updates", call(backend, "getUpdateCount") as Int)
                    put("dimension", columns.size)
                    put("column", j)
                    put("offset", offset)
                    put("count", count)
                    put("sampleCount", minOf(count, 64))
                    put("indices", json(indices.copyOfRange(offset, offset + minOf(count, 64))))
                    put("values", json(values.copyOfRange(offset, offset + minOf(count, 64))))
                    put("indicesInBounds", (offset until offset + count).all { indices[it] in columns.indices })
                    put("strictlySorted", (offset + 1 until offset + count).all { indices[it - 1] < indices[it] })
                    put("finite", (offset until offset + count).all { values[it].isFinite() })
                }
            }
        }

        private fun recordSolveWork(phase: String, arguments: Array<out Any?>, inputSupport: Int, work: Any) {
            val hint = if (arguments[1] == 1.0) "dense-hint" else "adaptive-hint"
            val counters = basisSolveWork.getOrPut("$phase:$hint") { linkedMapOf() }
            fun add(key: String, value: Long) {
                counters[key] = (counters[key] ?: 0L) + value
            }
            val size = call(requireNotNull(arguments[0]), "getSize") as Int
            add("calls", 1L)
            add("dimensions", size.toLong())
            add("inputSupport", inputSupport.toLong())
            add("inputAtMostTwentyPercent", if (inputSupport <= size * 0.2) 1L else 0L)
            add("outputSupport", (call(work, "getOutputSupport") as Int).toLong())
            for (part in listOf("First", "Second")) {
                val triangular = requireNotNull(call(work, "get$part"))
                val prefix = part.lowercase()
                add("${prefix}SparseCalls", if (call(triangular, "getSparse") == true) 1L else 0L)
                add("${prefix}PivotVisits", (call(triangular, "getPivotVisits") as Int).toLong())
                add("${prefix}ArithmeticEntries", call(triangular, "getArithmeticEntries") as Long)
                add("${prefix}ReachEntries", call(triangular, "getReachEntries") as Long)
            }
        }
    }

    internal fun shape(state: Any): JsonElement {
        val inspector = ExactFeasibilityCapture.javaClass.getDeclaredMethod("shape", Any::class.java)
            .apply { isAccessible = true }
        return inspector.invoke(ExactFeasibilityCapture, state) as JsonElement
    }

    private fun encode(state: Any): ByteArray {
        val kind = Class.forName(ENGINE + "LpReplaySolverKind").enumConstants.single { it.toString() == "PERSISTENT" }
        val settings = Class.forName(ENGINE + "LpReplaySettings").constructors.single { it.parameterCount == 9 }
            .newInstance("numerical-residual", 3L, kind, false, 0, 0, 0L, 64, false)
        val solve = Class.forName(ENGINE + "LpExactReplayEvent\$Solve").getConstructor().newInstance()
        val companion = Class.forName(ENGINE + "LpExactCapture").getField("Companion").get(null)
        val capture = requireNotNull(call(companion, "capture", state, settings, listOf(solve), Int.MAX_VALUE))
        val bytes = call(capture, "encode") as ByteArray
        val decoded = requireNotNull(call(companion, "decode", bytes))
        check(bytes.contentEquals(call(decoded, "encode") as ByteArray))
        return bytes
    }

    private fun singleton(name: String): Any = Class.forName(name).getField("INSTANCE").get(null)

    private fun field(owner: Any, name: String): Any = requireNotNull(
        owner.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }.get(owner),
    )

    private fun set(owner: Any, name: String, value: Any) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }

    internal fun call(owner: Any, name: String, vararg arguments: Any?): Any? {
        val candidates = owner.javaClass.methods.filter {
            it.name.substringBefore('$') == name && it.parameterCount == arguments.size
        }
        val method = candidates.singleOrNull() ?: error("missing or ambiguous ${owner.javaClass.name}.$name")
        return invoke(owner, method, arguments)
    }

    @Suppress("SpreadOperator")
    private fun invoke(owner: Any, method: Method, arguments: Array<out Any?>): Any? = try {
        method.invoke(owner, *arguments)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }

    internal fun json(value: Any?, depth: Int = 0): JsonElement = when {
        value == null -> JsonNull

        value is Number -> JsonPrimitive(value)

        value is Boolean -> JsonPrimitive(value)

        value is String || value is Enum<*> -> JsonPrimitive(value.toString())

        depth >= 4 -> JsonPrimitive(value.toString())

        value is Map<*, *> -> buildJsonObject {
            value.forEach { (key, item) -> put(key.toString(), json(item, depth + 1)) }
        }

        value is Iterable<*> -> JsonArray(value.take(256).map { json(it, depth + 1) })

        value is IntArray -> JsonArray(value.take(256).map(::JsonPrimitive))

        value is DoubleArray -> JsonArray(value.take(256).map(::JsonPrimitive))

        else -> buildJsonObject {
            value.javaClass.methods.filter {
                it.name.startsWith("get") && it.parameterCount == 0 && it.name != "getClass"
            }.sortedBy { it.name }.forEach { getter ->
                put(
                    getter.name.removePrefix("get").substringBefore('$').replaceFirstChar { it.lowercase() },
                    json(invoke(value, getter, emptyArray()), depth + 1),
                )
            }
        }
    }
}
