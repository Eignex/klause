package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.SolveMetric
import com.eignex.klause.bench.metric.SolveRecord
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.target.collectionOf
import com.eignex.lab.bench.*
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import com.eignex.klause.bench.target.BenchCli as KlauseCli

/** Repository-owned lab planning and installed-CLI execution entry point. */
fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    configureLabPaths()
    if (args.firstOrNull() == "solve-record") {
        require(args.size == 3)
        val case = BenchProtocol.json.decodeFromString<BenchCase>(Files.readString(Path.of(args[1])))
        val output = Path.of(args[2])
        val record = KlauseLabSuite.solveRecord(
            case,
            BenchContext(
                output.parent,
                worktree = Path.of(requireNotNull(System.getenv("LAB_WORKTREE"))),
                corpusDirectory = System.getenv("LAB_CORPUS")?.let(Path::of),
            ),
        )
        Files.writeString(
            output.resolve("solve-record.json"),
            Reports.json.encodeToString(SolveRecord.serializer(), record),
        )
    } else {
        BenchCli.execute(KlauseLabSuite, args, stdout = protocolOutput)
    }
}

internal fun configureLabPaths() {
    System.getenv("LAB_WORKTREE")?.let { tree ->
        System.setProperty("klause.workspace.root", tree)
        System.setProperty("klause.bench.minizinc", Path.of(tree, LabInputs.RUNTIME, "bin/minizinc").toString())
    }
    System.getenv("LAB_CORPUS")?.let { System.setProperty("klause.bench.corpusCache", it) }
    System.setProperty("klause.bench.cache", "false")
}

internal object KlauseLabSuite : BenchSuite {
    override fun describe() = BenchDescriptor(
        "klause-bench",
        "Frozen catalog workloads and installed CLI solve records",
    )

    override fun plan(request: BenchRequest): List<BenchCase> {
        val settings = LabSettings(request.parameters)
        val selectedProblems = settings.selections.flatMap { KlauseCli.selectProblems(it) }
            .distinctBy { it.suite to it.ref.name }
        val compilerTrees = mutableMapOf<Path, String>()
        return selectedProblems.flatMap { selected ->
            val ref = selected.ref
            require(ref.source !is ProblemSource.InCode) { "Lab subprocess solves require file-backed inputs" }
            val inputs = LabInputs.resolve(ref, settings.inputOnly)
            val trees = LabInputs.treeHashes(inputs, compilerTrees)
            settings.engines.flatMap { engine ->
                val variant = if (settings.engines.size > 1) "$engine/" else ""
                settings.seeds.flatMap { seed ->
                    (0 until settings.repeats).flatMap { repeat ->
                        settings.controls.map { control ->
                            val suffix = if (control.isEmpty()) "" else "/$control"
                            BenchCase(
                                "${selected.suite}/${ref.name}/$engine/$seed/$repeat$suffix",
                                settings.caseParameters(engine, seed, control),
                                settings.resources(),
                                inputs = inputs,
                                attributes = trees + mapOf(
                                    "suite" to selected.suite,
                                    "comparisonKey" to "${selected.suite}/${ref.name}/$variant$seed/$repeat$suffix",
                                    "pairKey" to "${selected.suite}/${ref.name}/$variant$seed/$repeat",
                                    "propagationControl" to control,
                                    "seed" to seed.toString(),
                                    "budgetMs" to settings.timeout.toString(),
                                    "problem" to ref.name,
                                    "collection" to collectionOf(ref),
                                    "family" to (ref.family ?: ref.name.substringBefore('/')),
                                    "format" to ref.format.name,
                                    "category" to ref.category.name,
                                    "license" to ref.license,
                                    "expected" to ref.expected.toString(),
                                    "tags" to ref.tags.sorted().joinToString(","),
                                    "recordSchema" to "solver-v1",
                                    "repeat" to repeat.toString(),
                                    "validationPolicy" to "reported-result-v1",
                                ),
                            )
                        }
                    }
                }
            }
        }.also { require(it.isNotEmpty()) { "Selection matched no problems" } }
    }

