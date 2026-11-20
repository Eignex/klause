package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.metric.SourceHarnessObservation
import com.eignex.klause.bench.profile.CliPreparationRuntime
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.lab.bench.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files

/** Observe finite preparation through installed CLI controls without constructing search workers. */
fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    configureLabPaths()
    BenchCli.execute(MpsCliPreparationSuite, args, stdout = protocolOutput)
}

internal object MpsCliPreparationSuite : BenchSuite {
    private val resources = BenchResources(1, 4096, 180, setOf("platform:linux", "arch:x86_64"))
    private val defaults = mapOf("heap-mib" to "2560", "gc" to "default", "debug" to "true",
        "measurement" to "none", "compile-timeout-sec" to "60")
    private val supplied = setOf("plain-sha256", "timeout-ms", "seed")

    override fun describe() = BenchDescriptor(
        "mps-cli-preparation", "Installed CLI preparation, no search or source verdict")

    override fun plan(request: BenchRequest): List<BenchCase> {
        require(request.parameters.keys == setOf("cases"))
        val cases = BenchProtocol.json.decodeFromString<List<BenchCase>>(request.parameters.getValue("cases"))
        require(cases.size in 1..8 && cases.map { it.id }.distinct().size == cases.size)
        return cases.map(::planned)
    }

    internal fun planned(case: BenchCase): BenchCase {
        require(case.parameters.keys == supplied)
        validate(case)
        return case.copy(parameters = case.parameters + defaults, resources = resources,
            attributes = case.attributes.filterKeys { !it.startsWith("lab") } +
                mapOf("recordSchema" to "mps-cli-preparation-v1"))
    }

