package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.PlannedSearch
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ExactFeasibilityCapture {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        if (System.getenv("KLAUSE_EXACT_CAPTURE_CHILD") != "1") {
            launch(args, out)
            return
        }
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        if (options.keys == setOf("inspectJob", "captureSha256")) {
            inspect(options, out)
            return
        }
        require(options.keys == setOf("suite", "problem", "sha256"))
        val reference = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use { it.readNBytes(1024 * 1024 + 1) }
        require(bytes.size <= 1024 * 1024 && sha256(bytes) == options.getValue("sha256"))
        out.resolve("source.smt2").writeBytes(bytes)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val stop = Cancellation.after(10_000.milliseconds)
        val compiled = SmtLib.parse(bytes.decodeToString(), cancellation = stop)
        require(compiled.objective == null)
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            compiled.source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK, cancellation = stop,
        ))
        val request = (invocation.route as? SourceProblemRoute.OpenTheory)?.request
            ?: error("source did not select exact linear execution")
        val factory = OpenTheoryPipeline.javaClass.declaredMethods.single {
            it.name.startsWith("engineFor") && it.parameterCount == 1
        }
        val engine = requireNotNull(factory.invoke(OpenTheoryPipeline, request))
        val params = TheoryParams(timeout = stop, randomSeed = 3)
        val stateType = Class.forName("com.eignex.klause.solver.pipeline.OpenTheorySolveState")
        val state = stateType.getDeclaredConstructor(TheoryParams::class.java).newInstance(params)
        val begin = engine.javaClass.declaredMethods.single { it.name.startsWith("begin") && it.parameterCount == 3 }
        val begun = requireNotNull(begin.invoke(engine, params, state, SearchNodePolicy.ExpandAll))
        val run = call(begun, "getRun") ?: error("opening decided before an inspectable search")
        val planned = field(run, "planned") as PlannedSearch
        val theory = requireNotNull(planned.theory)
        val observer = field(theory, "primaryLpObserver") ?: error("missing primary certification observer")
        val observerType = Class.forName("com.eignex.klause.lp.engine.LpCertificationObserver")
        var snapshot: Any? = null
        var booleans: IntArray? = null
        var capturedPremises: JsonElement = JsonNull
        var captureNs = 0L
        var observations = 0L
        val wrapped = Proxy.newProxyInstance(observerType.classLoader, arrayOf(observerType)) { _, method, values ->
            val result = method.invoke(observer, *values.orEmpty())
            if (method.name == "observeContinuation" && snapshot == null &&
                call(requireNotNull(values?.first()), "getDecline").toString() == "DIMENSION" &&
                field(theory, "unassignedBooleans") == 0 && field(theory, "completeTraversalObserved") == true
            ) {
                observations++
                val started = System.nanoTime()
                val lp = (field(theory, "lpDelegate") as Lazy<*>).value ?: error("missing LP owner")
                snapshot = requireNotNull(call(lp, "getState"))
                booleans = (field(theory, "bools") as IntArray).copyOf()
                capturedPremises = premises(lp, requireNotNull(snapshot), field(theory, "context") as SearchContext)
                captureNs = System.nanoTime() - started
            }
            result
        }
        setField(theory, "primaryLpObserver", wrapped)
        var result: Any? = null
        try {
            while (result == null && !stop()) result = call(run, "advance")
        } finally {
            (run as AutoCloseable).close()
        }
        val captured = snapshot ?: error("no complete dimension-declined state observed")
        val encoded = encode(captured)
        out.resolve("complete.klexact").writeBytes(encoded)
        out.resolve("premises.json").writeText(capturedPremises.toString())
        out.resolve("capture.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("sourceSha256", sha256(bytes))
            put("captureSha256", sha256(encoded))
            put("entry", "diagnostic direct complete worker; no CLI or default portfolio equivalence")
            put("sourceVerdictClaim", false)
            put("containingTimeoutMs", 10_000)
            put("seed", 3)
            put("processors", Runtime.getRuntime().availableProcessors())
            put("observerCaptureNs", captureNs)
            put("capturedCompleteDimensionObservations", observations)
            put("rawResult", result?.javaClass?.simpleName)
            put("booleans", buildJsonArray { requireNotNull(booleans).forEach { add(JsonPrimitive(it)) } })
            put("shape", shape(captured))
        }.toString())
    }

    private fun premises(lp: Any, state: Any, context: SearchContext): JsonElement {
        val model = requireNotNull(call(state, "getModel"))
        val count = call(model, "getNumVars") as Int
        val seen = IdentityHashMap<SearchAtomPremise, Int>()
        val nodes = ArrayList<JsonElement>()
        fun visit(premise: SearchAtomPremise): Int {
            seen[premise]?.let { return it }
            check(nodes.size < 32768) { "premise capture exceeds its bound" }
            val id = nodes.size
            seen[premise] = id
            nodes.add(JsonNull)
            nodes[id] = when (premise) {
                is SearchAtomPremise.Asserted -> buildJsonObject {
                    val literal = context.atomLiteral(premise.decision)
                    put("decision", premise.decision.toString())
                    put("literal", literal?.let(::JsonPrimitive) ?: JsonNull)
                    put("currentValue", literal?.let { context.boolValue(it ushr 1) }?.let(::JsonPrimitive) ?: JsonNull)
                }
                is SearchAtomPremise.All -> buildJsonObject {
                    @Suppress("UNCHECKED_CAST")
                    val children = field(premise, "premises") as List<SearchAtomPremise>
                    put("all", JsonArray(children.map { JsonPrimitive(visit(it)) }))
                }
                SearchAtomPremise.Unavailable -> buildJsonObject { put("unavailable", true) }
            }
            return id
        }
        val sides = buildJsonArray {
            for (column in 0 until count) for (upper in listOf(false, true)) {
                val premise = call(lp, "activeBoundPremise", column, upper) as? SearchAtomPremise ?: continue
                add(buildJsonObject {
                    put("column", column)
                    put("upper", upper)
                    put("premise", visit(premise))
                })
            }
        }
        return buildJsonObject {
            put("sides", sides)
            put("nodes", JsonArray(nodes))
        }
    }

    private fun encode(state: Any): ByteArray {
        val prefix = "com.eignex.klause.lp.engine."
        val settingsType = Class.forName(prefix + "LpReplaySettings")
        val kind = Class.forName(prefix + "LpReplaySolverKind").enumConstants.single { it.toString() == "PERSISTENT" }
        val refactor = Class.forName(prefix + "RevisedSimplexKt").getDeclaredField("DEFAULT_REFACTOR_UPDATE_LIMIT")
            .getInt(null)
        val settings = settingsType.constructors.single { it.parameterCount == 9 }.newInstance(
            "complete-dimension-state", 3L, kind, false, 0, 0, 0L, refactor, false,
        )
        val solve = Class.forName(prefix + "LpExactReplayEvent\$Solve").getConstructor().newInstance()
        val companion = Class.forName(prefix + "LpExactCapture").getField("Companion").get(null)
        val capture = call(companion, "capture", state, settings, listOf(solve), Int.MAX_VALUE)
            ?: error("exact state capture failed")
        val bytes = call(capture, "encode") as ByteArray
        val decoded = requireNotNull(call(companion, "decode", bytes))
        check(bytes.contentEquals(call(decoded, "encode") as ByteArray)) { "exact state codec did not round trip" }
        return bytes
    }

    private fun inspect(options: Map<String, String>, out: File) {
        val sourceJob = options.getValue("inspectJob").toLong().also { require(it > 0L) }
        val jobs = File(requireNotNull(System.getenv("JOB_DIR"))).canonicalFile.parentFile
        val path = jobs.resolve("$sourceJob/cases/0/out/complete.klexact").canonicalFile
        require(path.toPath().startsWith(jobs.toPath()) && path.length() in 1..16L * 1024 * 1024)
        val bytes = path.readBytes()
        require(sha256(bytes) == options.getValue("captureSha256"))
        val prefix = "com.eignex.klause.lp.engine."
        val companion = Class.forName(prefix + "LpExactCapture").getField("Companion").get(null)
        val capture = requireNotNull(call(companion, "decode", bytes))
        val state = requireNotNull(call(capture, "getInitialState"))
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("shape.json").writeText(buildJsonObject {
            put("inspectionRevision", Reports.readGitSha())
            put("sourceJob", sourceJob)
            put("captureSha256", sha256(bytes))
            put("shape", shape(state))
            put("fixedAndComponents", components(state))
        }.toString())
        val contextCompanion = Class.forName(prefix + "LpSolveContext").getField("Companion").get(null)
        val context = requireNotNull(call(contextCompanion, "getProduction"))
        val replay = Class.forName(prefix + "LpExactReplay").getField("INSTANCE").get(null)
        val started = System.nanoTime()
        val report = requireNotNull(call(replay, "replay", capture, context))
        val elapsed = System.nanoTime() - started
        val steps = call(report, "getSteps") as List<*>
        out.resolve("replay.json").writeText(buildJsonObject {
            put("inspectionRevision", Reports.readGitSha())
            put("captureSha256", sha256(bytes))
            put("coldReplayElapsedNs", elapsed)
            put("declinedEvent", call(report, "getDeclinedEventIndex")?.toString())
            put("originalWarmFactorsCaptured", false)
            put("sourceVerdictClaim", false)
            put("steps", buildJsonArray {
                for (step in steps) {
                    val current = requireNotNull(step)
                    val result = call(current, "getResult")
                    add(buildJsonObject {
                        put("accepted", call(current, "getAccepted") as Boolean)
                        put("verdict", result?.let { call(it, "getVerdict") }?.toString())
                        put("hasExactPoint", result?.let { call(it, "getExactPrimal") } != null)
                        put("continuation", result?.let { call(it, "getContinuation") }?.toString())
                    })
                }
            })
        }.toString())
    }

    private fun components(state: Any): JsonElement {
        val model = requireNotNull(call(state, "getModel"))
        val n = call(model, "getN") as Int
        val m = call(model, "getM") as Int
        val fixed = BooleanArray(n) { j ->
            val column = requireNotNull(call(model, "column", j))
            call(requireNotNull(call(column, "getBounds")), "getFixed") == true
        }
        val parent = IntArray(n + m) { it }
        fun root(node: Int): Int {
            var current = node
            while (current != parent[current]) current = parent[current]
            return current
        }
        val degrees = IntArray(m)
        for (j in 0 until n) {
            if (fixed[j]) continue
            val entries = call(model, "entries", j) as List<*>
            for (entry in entries) {
                val item = requireNotNull(entry)
                if (call(requireNotNull(call(item, "getNumber")), "getValue").toString() == "0") continue
                val row = call(item, "getRow") as Int
                degrees[row]++
                parent[root(n + row)] = root(j)
            }
        }
        val grouped = (0 until m).filter { degrees[it] > 0 }.groupBy { root(n + it) }
        return buildJsonObject {
            put("fixedStructuralColumns", JsonArray((0 until n).filter { fixed[it] }.map(::JsonPrimitive)))
            put("constantRowsAfterSubstitution", JsonArray((0 until m).filter { degrees[it] == 0 }.map(::JsonPrimitive)))
            put("constantRowsVerified", false)
            put("rowUnfixedDegrees", JsonArray(degrees.map(::JsonPrimitive)))
            put("components", buildJsonArray {
                for ((key, rows) in grouped) {
                    val columns = (0 until n).filter { !fixed[it] && root(it) == key }
                    add(buildJsonObject {
                        put("rows", JsonArray(rows.map(::JsonPrimitive)))
                        put("columns", JsonArray(columns.map(::JsonPrimitive)))
                        put("denseCells", rows.size.toLong() * (columns.size.toLong() + 2L * rows.size + 1))
                    })
                }
            })
        }
    }

    private fun shape(state: Any): JsonElement {
        val model = requireNotNull(call(state, "getModel"))
        val n = call(model, "getN") as Int
        val m = call(model, "getM") as Int
        val total = call(model, "getNumVars") as Int
        val objective = requireNotNull(call(model, "getObjective"))
        val rows = requireNotNull(call(state, "getRows"))
        val rowEntries = call(rows, "entries") as List<*>
        val active = rowEntries.count { call(requireNotNull(it), "getActive") == true }
        val free = (0 until m).filter { row ->
            val column = requireNotNull(call(model, "column", n + row))
            val bounds = requireNotNull(call(column, "getBounds"))
            val cost = requireNotNull(call(objective, "cost", n + row))
            call(bounds, "getLower") == null && call(bounds, "getUpper") == null &&
                call(column, "getIntegral") == false && call(cost, "getValue").toString() == "0" &&
                call(requireNotNull(call(model, "row", row)), "getStrict") == false
        }
        val projected = m - free.size
        return buildJsonObject {
            put("structuralColumns", n)
            put("retainedRows", m)
            put("activeRows", active)
            put("totalColumns", total)
            put("denseCells", m.toLong() * (total.toLong() + m + 1))
            put("failsRows128", m > 128)
            put("failsColumns4096", total > 4096)
            put("failsCells65536", m.toLong() * (total.toLong() + m + 1) > 65536)
            put("freeZeroCostNonintegralLogicalRows", JsonArray(free.map(::JsonPrimitive)))
            put("candidateConstrainedRows", projected)
            put("candidateDenseCells", projected.toLong() * (n.toLong() + 2L * projected + 1))
            put("projectionApplied", false)
        }
    }

    private fun call(owner: Any, name: String, vararg arguments: Any?): Any? {
        val methods = owner.javaClass.methods.filter {
            it.name.substringBefore('$') == name && it.parameterCount == arguments.size
        }
        val method = methods.singleOrNull() ?: error("ambiguous or missing ${owner.javaClass.name}.$name")
        return method.invoke(owner, *arguments)
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private fun setField(owner: Any, name: String, value: Any) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(owner, value)

    private fun launch(args: Array<String>, out: File) {
        val command = listOf(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx3g", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
            "-Dklause.workspace.root=${CorpusFetcher.workspaceRoot()}",
            "-Dklause.bench.corpusCache=${CorpusFetcher.cacheRoot}",
            "-cp", System.getProperty("java.class.path"), javaClass.name,
        ) + args
        out.resolve("runtime.json").writeText(buildJsonObject {
            put("command", JsonArray(command.map(::JsonPrimitive)))
            put("parentOptions", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
        }.toString())
        val process = ProcessBuilder(command)
            .redirectOutput(out.resolve("child.stdout"))
            .redirectError(out.resolve("child.stderr"))
            .apply { environment()["KLAUSE_EXACT_CAPTURE_CHILD"] = "1" }.start()
        val completed = process.waitFor(120, TimeUnit.SECONDS)
        if (process.isAlive) {
            process.destroyForcibly()
            process.waitFor()
        }
        val exit = process.exitValue()
        out.resolve("child.exit").writeText("$exit\n")
        check(completed) { "capture child transport did not finish" }
        check(exit == 0) { "capture child failed" }
    }
}