    internal fun solveRecord(case: BenchCase, context: BenchContext): SolveRecord {
        LabInputs.verify(case, context)
        fun source(input: BenchInput) = ProblemSource.Prepared(LabInputs.path(input, context).toFile())
        val includes = case.inputs.filter { it.role == "include" }.map { source(it).file }
        val ref = ProblemRef(
            case.attributes.getValue("problem"),
            Format.valueOf(case.attributes.getValue("format")),
            source(case.inputs.single { it.role == "model" }).copy(includes = includes),
            Category.valueOf(case.attributes.getValue("category")),
            Expected.Unknown,
            data = case.inputs.singleOrNull { it.role == "data" }?.let(::source),
        )
        val p = case.parameters
        System.setProperty("klause.bench.mzn.timeoutSec", p.getValue("compile-timeout-sec"))
        val settings = SolverInvocation.Settings(
            engine = p.getValue("engine").takeUnless { it == "default" },
            processors = case.resources.cpus,
            seed = p.getValue("seed").toLong(),
            params = p.filterKeys { it.startsWith("param.") }.map { (k, v) -> "${k.removePrefix("param.")}=$v" },
            lp = p["lp"],
            presolve = p["presolve"],
            exact = p["exact"]?.toBooleanStrict() ?: false,
            captureDirectory = context.outputDirectory.resolve("report").toFile(),
            completeAssignment = p["complete-assignment"]?.toBooleanStrict() ?: false,
            measureResources = p.getValue("measurement") != "none",
        )
        val budget = Budget(p.getValue("timeout-ms").toLong())
        val record = if (ref.format == Format.JSON_SCHEMA) {
            val reason = "JSON schema is an in-process catalog format; the installed CLI has no schema frontend"
            SolveMetric.loadFailureRecord(
                ref,
                settings,
                budget,
                Instant.now().toString(),
                Reports.readGitSha(),
                IllegalArgumentException(reason),
            ).copy(
                command = "UNSUPPORTED",
                stats = mapOf("unsupported" to reason),
                buildProvenance = InstalledBuild.current,
            )
        } else {
            SolveMetric.solveOne(ref, budget, settings, context.outputDirectory.resolve("report").toFile())
        }
        val provenance = record.buildProvenance?.let { build ->
            build.copy(
                runtimeOptions = build.runtimeOptions +
                    ("lab.cli.args" to Files.readString(context.outputDirectory.resolve("cli.args"))),
            )
        }
        val retained = record.copy(
            comparisonControls = comparisonControls(case) + buildMap {
                provenance?.let { build ->
                    put("runtimeFingerprint", build.copy(
                        distribution = emptyMap(), harness = emptyMap(),
                        runtimeOptions = build.runtimeOptions - "KLAUSE_CLI_OPTS" - "KLAUSE_CLI_PREFLIGHT_OPTS",
                    ).fingerprint)
                }
                record.compilerIdentity?.let { compiler ->
                    put("compilerFingerprint", com.eignex.klause.bench.metric.sha256(
                        compiler.copy(executable = "", standardLibrary = "").toString().toByteArray(),
                    ))
                }
            },
            buildProvenance = provenance,
            buildFingerprint = provenance?.fingerprint,
            stats = record.stats + mapOf(
                "labMeasurement" to p.getValue("measurement"),
                "labDebug" to p.getValue("debug"),
            ),
        )
        val recordPath = context.outputDirectory.resolve("report/${Reports.fileStem(ref.name)}.json")
        Files.writeString(recordPath, Reports.json.encodeToString(SolveRecord.serializer(), retained))
        return retained
    }

