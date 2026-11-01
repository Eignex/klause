package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.metric.BuildProvenance
import com.eignex.klause.bench.metric.NativeCliBuild
import com.eignex.klause.bench.metric.SolveEvidence
import com.eignex.klause.bench.metric.SolveMetric
import com.eignex.klause.bench.metric.SolveRecord
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.lab.bench.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.time.Instant

/** Plans original MPS and SMT-LIB sources through the ordinary Native CLI and lab checker protocol. */
fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    configureLabPaths()
    BenchCli.execute(NativeLpSuite, args, stdout = protocolOutput)
}

internal object NativeLpSuite : BenchSuite {
    override fun describe() = BenchDescriptor("klause-native-lp", "Original MPS and SMT-LIB Native CLI qualification")

    override fun plan(request: BenchRequest): List<BenchCase> = KlauseLabSuite.plan(request).map { case ->
        require(Format.valueOf(case.attributes.getValue("format")) in setOf(Format.MPS, Format.SMTLIB)) {
            "Native LP qualification requires original MPS or SMT-LIB"
        }
        require(case.parameters["input-only"] != "true") { "Native LP qualification executes the original source" }
        require(case.resources.cpus == 1) { "Native LP qualification uses one processor" }
        require(case.parameters["measurement"] in setOf("none", "resources")) {
            "Native LP qualification supports ordinary timing and process resources"
        }
        case.copy(attributes = case.attributes + ("cliRuntime" to "kotlin-native-linux-x86_64"))
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val started = System.nanoTime()
        val report = context.outputDirectory.resolve("report")
        Files.createDirectories(report)
        Files.writeString(context.outputDirectory.resolve("input.json"),
            BenchProtocol.json.encodeToString(BenchCase.serializer(), case))
        val record = record(case, context)
        val adapter = ComparisonProvenance.current
        val identities = ComparisonProvenance.Identities(adapter.harness,
            adapter.libraries + record.buildProvenance?.runtime.orEmpty().mapKeys { "native/${it.key}" })
        val provenance = record.buildProvenance?.let { it.copy(harness = it.harness + identities.retained) }
        val retained = record.copy(stats = record.stats + mapOf(
            "cliRuntime" to "kotlin-native-linux-x86_64",
            "nativeAdapterElapsedNs" to (System.nanoTime() - started).toString(),
            "nativeGcPolicy" to "compiled-default",
        ), buildProvenance = provenance, buildFingerprint = provenance?.fingerprint,
            comparisonControls = KlauseLabSuite.comparisonControls(case) + buildMap {
            put("series", "${case.parameters["comparison-series"] ?: "sdk-v1"}-native-lp")
            put("cliRuntime", "kotlin-native-linux-x86_64")
            put("heap-mib", "not-applicable-native")
            put("gc", "kotlin-native-compiled-default")
            put("harnessHeapMiB", "512")
            provenance?.let { build ->
                put("runtimeFingerprint",
                    ComparisonProvenance.runtimeFingerprint(build.copy(distribution = emptyMap())))
                put("harnessFingerprint", identities.harnessFingerprint)
                put("libraryFingerprint", identities.libraryFingerprint)
            }
        })
        Files.writeString(report.resolve("solve-record.json"),
            Reports.json.encodeToString(SolveRecord.serializer(), retained))
        Files.writeString(context.outputDirectory.resolve("runtime.json"), buildJsonObject {
            put("scope", "whole-cli-native")
            put("adapterScope", "input verification, source decoding, binary/runtime hashing, CLI, witness decoding")
            put("processors", case.resources.cpus)
            put("memoryMiB", case.resources.memoryMiB)
            put("nativeGcPolicy", "compiled-default")
            put("harnessRuntime", "JVM; separately retained in buildProvenance.harness")
            put("sourceChecking", "lab independent checker; feasible point authority only")
        }.toString())
        val error = KlauseLabSuite.failure(retained)
        return BenchResult(
            case.id,
            if (error == null) BenchStatus.SUCCESS else BenchStatus.FAILED,
            metrics = KlauseLabSuite.metrics(retained) + LabRuntime.resourceMetrics(report),
            artifacts = context.artifacts() + context.artifact("input.json") + context.artifact("runtime.json"),
            details = Reports.json.encodeToJsonElement(SolveRecord.serializer(), retained) as JsonObject,
            message = error,
        )
    }

    private fun record(case: BenchCase, context: BenchContext): SolveRecord {
        val p = case.parameters
        val ref = ProblemRef(case.attributes.getValue("problem"), Format.valueOf(case.attributes.getValue("format")),
            ProblemSource.Prepared(LabInputs.path(case.inputs.single { it.role == "model" }, context).toFile()),
            Category.valueOf(case.attributes.getValue("category")), Expected.Unknown)
        val report = context.outputDirectory.resolve("report")
        val settings = SolverInvocation.Settings(
            engine = p.getValue("engine").takeUnless { it == "default" },
            processors = case.resources.cpus,
            seed = p.getValue("seed").toLong(),
            params = p.filterKeys { it.startsWith("param.") }.map { (k, v) -> "${k.removePrefix("param.")}=$v" },
            lp = p["lp"], presolve = p["presolve"], exact = p["exact"]?.toBooleanStrict() ?: false,
            captureDirectory = report.toFile(), measureResources = p.getValue("measurement") != "none",
            completeAssignment = p["complete-assignment"]?.toBooleanStrict() ?: false,
        )
        val budget = Budget(p.getValue("timeout-ms").toLong())
        val timestamp = Instant.now().toString()
        val sha = Reports.readGitSha()
        var identity: BuildProvenance? = null
        return runCatching {
            LabInputs.verify(case, context)
            val executable = NativeCliBuild.executable()
            identity = NativeCliBuild.capture(harness = NativeCliBuild.harnessIdentity(),
                environment = System.getenv() + ("KLAUSE_DEBUG" to p.getValue("debug")))
            SolverInvocation.klauseCliDefect(executable)?.let { error(it) }
            val entry = Runners.resolve(ref, settings.exact)
            SolveMetric.solve(entry, settings, budget, timestamp, sha, installedBuild = { identity },
                invoke = { current, options, allowance, optimize ->
                    SolverInvocation.withSourceInput(CorpusFetcher.resolve(current.ref.source), allowance) {
                            file, remaining ->
                        val command = SolverInvocation.klauseCommand(current, options, remaining, optimize,
                            file, executable)
                        SolverInvocation.invoke(command, SolverInvocation.dialectFor(current.ref.format),
                            hardTimeoutMs = remaining.timeoutMillis * 2 + 20_000,
                            captureDirectory = options.captureDirectory, measureResources = options.measureResources,
                            startProcess = { argv -> ProcessBuilder(argv).apply {
                                environment()["KLAUSE_DEBUG"] = p.getValue("debug")
                            }.start() }).copy(buildProvenance = identity)
                    }
                }).first
        }.getOrElse { failure ->
            SolveMetric.loadFailureRecord(ref, settings, budget, timestamp, sha, failure).copy(
                buildProvenance = identity,
                buildFingerprint = identity?.fingerprint,
                sourceHashes = runCatching { SolveEvidence.sourceHashes(ref) }.getOrDefault(emptyMap()),
            )
        }
    }
}
