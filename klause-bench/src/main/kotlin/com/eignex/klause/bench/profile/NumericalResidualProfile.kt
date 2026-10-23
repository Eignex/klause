package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.propagation.bake
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
        require(input.keys == setOf("suite", "problem", "sha256"))
        val suite = input.getValue("suite").jsonPrimitive.content
        val name = input.getValue("problem").jsonPrimitive.content
        val reference = Catalog.uncapped(suite).problems.single { it.name == name }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use { it.readNBytes(4 * 1024 * 1024 + 1) }
        require(bytes.size <= 4 * 1024 * 1024 && sha256(bytes) == input.getValue("sha256").jsonPrimitive.content)
        out.resolve("source.mps").writeBytes(bytes)
        val entry = Runners.resolve(reference)
        val objective = requireNotNull(entry.objective)
        val probe = Probe()
        val solver = BacktrackSolver(entry.problem.bake())
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
            put("elapsedNs", elapsed)
            put("rawResult", result.javaClass.simpleName)
            put("objective", result.objectiveValue?.let(::JsonPrimitive) ?: JsonNull)
            put("phaseNs", json(probe.phaseNs))
            put("phaseCalls", json(probe.phaseCalls))
            put("transformEntries", json(probe.transformEntries))
            put("solves", JsonArray(probe.solves))
            put("builds", JsonArray(probe.builds))
            put("captureHashes", JsonArray(captureHashes.map(::JsonPrimitive)))
        }
        out.resolve("capture.json").writeText(report.toString())
        println("captured $suite/$name: ${probe.solves.size} float invocations, ${probe.captures.size} residuals")
    }

    internal class Probe(private val denseLimit: Int = 6) {
        val phaseNs = linkedMapOf<String, Long>()
        val phaseCalls = linkedMapOf<String, Long>()
        val transformEntries = linkedMapOf<String, Long>()
        val solves = ArrayList<JsonElement>()
        val builds = ArrayList<JsonElement>()
        val captures = ArrayList<Any>()
        private var owners = 0
        val context: Any

        init {
            val production = singleton(ENGINE + "ProductionLpEngineFactory")
            val factoryType = Class.forName(ENGINE + "LpEngineFactory")
            val factory = Proxy.newProxyInstance(factoryType.classLoader, arrayOf(factoryType)) { _, method, values ->
                val arguments = values.orEmpty()
                val owner = measured("owner-setup") { invoke(production, method, arguments) }
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
            val basisFactory: (Any) -> Any = { matrix ->
                val type = Class.forName(BASIS + "KotlinBasisSolver")
                val constructor = type.constructors.single { it.parameterCount == 10 }
                val backend = measured("basis-setup") {
                    constructor.newInstance(
                        matrix, null, 0, 0.0, 0.0, false,
                        denseLimit, if (denseLimit == 64) 0.0 else 0.5, 62, null,
                    )
                }
                val pointers = call(matrix, "copyColumnPointers") as IntArray
                val coefficients = call(matrix, "getValues") as DoubleArray
                val basisType = Class.forName(BASIS + "BasisSolver")
                Proxy.newProxyInstance(basisType.classLoader, arrayOf(basisType)) { _, method, values ->
                    val arguments = values.orEmpty()
                    val phase = method.name
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
                    if (phase == "ftran" || phase == "btran") {
                        val work = requireNotNull(call(backend, "getLastSolveWork"))
                        val entries = call(work, "getTransformEntries") as Long
                        transformEntries[phase] = (transformEntries[phase] ?: 0L) + entries
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
                        put("policyMetricsScope", "owner lifetime; replaced on unscaled fallback")
                        put("result", json(result))
                    }
                    val pivots = call(requireNotNull(call(owner, "getLastMetrics")), "getPivots") as Int
                    if (state != null && pivots >= 8 && captures.size < 2) captures += state
                }
                result
            }
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
