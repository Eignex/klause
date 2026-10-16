package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URLClassLoader
import java.util.Base64

internal object LinearStopAttributionProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args[0].startsWith("request64="))
        val request = Base64.getDecoder().decode(args[0].substringAfter('='))
        require(request.size <= 32 * 1024)
        val sources = Json.decodeFromString<List<LinearStopSource>>(request.decodeToString())
        require(sources.size in 1..8)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        if (System.getenv("KLAUSE_LINEAR_STOP_CHILD") != "1") {
            LinearStopProcess.launch(args, out)
            return
        }
        require(Runtime.getRuntime().availableProcessors() == 1)
        out.resolve("request.json").writeBytes(request)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("runtime.json").writeText(
            buildJsonObject {
            put("revision", Reports.readGitSha())
            put("javaVersion", System.getProperty("java.version"))
            put("processors", Runtime.getRuntime().availableProcessors())
            put(
                "jvmOptions",
                buildJsonArray {
                ManagementFactory.getRuntimeMXBean().inputArguments.forEach { add(JsonPrimitive(it)) }
            }
            )
            put("entryPoint", "installed-cli-frontend-and-public-pipeline")
            put("cliDriverExecuted", false)
            put("timeoutMs", 60000)
            put("nativeLimitsChanged", false)
        }.toString() + "\n"
        )
        val jars = SolverInvocation.klauseCliBin().canonicalFile.parentFile.parentFile.resolve("lib")
            .listFiles().orEmpty().filter { it.extension == "jar" }.sortedBy { it.name }
        require(jars.isNotEmpty())
        val loaded = File(Problem::class.java.protectionDomain.codeSource.location.toURI())
        val installed = jars.single { it.name == loaded.name }
        require(sha256(loaded.readBytes()) == sha256(installed.readBytes())) {
            "harness and installed CLI load different solver bytes"
        }
        out.resolve("loaded-solver.json").writeText(
            buildJsonObject {
            put("path", loaded.path)
            put("sha256", sha256(loaded.readBytes()))
            put("installedCliSolver", installed.path)
        }.toString() + "\n"
        )
        URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), javaClass.classLoader).use { loader ->
            val access = LinearCliAccess(loader)
            for ((index, source) in sources.withIndex()) {
                val directory = out.resolve(index.toString()).apply { mkdirs() }
                val ref = Catalog.uncapped(source.suite).problems.single { it.name == source.problem }
                val bytes = CorpusFiles.open(
                    CorpusFetcher.resolve(ref.source),
                ).use { it.readNBytes(32 * 1024 * 1024 + 1) }
                require(bytes.size <= 32 * 1024 * 1024 && sha256(bytes) == source.sha256)
                val input = directory.resolve("source.smt2").apply { writeBytes(bytes) }
                val order = if (source.bounded) listOf(false, true, true, false) else listOf(false, true)
                for ((run, observed) in order.withIndex()) {
                    val destination = directory.resolve(run.toString()).apply { mkdirs() }
                    access.measure(source, input, observed, destination)
                }
            }
        }
    }
}

@Serializable
private data class LinearStopSource(
    val suite: String,
    val problem: String,
    val sha256: String,
    val seed: Long,
    val bounded: Boolean = false,
)

private class LinearCliAccess(private val loader: ClassLoader) {
    private val prefix = "com.eignex.klause.cli."
    private val optionsType = loader.loadClass(prefix + "CommonOptions")
    private val modeType = loader.loadClass(prefix + "SmtLibMode")
    private val coreType = loader.loadClass(prefix + "SolveCore")
    private val core = coreType.getField("INSTANCE").get(null)
    private val cliMode = loader.loadClass(prefix + "CliModeKt")

