package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
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
import java.lang.reflect.Proxy
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds

internal object NumericalResidualReplay {
    private const val ENGINE = "com.eignex.klause.lp.engine."

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args.single().startsWith("request64="))
        val request = Base64.getDecoder().decode(args.single().substringAfter('='))
        require(request.size <= 48 * 1024)
        val captures = Reports.json.parseToJsonElement(request.decodeToString()).jsonArray
        require(captures.size in 1..8)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("request.json").writeBytes(request)
        captures.forEachIndexed { index, capture ->
            replay(capture, out.resolve(index.toString()).apply { mkdirs() })
        }
    }

    private fun replay(input: JsonElement, out: File) {
        val spec = input.jsonObject
        val keys = setOf("job", "source", "residual", "sha256", "dense")
        require(spec.keys.containsAll(keys) && (spec.keys - keys).all { it in setOf("synthetic", "entry") })
        val entry = spec["entry"]?.jsonPrimitive?.content ?: "scoped"
        require(entry == "scoped" || entry == "session")
        val compareSynthetic = spec["synthetic"]?.jsonPrimitive?.content?.also { require(it == "compare") } != null
        val job = spec.getValue("job").jsonPrimitive.content.toLong().also { require(it > 0L) }
        val source = spec.getValue("source").jsonPrimitive.content.toInt().also { require(it in 0..7) }
        val residual = spec.getValue("residual").jsonPrimitive.content.toInt().also { require(it in 0..1) }
        val jobs = File(requireNotNull(System.getenv("JOB_DIR"))).canonicalFile.parentFile
        val path = jobs.resolve("$job/cases/0/out/$source/residual-$residual.klexact").canonicalFile
        require(path.toPath().startsWith(jobs.toPath()) && path.length() in 1..16L * 1024 * 1024)
        val bytes = path.readBytes()
        require(sha256(bytes) == spec.getValue("sha256").jsonPrimitive.content)
        val companion = Class.forName(ENGINE + "LpExactCapture").getField("Companion").get(null)
        val capture = requireNotNull(NumericalResidualProfile.call(companion, "decode", bytes))
        val state = requireNotNull(NumericalResidualProfile.call(capture, "getInitialState"))
        out.resolve("shape.json").writeText(NumericalResidualProfile.shape(state).toString())
        out.resolve("residual.klexact").writeBytes(bytes)
        val dense = spec.getValue("dense").jsonPrimitive.content.toBooleanStrict()
        require(!dense || !compareSynthetic)
        val arms = when {
            dense -> listOf(0 to true, 64 to true)
            compareSynthetic -> listOf(6 to true, 6 to false)
            else -> listOf(6 to true)
        }
        for (round in -1..3) {
            val ordered = if (round % 2 == 0) arms.reversed() else arms
            for ((denseLimit, syntheticWorkEnabled) in ordered) {
                for (retained in listOf(false, true)) {
                    val observed = round == 3
                    val result = measure(state, denseLimit, retained, observed, syntheticWorkEnabled, entry)
                    val report = buildJsonObject {
                        put("revision", Reports.readGitSha())
                        put("input", input)
                        put("warmup", round == -1)
                        put("round", round)
                        put("denseLimit", denseLimit)
                        put("retained", retained)
                        put("observed", observed)
                        put("syntheticWorkEnabled", syntheticWorkEnabled)
                        put("entry", entry)
                        put("sourceVerdictClaim", false)
                        put("captureContainsOriginalFactors", false)
                        put("result", result)
                    }
                    val filename = "round-$round-dense-$denseLimit-retained-$retained-" +
                        "synthetic-$syntheticWorkEnabled.json"
                    out.resolve(filename).writeText(report.toString())
                }
            }
        }
        println("replayed residual ${sha256(bytes)}")
    }

    private fun measure(
        state: Any,
        denseLimit: Int,
        retained: Boolean,
        observed: Boolean,
        syntheticWorkEnabled: Boolean,
        entry: String,
    ): JsonElement {
        val probe = NumericalResidualProfile.Probe(denseLimit, observed, syntheticWorkEnabled)
        val observerType = Class.forName(ENGINE + "LpCertificationObserver")
        val observations = ArrayList<JsonElement>()
        val recording = Proxy.newProxyInstance(observerType.classLoader, arrayOf(observerType)) { _, method, values ->
            if (observations.size < 256) {
                observations += buildJsonObject {
                    put("method", method.name)
                    put("values", JsonArray(values.orEmpty().map { NumericalResidualProfile.json(it) }))
                }
            }
            null
        }
        val observer = recording.takeIf { observed }
        val pricing = Class.forName(ENGINE + "LpPricingOptions").constructors.single { it.parameterCount == 2 }
            .newInstance(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 3L)
        val session = entry == "session"
        val ownerType = Class.forName(ENGINE + if (session) "LpSolveSession" else "LpScopedSolver")
        val constructor = ownerType.constructors.single { it.parameterCount == if (session) 3 else 9 }
        val prepare = if (session) null else ownerType.methods.single {
            it.name == "prepare" && it.parameterCount == 1
        }
        val defaultSolve = ownerType.methods.single { it.name == "solve\$default" && it.parameterCount == 11 }
        val solverField = if (session) ownerType.getDeclaredField("solver").apply { isAccessible = true } else null
        val model = if (session) {
            val meter = Class.forName(ENGINE + "LpProjectionMeter").getConstructor().newInstance()
            requireNotNull(NumericalResidualProfile.call(state, "toWorkingModel", meter))
        } else null
        fun owner(): Any = if (session) {
            constructor.newInstance(probe.context, pricing, false)
        } else {
            constructor.newInstance(
                state, Cancellation.Never, probe.context, 64, 4096, 1_200_000_000L, false, Int.MAX_VALUE, pricing,
            )
        }
        val steps = ArrayList<JsonElement>()
        var current: Any? = null
        var setupNs = 0L
        var solveNs = 0L
        var closeNs = 0L
        try {
            repeat(2) { index ->
                if (current == null) {
                    val started = System.nanoTime()
                    current = owner()
                    setupNs += System.nanoTime() - started
                }
                val active = requireNotNull(current)
                val stop = Cancellation.after(2000.milliseconds)
                val solveStarted = System.nanoTime()
                val prepared = prepare?.invoke(active, stop) as? Boolean ?: true
                val prepareNs = System.nanoTime() - solveStarted
                val result = if (prepared) {
                    if (session) {
                        defaultSolve.invoke(
                            null, active, model, stop, 1_200_000_000L, observer, null, null, 0.0, null, 240, null,
                        )
                    } else {
                        defaultSolve.invoke(
                            null, active, null, stop, null, null, true, observer, null, false, 221, null,
                        )
                    }
                } else {
                    null
                }
                val elapsed = System.nanoTime() - solveStarted
                solveNs += elapsed
                val metricsOwner = if (session) solverField?.get(active)?.let { probe.unwrapped(it) } else active
                steps += buildJsonObject {
                    put("step", index)
                    put("prepared", prepared)
                    put("prepareNs", prepareNs)
                    put("elapsedNs", elapsed)
                    put("cancelled", stop())
                    put("verdict", result?.let { NumericalResidualProfile.call(it, "getVerdict").toString() })
                    put("bound", result?.let { NumericalResidualProfile.call(it, "getLowerBound")?.toString() })
                    put("point", result?.let { NumericalResidualProfile.call(it, "getExactPrimal")?.toString() })
                    put(
                        "floatTermination",
                        metricsOwner?.let {
                            val getter = if (session) "getLastTermination" else "getLastFloatTermination"
                            NumericalResidualProfile.call(it, getter)
                        }?.toString(),
                    )
                    put(
                        "metrics",
                        NumericalResidualProfile.json(metricsOwner?.let {
                            NumericalResidualProfile.call(it, "getLastMetrics")
                        }),
                    )
                    put(
                        "scopeMetrics",
                        if (session) JsonNull else
                            NumericalResidualProfile.json(NumericalResidualProfile.call(active, "getMetrics")),
                    )
                }
                if (!retained) {
                    val closeStarted = System.nanoTime()
                    (active as AutoCloseable).close()
                    closeNs += System.nanoTime() - closeStarted
                    current = null
                }
            }
        } finally {
            val closeStarted = System.nanoTime()
            (current as? AutoCloseable)?.close()
            closeNs += System.nanoTime() - closeStarted
        }
        return buildJsonObject {
            put("elapsedNs", setupNs + solveNs + closeNs)
            put("setupNs", setupNs)
            put("solveNs", solveNs)
            put("closeNs", closeNs)
            put("processors", Runtime.getRuntime().availableProcessors())
            put("tieSeed", 3)
            put("perSolveTimeoutMs", 2000)
            put("pivotLimit", if (session) 0 else 4096)
            put("inputProjectionExcluded", session)
            put("workLimit", 1_200_000_000L)
            put("instrumentation", if (observed) "lab JFR, basis timing and certifier observer" else "lab JFR")
            put("steps", JsonArray(steps))
            put("phaseNs", NumericalResidualProfile.json(probe.phaseNs))
            put("phaseCalls", NumericalResidualProfile.json(probe.phaseCalls))
            put("transformEntries", NumericalResidualProfile.json(probe.transformEntries))
            put("minimumPivotSpread", probe.minimumPivotSpread?.let(::JsonPrimitive) ?: JsonNull)
            put("floatSolves", JsonArray(probe.solves))
            put("builds", JsonArray(probe.builds))
            put("certification", JsonArray(observations))
        }
    }
}
