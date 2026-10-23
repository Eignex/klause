package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
        require(spec.keys == setOf("job", "source", "residual", "sha256", "dense"))
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
        out.resolve("residual.klexact").writeBytes(bytes)
        val arms = if (spec.getValue("dense").jsonPrimitive.content.toBooleanStrict()) listOf(0, 64) else listOf(6)
        for (round in -1..3) {
            val ordered = if (round % 2 == 0) arms.reversed() else arms
            for (denseLimit in ordered) {
                for (retained in listOf(false, true)) {
                    val observed = round == 3
                    val result = measure(state, denseLimit, retained, observed)
                    val report = buildJsonObject {
                        put("revision", Reports.readGitSha())
                        put("input", input)
                        put("warmup", round == -1)
                        put("round", round)
                        put("denseLimit", denseLimit)
                        put("retained", retained)
                        put("observed", observed)
                        put("sourceVerdictClaim", false)
                        put("captureContainsOriginalFactors", false)
                        put("result", result)
                    }
                    out.resolve("round-$round-dense-$denseLimit-retained-$retained.json").writeText(report.toString())
                }
            }
        }
        println("replayed residual ${sha256(bytes)}")
    }

    private fun measure(state: Any, denseLimit: Int, retained: Boolean, observed: Boolean): JsonElement {
        val probe = NumericalResidualProfile.Probe(denseLimit, observed)
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
        fun owner(): Any = Class.forName(ENGINE + "LpScopedSolver").constructors.single { it.parameterCount == 9 }
            .newInstance(state, Cancellation.Never, probe.context, 64, 4096, 50_000_000L, false, Int.MAX_VALUE, pricing)
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
                val prepared = NumericalResidualProfile.call(active, "prepare", stop) as Boolean
                val prepareNs = System.nanoTime() - solveStarted
                val continuation = Class.forName("com.eignex.klause.simplex.exact.ExactContinuationLimits")
                    .getConstructor().newInstance()
                val refinement = Class.forName(ENGINE + "LpRefinementLimits").getConstructor().newInstance()
                val result = if (prepared) {
                    NumericalResidualProfile.call(
                        active, "solve", null, stop, null, continuation, true, observer, refinement, false,
                    )
                } else {
                    null
                }
                val elapsed = System.nanoTime() - solveStarted
                solveNs += elapsed
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
                        NumericalResidualProfile.call(active, "getLastFloatTermination")?.toString(),
                    )
                    put(
                        "metrics",
                        NumericalResidualProfile.json(NumericalResidualProfile.call(active, "getLastMetrics")),
                    )
                    put(
                        "scopeMetrics",
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
            put("pivotLimit", 4096)
            put("workLimit", 50_000_000L)
            put("instrumentation", if (observed) "lab JFR, basis timing and certifier observer" else "lab JFR")
            put("steps", JsonArray(steps))
            put("phaseNs", NumericalResidualProfile.json(probe.phaseNs))
            put("phaseCalls", NumericalResidualProfile.json(probe.phaseCalls))
            put("transformEntries", NumericalResidualProfile.json(probe.transformEntries))
            put("floatSolves", JsonArray(probe.solves))
            put("builds", JsonArray(probe.builds))
            put("certification", JsonArray(observations))
        }
    }
}
