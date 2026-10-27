package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.metric.SolveMetric
import com.eignex.klause.bench.metric.SolveRecord
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.target.BenchCli as KlauseCli
import com.eignex.klause.bench.target.collectionOf
import com.eignex.lab.bench.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject

fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    System.getenv("LAB_WORKTREE")?.let { System.setProperty("klause.workspace.root", it) }
    System.getenv("LAB_CORPUS")?.let { System.setProperty("klause.bench.corpusCache", it) }
    if (args.firstOrNull() == "solve-record") {
        require(args.size == 3)
        val case = BenchProtocol.json.decodeFromString<BenchCase>(Files.readString(Path.of(args[1])))
        val output = Path.of(args[2])
        val record = KlauseLabSuite.solveRecord(case, BenchContext(output.parent,
            worktree = Path.of(requireNotNull(System.getenv("LAB_WORKTREE"))),
            corpusDirectory = System.getenv("LAB_CORPUS")?.let(Path::of)))
        Files.writeString(output.resolve("solve-record.json"), Reports.json.encodeToString(SolveRecord.serializer(), record))
        check(KlauseLabSuite.failure(record) == null) { KlauseLabSuite.failure(record).orEmpty() }
    } else BenchCli.execute(KlauseLabSuite, args, stdout = protocolOutput)
}

internal object KlauseLabSuite : BenchSuite {
    private val selectors = setOf("suite", "set", "name", "category", "tag", "kind", "per-family", "seed", "max", "balance")
    private val options = setOf("engines", "solver-seeds", "repeats", "cpus", "memory-mib", "timeout-ms", "lp", "presolve", "exact")

    override fun describe() = BenchDescriptor("klause-bench", "Catalog selection and installed CLI solves with complete solve records")

    override fun plan(request: BenchRequest): List<BenchCase> {
        val p = request.parameters
        require(p.keys.all { it in selectors || it in options || it.startsWith("param.") }) { "Unknown Klause bench parameter" }
        p["exact"]?.toBooleanStrict()
        val cpus = (p["cpus"] ?: "1").toInt().also { require(it > 0) }
        val timeout = (p["timeout-ms"] ?: "10000").toLong().also { require(it in 1..300_000_000) }
        val memory = (p["memory-mib"] ?: "4096").toLong().also { require(it >= 2048) }
        val repeats = (p["repeats"] ?: "1").toInt().also { require(it in 1..10000) }
        val seeds = (p["solver-seeds"] ?: "1").split(',').map { it.toLong() }.distinct()
        val engines = (p["engines"] ?: "default").split(',').distinct().also { require(it.none(String::isBlank)) }
        val selection = p.filterKeys { it in selectors }.ifEmpty { mapOf("suite" to "dimacs-core", "name" to "php4") }
        return KlauseCli.selectProblems(selection).flatMap { selected ->
            val ref = selected.ref
            require(ref.source !is ProblemSource.InCode) { "Lab subprocess solves require file-backed inputs" }
            val inputs = buildList {
                val model = CorpusFetcher.resolve(ref.source)
                add(input(model.toPath(), "model"))
                ref.data?.let { add(input(CorpusFetcher.resolve(it).toPath(), "data")) }
                if (ref.format == Format.MINIZINC) {
                    // Preserve relative includes beside the model as well as collection search directories.
                    add(input(model.parentFile.toPath(), "include"))
                    CorpusFetcher.includeDirs(ref.source).forEach { add(input(it.toPath(), "include")) }
                }
            }.distinct()
            engines.flatMap { engine -> seeds.flatMap { seed -> (0 until repeats).map { repeat ->
                BenchCase("${selected.suite}/${ref.name}/$engine/$seed/$repeat",
                    p.filterKeys { it in setOf("lp", "presolve", "exact") || it.startsWith("param.") } + mapOf(
                        "engine" to engine, "seed" to seed.toString(), "timeout-ms" to timeout.toString()),
                    BenchResources(cpus, memory, (timeout * 2 + 20_999) / 1000 + 120),
                    inputs = inputs,
                    attributes = mapOf("suite" to selected.suite, "problem" to ref.name,
                        "collection" to collectionOf(ref), "family" to (ref.family ?: ref.name.substringBefore('/')),
                        "format" to ref.format.name, "category" to ref.category.name, "recordSchema" to "solver-v1"))
            } } }
        }.also { require(it.isNotEmpty()) { "Selection matched no problems" } }
    }