    fun measure(source: LinearStopSource, input: File, observed: Boolean, out: File) {
        val started = System.nanoTime()
        val common = optionsType.getDeclaredConstructor().newInstance()
        val config = loader.loadClass(prefix + "PlatformKt").getDeclaredMethod("installCliConfig").invoke(null)
        set(common, "config", requireNotNull(config))
        set(common, "timeLimitMs", 60000L)
        val cliNow = loader.loadClass(prefix + "PlatformKt").getDeclaredMethod("nowMillis").invoke(null) as Long
        set(common, "deadlineAtMs", cliNow + 60000L)
        set(common, "randomSeed", source.seed)
        set(common, "parallel", 1)
        set(common, "statistics", true)
        val routingField = optionsType.getDeclaredField("routingToken").apply { isAccessible = true }
        lateinit var routingStop: LinearObservedStop
        val routing by lazy {
            // Routing's elapsed allowance starts after source parsing, at its ordinary first use.
            routingField.set(common, null)
            val token = cliMode.getDeclaredMethod("routingCancellation", optionsType).invoke(null, common)
                as Cancellation
            routingField.set(common, routingStop)
            token
        }
        val deferred = object : Cancellation {
            override fun isCancelled() = routing()
            override fun deadline() = routing.deadline()
            override fun workMeter() = routing.workMeter()
        }
        routingStop = LinearObservedStop(deferred, observed)
        routingField.set(common, routingStop)
        val mode = modeType.getField("INSTANCE").get(null)
        val session = modeType.getMethod("newSession").invoke(mode)
        val load = session.javaClass.getDeclaredMethod("load", String::class.java, optionsType)
            .apply { isAccessible = true }
        val solvable = requireNotNull(load.invoke(session, input.path, common))
        val pipeline = get(solvable, "Pipeline")
        val frontendNs = System.nanoTime() - started
        val budget = optionsType.getDeclaredField("presolveBudget").apply { isAccessible = true }.get(common)
            as? PresolveBudget
        val routingSpent = budget?.spent()
        val deadline = coreType.getDeclaredMethod("deadlineCancellation", optionsType).apply { isAccessible = true }
            .invoke(core, common) as Pair<*, *>
        val containing = LinearObservedStop(deadline.second as Cancellation, observed)
        val remaining = requireNotNull(containing.deadline()) - TimeSource.Monotonic.markNow()
        require(remaining.inWholeMilliseconds <= 60000L) { "CLI deadline uses a different clock" }
        val allowance = coreType.declaredMethods.single { it.name.startsWith("presolveAllowance") }
            .apply { isAccessible = true }.invoke(core, common, containing) as Pair<*, *>
        val preparation = LinearObservedStop(allowance.first as Cancellation, observed)
        val params = TheoryParams(
            timeout = containing,
            cancellation = containing,
            randomSeed = source.seed,
            maxDecisions = if (source.bounded) 8 else Long.MAX_VALUE,
            maxFlips = if (source.bounded) 4 else Long.MAX_VALUE,
            maxInstructions = if (source.bounded) 10000L else null,
        )
        val request = pipeline.javaClass.methods.singleOrNull { it.name == "getRequest" }?.invoke(pipeline)
            as? OpenTheoryRequest
        val model = request?.model ?: get(pipeline, "Model") as Problem
        val prepared = request?.preparedPlan
        val execution = if (request != null) {
            val presolve = config.javaClass.getMethod("presolveConfig").invoke(config)
                as PresolveConfig
            OpenTheoryPipeline.execute(
                request.withPresolve(
                    presolve,
                    cancellation = preparation,
                    budget = allowance.second as? PresolveBudget,
                ),
                params,
                if (source.bounded) FiniteEngine.BACKTRACK else FiniteEngine.DEFAULT,
                1,
            )
        } else {
            require(pipeline.javaClass.simpleName == "OpenLocalSearch")
            OpenTheoryPipeline.searchWithoutTheory(
                model,
                params,
                null,
                if (source.bounded) FiniteEngine.LOCAL_SEARCH else FiniteEngine.DEFAULT,
                1,
            )
        }
        val result = (execution as OpenTheoryExecution.Satisfy).result
        val stats = result.stats
        val work = stats.openTheory
        out.resolve("stats.txt").writeText(stats.toString() + "\n")
        val pairs = loader.loadClass(prefix + "CliStatsKt").getDeclaredMethod(
            "openTheoryStatPairs",
            SolveStats::class.java,
            Long::class.javaPrimitiveType,
        )
            .invoke(null, stats, stats.run.wallMs) as List<*>
        out.resolve("stat-pairs.txt").writeText(pairs.joinToString("\n") + "\n")
        out.resolve("result.json").writeText(
            buildJsonObject {
            put("observed", observed)
            put("boundedControl", source.bounded)
            put("engine", if (source.bounded) (if (request != null) "backtrack" else "localsearch") else "mixed")
            put("sourceSha256", source.sha256)
            put("seed", source.seed)
            put("pipeline", pipeline.javaClass.simpleName)
            put("declaredRoute", request?.route?.name)
            put("frontendNs", frontendNs)
            put("remainingMsAtExecution", remaining.inWholeMilliseconds)
            put("routingSpent", routingSpent)
            put("presolveSpent", (allowance.second as? PresolveBudget)?.spent())
            put("presolveAllowance", (allowance.second as? PresolveBudget)?.allowance)
            put("elapsedNs", System.nanoTime() - started)
            put("result", result.javaClass.simpleName)
            put("reason", (result as? OpenTheoryResult.Unknown)?.reason?.name)
            put("booleans", model.numBoolVars)
            put("integers", model.numIntVars)
            put("reals", model.numRealVars)
            put("factors", model.numFactors)
            put("planDecline", prepared?.decline?.name)
            put("booleanDecisions", work.openBoolDecisions)
            put("integerDecisions", work.openIntDecisions)
            put("theoryDecisions", work.openTheoryDecisions)
            put("checks", work.openTheoryChecks)
            put("work", work.openWork)
            put("routingStop", Json.encodeToString(routingStop.observation))
            put("preparationStop", Json.encodeToString(preparation.observation))
            put("containingStop", Json.encodeToString(containing.observation))
        }.toString() + "\n"
        )
    }

    private fun set(owner: Any, name: String, value: Any) {
        optionsType.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }

    private fun get(owner: Any, name: String): Any = requireNotNull(owner.javaClass.getMethod("get$name").invoke(owner))
}

@Serializable
internal data class LinearStopObservation(
    val capturedNs: Long,
    val captureElapsedNs: Long,
    val stack: List<String>,
)

internal class LinearObservedStop(private val delegate: Cancellation, private val observed: Boolean) : Cancellation {
    var observation: LinearStopObservation? = null
        private set

    override fun isCancelled(): Boolean {
        val cancelled = delegate.isCancelled()
        if (cancelled && observed && observation == null) {
            val started = System.nanoTime()
            val stack = Thread.currentThread().stackTrace.take(96).map { it.toString() }
            observation = LinearStopObservation(started, System.nanoTime() - started, stack)
        }
        return cancelled
    }

    override fun deadline() = delegate.deadline()
    override fun workMeter() = delegate.workMeter()
}