    internal fun comparisonControls(case: BenchCase): Map<String, String> = buildMap {
        val p = case.parameters
        put("policy", "matched-source-v1")
        put("series", p["comparison-series"] ?: "sdk-v1")
        put("solverSeed", p.getValue("seed"))
        put("compilerSeed", p["compiler-seed"] ?: "1")
        put("solverBudgetMs", p.getValue("timeout-ms"))
        put("compilerTimeoutSeconds", p.getValue("compile-timeout-sec"))
        put("caseTimeoutSeconds", case.resources.timeoutSeconds.toString())
        put("cpus", case.resources.cpus.toString())
        put("memoryMiB", case.resources.memoryMiB.toString())
        for (key in listOf("heap-mib", "gc", "debug", "measurement", "complete-assignment")) {
            put(key, p.getValue(key))
        }
        put("exact", p["exact"] ?: "false")
        put("deadlinePolicy", "cli-inclusive-v1")
        case.attributes["labInputDigest"]?.let { put("inputDigest", it) }
    }

    internal fun failure(record: SolveRecord): String? = record.stats["loadError"] ?: record.stats["errorMessage"]
        ?: when {
            record.command == "ERROR" -> "CLI solve failed"

            record.stats["exit"]?.toIntOrNull()?.let { it != 0 } == true && "unsupported" !in record.stats &&
                record.stats["killed"] != "hard-timeout" ->
                "CLI exited ${record.stats["exit"]} after publishing its result"

            else -> null
        }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        require(case.parameters["input-only"] != "true") {
            "Input-only plans must run through the lab reference backend"
        }
        val report = context.reportPath()
        Files.createDirectories(report)
        val input = context.outputDirectory.resolve("input.json")
        Files.writeString(input, BenchProtocol.json.encodeToString(BenchCase.serializer(), case))
        val environment = LabRuntime.environment(case, context)
        var record: SolveRecord? = null
        val error = runCatching {
            LabInputs.verify(case, context)
            val exit = LabRuntime.process(
                context,
                listOf(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx512m",
                    "--enable-native-access=ALL-UNNAMED",
                    "--add-modules=jdk.incubator.vector",
                    "-cp",
                    System.getProperty("java.class.path"),
                    "com.eignex.klause.bench.lab.LabAdapterKt",
                    "solve-record",
                    input.toString(),
                    report.toString(),
                ),
                environment,
            )
            val file = report.resolve("solve-record.json")
            record = if (Files.isRegularFile(file)) {
                Reports.json.decodeFromString<SolveRecord>(Files.readString(file))
            } else {
                null
            }
            record?.let(::failure) ?: if (exit != 0 || record == null) {
                "CLI adapter failed (exit $exit); inspect solve.log"
            } else {
                null
            }
        }.getOrElse { "CLI adapter failed: ${it.message}" }
        val metrics = record?.let(::metrics).orEmpty() + LabRuntime.resourceMetrics(report)
        val artifacts = context.artifacts() + listOf(
            "input.json",
            "runtime.json",
            "cli.args",
            "preflight.args",
            "solve.log",
        )
            .filter { Files.isRegularFile(context.outputDirectory.resolve(it)) }.map { context.artifact(it) }
        return BenchResult(
            case.id,
            if (error == null) BenchStatus.SUCCESS else BenchStatus.FAILED,
            metrics = metrics,
            artifacts = artifacts,
            details = record?.let { Reports.json.encodeToJsonElement(SolveRecord.serializer(), it) as JsonObject },
            message = error,
        )
    }

    internal fun metrics(record: SolveRecord): List<BenchMetric> = buildList {
        fun time(name: String, value: Long?) {
            value?.let { add(BenchMetric(name, it.toDouble(), "ms", MetricDirection.LOWER)) }
        }
        time("elapsed", record.elapsedMs)
        time("time-to-first-feasible", record.timeToFirstFeasibleMs)
        time("time-to-best", record.timeToBestMs)
        record.objective?.takeIf(Double::isFinite)?.let {
            add(
                BenchMetric(
                    "objective",
                    it,
                    "objective",
                    if (record.maximize) MetricDirection.HIGHER else MetricDirection.LOWER,
                ),
            )
        }
    }
}
