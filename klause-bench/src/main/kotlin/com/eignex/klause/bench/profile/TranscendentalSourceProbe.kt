package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PolynomialStrategyParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object TranscendentalSourceProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("sources", "controls", "seeds", "repeats", "work", "timeout", "cancel-work") })
        val sources = Json.parseToJsonElement(requireNotNull(options["sources"])).jsonArray
        val controls = (options["controls"] ?: "BACKTRACK:1,FIXED:4,LOCAL_SEARCH:1,ALNS:1,MIXED:1,MIXED:4").split(',')
        val seeds = (options["seeds"] ?: "1,7").split(',').map(String::toLong)
        val repeats = (options["repeats"] ?: "3").toInt().also { require(it > 0) }
        val maximumWork = (options["work"] ?: "100000").toLong().also { require(it >= 0) }
        val timeout = (options["timeout"] ?: "10000").toLong().also { require(it >= 0) }
        val cancelWork = options["cancel-work"]?.toLong()?.also { require(it >= 0) }
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        val captured = buildJsonArray {
            sources.forEach { entry ->
                val source = entry.jsonObject
                require(source.keys == setOf("name", "text"))
                val text = source.getValue("text").jsonPrimitive.content
                add(buildJsonObject {
                    put("name", source.getValue("name"))
                    put("text", text)
                    put("sha256", hash(text.toByteArray()))
                })
            }
        }
        out.resolve("sources.json").writeText(captured.toString())
        val results = buildJsonArray {
            for (entry in captured) {
                val source = entry.jsonObject
                val problem = (SmtLibAdmission.parse(source.getValue("text").jsonPrimitive.content)
                    as SmtLibInput.Transcendental).problem
                for (seed in seeds) repeat(repeats) { repetition ->
                    for (control in if (repetition % 2 == 0) controls else controls.reversed()) {
                        val engine = FiniteEngine.valueOf(control.substringBefore(':'))
                        val cores = control.substringAfter(':').toInt().also { require(it > 0) }
                        val charged = AtomicLong()
                        val cancellation = object : Cancellation {
                            override fun isCancelled(): Boolean = cancelWork?.let { charged.get() >= it } == true
                            override fun workMeter(): WorkMeter = WorkMeter { charged.addAndGet(it) }
                        }
                        val params = PolynomialTheoryParams(maximumWork = maximumWork,
                            timeout = Cancellation.after(timeout.milliseconds), cancellation = cancellation,
                            strategy = PolynomialStrategyParams(backtrack = BacktrackParams(randomSeed = seed), localSearch = LocalSearchParams(
                                randomSeed = seed, maxFlips = 16, maxInstructions = maximumWork), alnsIterations = 16))
                        val started = TimeSource.Monotonic.markNow()
                        val execution = problem.pipelineRequest().execute(params, engine, cores)
                        val elapsed = started.elapsedNow().inWholeNanoseconds
                        val result = execution.result
                        add(buildJsonObject {
                            put("name", source.getValue("name"))
                            put("sourceSha256", source.getValue("sha256"))
                            put("control", control)
                            put("seed", seed)
                            put("repetition", repetition)
                            put("maximumWork", maximumWork)
                            put("timeoutMillis", timeout)
                            put("cancelWork", cancelWork)
                            put("elapsedNanos", elapsed)
                            put("kind", result::class.simpleName)
                            put("reason", (result as? OpenTheoryResult.Unknown)?.reason?.name)
                            put("nativeScope", execution.nativeWorkScope)
                            put("nativeResource", execution.nativeResource)
                            put("nativeWork", execution.stats.workSpent)
                            put("chargedWork", charged.get())
                            put("nodes", result.stats.search.nodes.sum)
                            put("moves", result.stats.ls.moves.sum)
                            put("arms", buildJsonArray {
                                result.stats.portfolio.arms.forEach { arm -> add(buildJsonObject {
                                    put("label", arm.label)
                                    put("segments", arm.segments)
                                    put("work", arm.work)
                                    put("millis", arm.millis)
                                    put("faults", arm.faults)
                                    put("failures", arm.failures)
                                }) }
                            })
                            if (result is OpenTheoryResult.Sat) put("witness", buildJsonObject {
                                problem.boolVarNames.forEach { (name, id) -> put(name, result.assignment.boolValue(id)) }
                                problem.intVarNames.forEach { (name, id) -> put(name, result.assignment.intValue(id)) }
                                problem.realVarNames.forEach { (name, id) -> put(name, result.assignment.realValue(id)) }
                            })
                        })
                    }
                }
            }
        }
        val resultFile = out.resolve("results.json").also { it.writeText(results.toString()) }
        out.resolve("payloads.sha256").writeText("${hash(out.resolve("sources.json").readBytes())}  sources.json\n" +
            "${hash(resultFile.readBytes())}  results.json\n")
        out.resolve("runtime.json").writeText(buildJsonObject {
            put("java", System.getProperty("java.runtime.version"))
            put("processors", Runtime.getRuntime().availableProcessors())
            put("os", System.getProperty("os.name"))
            put("arch", System.getProperty("os.arch"))
        }.toString())
        println("Recorded ${results.size} source executions")
    }

    private fun hash(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
