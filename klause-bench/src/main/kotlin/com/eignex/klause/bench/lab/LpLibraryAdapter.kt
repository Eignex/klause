package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.metric.BuildProvenance
import com.eignex.klause.bench.metric.NativeCliBuild
import com.eignex.klause.bench.metric.SolveMetric
import com.eignex.klause.bench.metric.SolveRecord
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.metric.runtimeOptions
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.lab.bench.*
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Base64

/** Records an ordinary public library consumer with independently checked original-source coordinates. */
fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    configureLabPaths()
    BenchCli.execute(LpLibrarySuite, args, stdout = protocolOutput)
}

internal object LpLibrarySuite : BenchSuite {
    private val runtime get() = requireNotNull(System.getenv("KLAUSE_LP_CALLER_RUNTIME"))
        .also { require(it in setOf("jvm", "native")) }

    override fun describe() = BenchDescriptor("klause-lp-library", "Exact original MPS public Backtrack caller")

    override fun plan(request: BenchRequest): List<BenchCase> = NativeLpSuite.plan(request).map { case ->
        require(case.parameters["exact"] == "true") { "Library qualification requires exact source semantics" }
        require(case.parameters["engine"] == "default" && case.parameters.keys.none { it.startsWith("param.") })
        require(case.parameters["lp"] == null && case.parameters["presolve"] == null)
        require(case.parameters.getValue("timeout-ms").toLong() <= 300_000)
        case.copy(attributes = case.attributes + mapOf("cliRuntime" to runtime, "caller" to "baked-backtrack"))
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val started = System.nanoTime()
        LabInputs.verify(case, context)
        val report = context.outputDirectory.resolve("report").also { Files.createDirectories(it) }
        val p = case.parameters
        val input = LabInputs.path(case.inputs.single { it.role == "model" }, context).toFile()
        val ref = ProblemRef(case.attributes.getValue("problem"), Format.MPS, ProblemSource.Prepared(input),
            Category.valueOf(case.attributes.getValue("category")), Expected.Unknown)
        val settings = SolverInvocation.Settings(processors = 1, seed = p.getValue("seed").toLong(), exact = true,
            captureDirectory = report.toFile(), measureResources = p.getValue("measurement") != "none")
        val root = CorpusFetcher.workspaceRoot()
        val executable = root.resolve("klause/build/bin/linuxX64/lpProfileReleaseExecutable/klause-lp-profile.kexe")
        val javaHome = File(System.getProperty("java.home"))
        val options = listOf("-Xmx${p.getValue("heap-mib")}m", "-XX:ActiveProcessorCount=1") + when (p.getValue("gc")) {
            "default" -> emptyList()
            "serial" -> listOf("-XX:+UseSerialGC")
            "parallel" -> listOf("-XX:+UseParallelGC")
            "g1" -> listOf("-XX:+UseG1GC")
            "z" -> listOf("-XX:+UseZGC")
            else -> error("Unsupported caller GC")
        }
        val harness = NativeCliBuild.harnessIdentity(root)
        val environment = System.getenv() + ("KLAUSE_DEBUG" to p.getValue("debug"))
        val provenance = if (runtime == "native") {
            NativeCliBuild.capture(executable = executable, harness = harness, environment = environment)
        } else BuildProvenance(NativeCliBuild.treeIdentity(root.resolve("klause/build/lp-profile/jvm")),
            NativeCliBuild.treeIdentity(javaHome), runtimeOptions(environment) +
                ("javaOptions" to options.joinToString(" ")),
            System.getProperty("os.name"), System.getProperty("os.arch"), harness)
        val record = SolveMetric.solve(Runners.resolve(ref, true), settings, Budget(p.getValue("timeout-ms").toLong()),
            Instant.now().toString(), Reports.readGitSha(), installedBuild = { provenance },
            invoke = { _, _, budget, _ ->
                val command = if (runtime == "native") listOf(executable.absolutePath) else
                    listOf(javaHome.resolve("bin/java").absolutePath) + options + listOf(
                        "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector", "-cp",
                        root.resolve("klause/build/lp-profile/jvm/classes").path + File.pathSeparator +
                            root.resolve("klause/build/lp-profile/jvm/lib/*").path,
                        "com.eignex.klause.bench.profile.LpLifecycleProfileKt")
                SolverInvocation.invoke(command + listOf("mode=library",
                    "source64=${Base64.getEncoder().encodeToString(input.readBytes())}",
                    "seed=${settings.seed}", "timeout-ms=${budget.timeoutMillis}"),
                    SolverInvocation.Dialect.PB_COMPETITION,
                    hardTimeoutMs = budget.timeoutMillis * 2 + 20_000, captureDirectory = report.toFile(),
                    measureResources = settings.measureResources, startProcess = { argv ->
                        ProcessBuilder(argv).apply { environment()["KLAUSE_DEBUG"] = p.getValue("debug") }.start()
                    }).copy(buildProvenance = provenance)
            }).first
        val retained = record.copy(stats = record.stats + mapOf(
            "libraryAdapterElapsedNs" to (System.nanoTime() - started).toString(),
            "callerRuntime" to runtime,
        ), comparisonControls = KlauseLabSuite.comparisonControls(case) + mapOf(
            "series" to "lp-public-library-v1-$runtime", "caller" to "baked-backtrack", "runtime" to runtime,
            "runtimeFingerprint" to provenance.copy(distribution = emptyMap(), harness = emptyMap()).fingerprint,
            "heap-mib" to if (runtime == "native") "not-applicable-native" else p.getValue("heap-mib"),
            "gc" to if (runtime == "native") "compiled-default" else p.getValue("gc"),
        ))
        Files.writeString(report.resolve("solve-record.json"),
            Reports.json.encodeToString(SolveRecord.serializer(), retained))
        val failure = KlauseLabSuite.failure(retained)
        return BenchResult(case.id, if (failure == null) BenchStatus.SUCCESS else BenchStatus.FAILED,
            metrics = KlauseLabSuite.metrics(retained) + LabRuntime.resourceMetrics(report),
            artifacts = context.artifacts(),
            details = Reports.json.encodeToJsonElement(SolveRecord.serializer(), retained) as JsonObject,
            message = failure)
    }
}
