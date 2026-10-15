package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.PlannedSearch
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.util.Cancellation
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LinearPointAdmissionProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(requireNotNull(System.getenv("OUT")))
        if (System.getenv("KLAUSE_LINEAR_POINT_MEASURED_JVM") != "1") {
            launchMeasuredJvm(args, out)
            return
        }
        require(Runtime.getRuntime().availableProcessors() == 1)
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "sha256"))
        val expected = options.getValue("sha256")
        require(expected.matches(Regex("[0-9a-f]{64}")))
        val reference = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val file = CorpusFetcher.resolve(reference.source)
        val bytes = CorpusFiles.open(file).use { it.readNBytes(32 * 1024 * 1024 + 1) }
        require(bytes.size <= 32 * 1024 * 1024)
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(actual == expected) { "original source identity differs" }
        out.resolve("source.smt2").writeBytes(bytes)
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        val stop = Cancellation.after(60_000.milliseconds)
        val compiled = SmtLib.parse(bytes.decodeToString())
        require(compiled.objective == null) { "only satisfaction openings are supported" }
        val params = TheoryParams(timeout = stop, randomSeed = 3)
        // Internal admission inspection stays outside the published solver API.
        val factory = OpenTheoryPipeline.javaClass.declaredMethods.single {
            it.name.startsWith("engineFor") && it.parameterCount == 1
        }
        val engine = requireNotNull(factory.invoke(OpenTheoryPipeline, OpenTheoryRequest(compiled.model)))
        val stateType = Class.forName("com.eignex.klause.solver.pipeline.OpenTheorySolveState")
        val state = stateType.getDeclaredConstructor(TheoryParams::class.java).newInstance(params)
        val begin = engine.javaClass.declaredMethods.single { it.name.startsWith("begin") && it.parameterCount == 3 }
        val started = requireNotNull(begin.invoke(engine, params, state, SearchNodePolicy.ExpandAll))
        val run = started.javaClass.methods.singleOrNull { it.name == "getRun" && it.parameterCount == 0 }
            ?.invoke(started)
        val result = buildJsonObject {
            put("sourceSha256", actual)
            put("revision", Reports.readGitSha())
            put("processors", Runtime.getRuntime().availableProcessors())
            put("seed", 3)
            put("openingBudgetMs", 60_000)
            put("traversalDecisions", 0)
            put("sourceVerdictClaim", false)
            put("opening", started.javaClass.simpleName)
            put("sourceBooleans", compiled.boolVarNames.size)
            put("sourceIntegers", compiled.intVarNames.size)
            put("sourceReals", compiled.realVarNames.size)
            if (run != null) {
                try {
                    val planned = field(run, "planned") as PlannedSearch
                    val component = planned.theory
                    put("component", component?.javaClass?.simpleName)
                    if (component is ExactLiraSearchComponent) {
                        val model = field(component, "model") as Problem
                        val rows = field(component, "pointHintRows") as? List<*>
                        val blocks = field(component, "pointForwardBlocks") as? Set<*>
                        val hints = field(component, "pointHintValues") as? IntArray
                        val variables = field(component, "pointHintVariables") as? IntArray
                        val cursor = field(component, "pointHintCursor") as Int
                        val first = variables?.getOrNull(cursor)
                        val entries = rows?.sumOf { prepared ->
                            (field(requireNotNull(prepared), "row") as LinearRow).size.toLong() + 1L
                        }?.plus(model.numBoolVars.toLong() + model.numIntVars + model.numRealVars)
                        put("modelBooleans", model.numBoolVars)
                        put("modelIntegers", model.numIntVars)
                        put("modelReals", model.numRealVars)
                        put("preparedRows", (field(component, "preparedRows") as List<*>).size)
                        put("registeredHintRows", rows?.size)
                        put("registeredPrimitiveIds", variables?.size)
                        put("pointRegistrationEntries", entries)
                        put("registeredBlockedPrimitives", blocks?.let { blocked ->
                            variables?.count { it in blocked }
                        })
                        put("firstPointPrimitive", first)
                        put("firstPointPrimitiveBlocked", first?.let { blocks?.contains(it) })
                        put("forwardDependenciesComplete", blocks != null)
                        put("forwardBlockedVariables", blocks?.size)
                        put("pointValuesPublished", hints != null)
                        put("pointCoordinates", (field(component, "candidate") as? List<*>)?.size)
                        put("finiteIntegerOwners", (field(component, "finiteIntVars") as IntArray).size)
                        put("dirty", field(component, "dirty") as Boolean)
                        put("pendingPointForward", field(component, "pendingPointForward") as Boolean)
                        put("decisionLevel", planned.session.decisionLevel)
                    }
                } finally {
                    (run as AutoCloseable).close()
                }
            }
        }
        out.resolve("point-admission.json").writeText(result.toString() + "\n")
        println(result)
    }

    private fun launchMeasuredJvm(args: Array<String>, out: File) {
        val command = arrayListOf(
            File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx3g", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
            "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector",
            "-XX:StartFlightRecording=filename=${out.resolve("measured-profile.jfr")}," +
                "settings=profile,dumponexit=true",
        )
        for (name in listOf("klause.workspace.root", "klause.bench.corpusCache")) {
            System.getProperty(name)?.let { command += "-D$name=$it" }
        }
        command += listOf("-cp", System.getProperty("java.class.path"), javaClass.name)
        command += args
        out.resolve("launcher.json").writeText(buildJsonObject {
            put("parentProcessors", Runtime.getRuntime().availableProcessors())
            put("command", buildJsonArray { command.forEach { add(JsonPrimitive(it)) } })
        }.toString() + "\n")
        val builder = ProcessBuilder(command).inheritIO()
        builder.environment()["KLAUSE_LINEAR_POINT_MEASURED_JVM"] = "1"
        check(builder.start().waitFor() == 0) { "Measured point admission JVM failed" }
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
}
