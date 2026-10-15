package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.MpsCompiled
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Compare explicit finite mixed MPS tree probing through the prepared public backtracking API. */
object MpsPrimalProbe {
    /** Capture paired API invocations and every original-coordinate incumbent for the MPS paths in [args]. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Pass original MPS paths" }
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        if (System.getenv("KLAUSE_MPS_PRIMAL_MEASURED") != "1") {
            launch(args, out)
            return
        }
        require(Runtime.getRuntime().availableProcessors() == 1)
        val revision = requireNotNull(Reports.readGitSha())
        val root = File(System.getProperty("klause.workspace.root", "."))
        Reports.writeJson(out.resolve("installed-build.json").path, InstalledBuild.reference)
        out.resolve("provenance.json").writeText(buildJsonObject {
            put("revision", revision)
            put("scope", "prepared-backtracking-api")
            put("sourceAdapter", "original-decimal-exact")
            put("javaVersion", System.getProperty("java.version"))
            put("javaVmVersion", System.getProperty("java.vm.version"))
            put("kotlinVersion", KotlinVersion.CURRENT.toString())
            put("processors", Runtime.getRuntime().availableProcessors())
            put("javaOptions", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
            put("classpath", buildJsonArray {
                System.getProperty("java.class.path").split(File.pathSeparator).map(::File)
                    .filter(File::isFile).forEach { file -> add(buildJsonObject {
                        put("name", file.name)
                        put("sha256", sha256(file.readBytes()))
                    }) }
            })
        }.toString())
        val cases = ArrayList<JsonObject>()
        val measurements = ArrayList<JsonObject>()
        for (path in args) {
            val input = File(path).let { if (it.isAbsolute) it else root.resolve(path) }
            val bytes = CorpusFiles.open(input).use { it.readBytes() }
            val digest = sha256(bytes)
            out.resolve("sources").apply { mkdirs() }.resolve("$digest.txt").writeBytes(bytes)
            val compiled = Mps.parse(bytes.decodeToString()).toExactSourceProblem()
            require((0 until compiled.model.numIntVars).all {
                compiled.model.intBounds.hasLower(it) && compiled.model.intBounds.hasUpper(it)
            }) { "The prepared API profile requires closed source integer bounds" }
            val baked = compiled.model.bake()
            for (round in -1 until 3) {
                for (exact in listOf(false, true)) {
                    val variants = if (round % 2 == 0) listOf(false, true) else listOf(true, false)
                    for (tree in variants) {
                        val objective = requireNotNull(compiled.objective).toLinearObjective().let {
                            if (compiled.maximize) it.negated() else it
                        }
                        val started = System.nanoTime()
                        var firstMs: Double? = null
                        var bestMs: Double? = null
                        val firstIndex = cases.size
                        val params = BacktrackParams(randomSeed = 3L, valueSelector = IndomainMin,
                            cancellation = Cancellation.after(BUDGET_MS.milliseconds), solveBudgetMillis = BUDGET_MS,
                            toleranceCheck = if (exact) null else { sample ->
                                compiled.withinTolerance(sample.ints, sample.reals)
                            }, lpPlan = LpPlan(bounding = true, lbTreeSearch = tree),
                            improvedSolutionSink = { sample, _ ->
                                val millis = (System.nanoTime() - started) / 1_000_000.0
                                if (firstMs == null) firstMs = millis
                                bestMs = millis
                                cases += capture(compiled, sample, input.nameWithoutExtension, digest,
                                    exact, tree, round, cases.size, millis, revision)
                            })
                        val result = BacktrackSolver(baked).minimize(objective, params)
                        measurements += buildJsonObject {
                            put("sourceSha256", digest)
                            put("source", path)
                            put("round", round)
                            put("warmup", round < 0)
                            put("tree", tree)
                            put("exact", exact)
                            put("seed", 3)
                            put("budgetMs", BUDGET_MS)
                            put("rootMaxWork", params.lpPlan.rootMaxWork)
                            put("rootBudgetFraction", params.lpPlan.rootBudgetFraction)
                            put("lpPlan", params.lpPlan.toString())
                            put("valueSelector", "IndomainMin")
                            put("maxDecisions", params.maxDecisions)
                            put("publicationMode", "api-improvedSolutionSink")
                            put("roundingAndPump", params.lpPlan.probe)
                            put("firstMs", firstMs)
                            put("bestMs", bestMs)
                            put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0)
                            put("publications", cases.size - firstIndex)
                            put("firstCaseIndex", firstIndex)
                            put("nativeResult", result::class.simpleName)
                            put("nativeProofCaptured", false)
                            put("nodes", result.stats.search.nodes.sum)
                            put("rootWork", result.stats.lp.rootWorkOps.sum)
                            put("standaloneWork", result.stats.lp.standaloneWorkOps.sum)
                            put("bestWorkingObjective", result.objectiveValue)
                        }
                        out.resolve("cases.json").writeText(JsonArray(cases).toString())
                        out.resolve("measurements.json").writeText(JsonArray(measurements).toString())
                    }
                }
            }
        }
    }

    private fun capture(compiled: MpsCompiled, sample: Sample, name: String, digest: String,
        exact: Boolean, tree: Boolean, round: Int, index: Int, millis: Double, revision: String): JsonObject {
        val values = compiled.columns.map { column ->
            if (column.real) sample.exactReals?.get(column.id)
                ?: requireNotNull(BigFraction.ofDouble(sample.reals[column.id]))
            else BigFraction.ofLong(sample.ints[column.id])
        }
        val value = if (sample.exactReals != null) compiled.sourceWitness(values).objective
        else requireNotNull(compiled.sourceObjective(sample.ints, sample.reals))
        return buildJsonObject {
            put("index", index)
            put("status", "DONE")
            put("arm", "${if (tree) "tree" else "control"}-${if (exact) "exact" else "default"}")
            put("round", round)
            put("elapsedMs", millis)
            put("problem", buildJsonObject {
                put("suite", "mps-linear-source-v1")
                put("problem", name)
            })
            put("record", buildJsonObject {
                put("problem", name)
                put("gitSha", revision)
                put("kind", "optimize")
                put("feasible", true)
                put("proven", false)
                put("maximize", compiled.maximize)
                put("objective", value.toDouble())
                put("exactObjective", value.toString())
                put("sourceHashes", buildJsonObject { put("model", digest) })
                put("finalWitness", "v " + compiled.columns.indices.joinToString(" ") {
                    "${compiled.columns[it].name}=${values[it]}"
                })
                put("witnessGuarantee", if (sample.exactReals == null) "source-tolerance" else "exact-coordinate")
            })
        }
    }

    private fun launch(args: Array<String>, out: File) {
        val command = arrayListOf(File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx3g", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "--enable-native-access=ALL-UNNAMED",
            "--add-modules=jdk.incubator.vector",
            "-XX:StartFlightRecording=filename=${out.resolve("measured-profile.jfr")},settings=profile,dumponexit=true")
        for (name in listOf("klause.workspace.root", "klause.bench.corpusCache")) {
            System.getProperty(name)?.let { command += "-D$name=$it" }
        }
        command += listOf("-cp", System.getProperty("java.class.path"), javaClass.name)
        command += args
        out.resolve("launcher.json").writeText(buildJsonObject {
            put("command", JsonArray(command.map(::JsonPrimitive)))
        }.toString())
        val builder = ProcessBuilder(command).inheritIO()
        builder.environment()["KLAUSE_MPS_PRIMAL_MEASURED"] = "1"
        val exit = builder.start().waitFor()
        out.resolve("measured-exit.txt").writeText("$exit\n")
        check(exit == 0) { "Measured MPS primal JVM failed" }
    }

    private const val BUDGET_MS = 5_000L
}