    private fun input(path: Path, role: String): BenchInput {
        val actual = path.toRealPath()
        val source = CorpusFetcher.workspaceRoot().toPath().toRealPath()
        val corpus = CorpusFetcher.cacheRoot.toPath().toAbsolutePath().normalize()
        val root = if (actual.startsWith(source)) source else corpus
        require(actual.startsWith(root)) { "Input outside source and corpus: $actual" }
        val hash = if (Files.isRegularFile(actual)) MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(actual))
            .joinToString("") { "%02x".format(it) } else null
        return BenchInput(root.relativize(actual).toString().replace('\\', '/'),
            if (root == source) BenchInputRoot.SOURCE else BenchInputRoot.CORPUS, role, hash)
    }

    internal fun solveRecord(case: BenchCase, context: BenchContext): SolveRecord {
        fun source(input: BenchInput): ProblemSource.Prepared {
            val root = if (input.root == BenchInputRoot.SOURCE) requireNotNull(context.worktree) else requireNotNull(context.corpusDirectory)
            return ProblemSource.Prepared(root.resolve(input.path).toFile())
        }
        val includes = case.inputs.filter { it.role == "include" }.map { source(it).file }
        val ref = ProblemRef(case.attributes.getValue("problem"), Format.valueOf(case.attributes.getValue("format")),
            source(case.inputs.single { it.role == "model" }).copy(includes = includes), Category.valueOf(case.attributes.getValue("category")), Expected.Unknown,
            data = case.inputs.singleOrNull { it.role == "data" }?.let(::source))
        val p = case.parameters
        val settings = SolverInvocation.Settings(p.getValue("engine").takeUnless { it == "default" }, case.resources.cpus,
            p.getValue("seed").toLong(), p.filterKeys { it.startsWith("param.") }.map { (k, v) -> "${k.removePrefix("param.")}=$v" },
            p["lp"], p["presolve"], p["exact"]?.toBooleanStrict() ?: false)
        return SolveMetric.solveOne(ref, Budget(p.getValue("timeout-ms").toLong()), settings, context.reportPath().toFile())
    }

    internal fun failure(record: SolveRecord): String? = record.stats["loadError"] ?: record.stats["errorMessage"] ?:
        if (record.command == "ERROR") "CLI solve failed" else null

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val report = context.reportPath()
        val input = context.outputDirectory.resolve("input.json")
        Files.writeString(input, BenchProtocol.json.encodeToString(BenchCase.serializer(), case))
        val runtime = "-Xmx${case.resources.memoryMiB - 1536}m -XX:ActiveProcessorCount=${case.resources.cpus}"
        val exit = context.process(listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx512m", "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector", "-cp",
            System.getProperty("java.class.path"), "com.eignex.klause.bench.lab.LabAdapterKt", "solve-record", input.toString(), report.toString()),
            "solve.log", environment = mapOf("JAVA_OPTS" to "", "KLAUSE_DEBUG" to "true", "KLAUSE_CLI_OPTS" to runtime,
                "KLAUSE_CLI_PREFLIGHT_OPTS" to runtime, "LAB_WORKTREE" to requireNotNull(context.worktree).toString()) +
                (context.corpusDirectory?.let { mapOf("LAB_CORPUS" to it.toString()) } ?: emptyMap()))
        val recordFile = report.resolve("solve-record.json")
        val record = if (Files.isRegularFile(recordFile)) Reports.json.decodeFromString<SolveRecord>(Files.readString(recordFile)) else null
        val failure = record?.let(::failure) ?: if (exit != 0 || record == null) "CLI adapter failed (exit $exit); inspect solve.log" else null
        return BenchResult(case.id, if (failure == null) BenchStatus.SUCCESS else BenchStatus.FAILED,
            metrics = listOfNotNull(record?.elapsedMs?.let { BenchMetric("elapsed", it.toDouble(), "ms", MetricDirection.LOWER) },
                record?.timeToBestMs?.let { BenchMetric("time-to-best", it.toDouble(), "ms", MetricDirection.LOWER) }),
            artifacts = context.artifacts() + context.artifact("solve.log", "text/plain"),
            details = record?.let { Reports.json.encodeToJsonElement(SolveRecord.serializer(), it) as JsonObject },
            message = failure)
    }
}