    private fun validate(case: BenchCase) {
        val input = case.inputs.single()
        require(input.root == BenchInputRoot.CORPUS && input.role == "model")
        require(input.sha256?.matches(Regex("[a-f0-9]{64}")) == true)
        require(case.parameters.getValue("plain-sha256").matches(Regex("[a-f0-9]{64}")))
        require(case.parameters.getValue("timeout-ms").toLong() in 1..120_000L)
        case.parameters.getValue("seed").toLong()
        require(case.attributes.getValue("suite").isNotBlank() && case.attributes.getValue("problem").isNotBlank())
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        validate(case)
        require(case.resources == resources && case.parameters.keys == supplied + defaults.keys)
        require(case.parameters.filterKeys { it in defaults } == defaults)
        val output = context.outputDirectory
        val report = output.resolve("report").toFile().apply { mkdirs() }
        listOf("preparation.json", "admission.json").forEach { report.resolve(it).delete() }
        LabInputs.verify(case, context)
        val launcher = SolverInvocation.klauseCliBin()
        CliPreparationRuntime.requireLauncher(launcher)
        val worktree = requireNotNull(context.worktree).toFile()
        val jars = CliPreparationRuntime.classpath(launcher.parentFile.parentFile.resolve("lib"),
            worktree.resolve("klause-bench/build/install/klause-bench/lib"))
        Reports.writeJson(output.resolve("build-provenance.json").toString(), InstalledBuild.reference)
        val environment = LabRuntime.environment(case, context)
        val runtime = Json.parseToJsonElement(Files.readString(output.resolve("runtime.json"))).jsonObject
        val javaOptions = runtime.getValue("javaOptions").jsonArray.map { it.jsonPrimitive.content }
        Files.writeString(output.resolve("runtime.json"), buildJsonObject {
            put("scope", "cli-preparation-helper-jvm")
            put("javaOptions", JsonArray((CliPreparationRuntime.launcherOptions + javaOptions).map(::JsonPrimitive)))
            put("debug", true)
            put("heapMiB", 2560)
            put("cpus", 1)
            put("caseMemoryMiB", resources.memoryMiB)
            put("caseTimeoutSeconds", resources.timeoutSeconds)
            put("sourceBudgetMs", case.parameters.getValue("timeout-ms").toLong())
            put("shapeInspectionDeadlineSeconds", 20)
            put("resourceScope", "SDK case and attempt resource receipts; child-only costs unavailable")
            put("validationPolicy", "preparation-diagnostic-no-source-verdict")
            put("deadlinePolicy", "source staging spends source allowance; CLI keeps its shared controls; " +
                "separate shape inspection follows readiness")
            put("metricSources", JsonArray(emptyList()))
        }.toString())
        val model = LabInputs.path(case.inputs.single(), context).toFile()
        val ref = ProblemRef(case.attributes.getValue("problem"), Format.MPS, ProblemSource.Prepared(model),
            Category.OPTIMIZATION, Expected.Unknown)
        val resolved = ResolvedProblem(ref, lazy { error("preparation observer does not parse in its parent JVM") },
            optimizes = true)
        val settings = SolverInvocation.Settings(engine = "cp", processors = 1,
            seed = case.parameters.getValue("seed").toLong(),
            params = listOf("bt-arm=lp-aggressive", "arms=1", "lp-root-cuts=false", "node-limit=10000",
                "dry-run-presolve=true"))
        var childExit: Int? = null
        val source = SolverInvocation.withSourceInput(model, Budget(case.parameters.getValue("timeout-ms").toLong()),
            observeHarness = { SourceHarnessObservation.start() }) { plain, remaining ->
            require(plain.length() <= 64L * 1024 * 1024)
            val cliArgs = SolverInvocation.klauseCommand(resolved, settings, remaining, true, plain).drop(1)
            val request = buildJsonObject {
                put("suite", case.attributes.getValue("suite"))
                put("problem", case.attributes.getValue("problem"))
                put("plainPath", plain.path)
                put("plainSha256", case.parameters.getValue("plain-sha256"))
                put("cliArgs", JsonArray(cliArgs.map(::JsonPrimitive)))
            }
            val input = output.resolve("capture-request.json")
            Files.writeString(input, "$request\n")
            val argv = listOf(File(System.getProperty("java.home"), "bin/java").path) +
                CliPreparationRuntime.launcherOptions + javaOptions + listOf("-cp",
                    jars.joinToString(File.pathSeparator) { it.path },
                    "com.eignex.klause.bench.profile.CliPreparationCapture", input.toString(), report.path)
            Files.writeString(output.resolve("capture-command.json"), buildJsonObject {
                put("argv", JsonArray(argv.map(::JsonPrimitive)))
                put("classpath", CliPreparationRuntime.identity(jars))
                put("environment", buildJsonObject { environment.forEach { (key, value) -> put(key, value) } })
            }.toString())
            childExit = LabRuntime.process(context, argv, environment)
            SolverInvocation.Result(null, null, null, proven = false, stats = emptyMap(), rawOutput = "",
                command = "CLI_PREPARATION_DIAGNOSTIC")
        }
        fun captured(name: String): JsonObject? = report.resolve(name).takeIf { it.isFile }
            ?.let { Json.parseToJsonElement(it.readText()).jsonObject }
        val preparation = captured("preparation.json")
        val admission = captured("admission.json")
        val details = buildJsonObject {
            put("scope", "installed-cli-code-path-preparation-diagnostic")
            put("searchExecuted", false)
            put("sourceVerdictClaim", false)
            put("productionApplicabilityEstablished", false)
            put("childExit", childExit)
            put("preparation", preparation ?: JsonNull)
            put("admission", admission ?: JsonNull)
            put("captureComplete", childExit == 0 && preparation != null)
            put("sourceTimingScope", "staging and helper lifetime, including post-readiness shape inspection")
            put("sourceStaging", buildJsonObject { source.stats.forEach { (key, value) -> put(key, value) } })
        }
        Files.writeString(output.resolve("capture-result.json"), "$details\n")
        val artifacts = listOf("build-provenance.json", "runtime.json", "capture-request.json", "capture-command.json",
            "capture-result.json", "report/preparation.json", "report/admission.json", "solve.log")
            .filter { Files.isRegularFile(output.resolve(it)) }.map { context.artifact(it) }
        return BenchResult(case.id, BenchStatus.SUCCESS, details = details, artifacts = artifacts)
    }
}
