package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemSets
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.InstanceClassifier
import com.eignex.klause.bench.metric.SolveMetric
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.MZN_COMPATIBILITY_POLICY
import com.eignex.klause.bench.runner.MiniZincCompatibility
import com.eignex.klause.bench.source.CorpusCache
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.bench.source.CorpusUnavailable
import com.eignex.klause.bench.source.ProblemKind
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The bench commands klause-lab runs: `select` names problems, `solve-one` solves one and writes its
 * [SolveMetric] record, `provenance` captures the installed build, and `corpus` maintains the cache.
 * Experiments, references and comparisons live in klause-lab.
 */
object BenchCli {
    private const val MIB = 1024L * 1024

    private const val MIB_COLUMN_WIDTH = 14

    /** CLI entry point dispatching bench subcommands. */
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            dispatch(args)
        } catch (failure: CorpusUnavailable) {
            System.err.println("${failure.message}: ${failure.cause?.javaClass?.simpleName}")
            exitProcess(75)
        }
    }

    private fun dispatch(args: Array<String>) {
        when (val cmd = args.firstOrNull() ?: "list") {
            "list", "--list", "help", "--help" -> if (args.size > 1) listProblems(args[1]) else printListing()

            "select" -> printSelection(args.drop(1))

            "solve-one" -> solveOne(args.drop(1))

            "provenance" -> {
                val output = args.drop(1).singleOrNull()?.takeIf { it.startsWith("out=") }?.substringAfter('=')
                require(!output.isNullOrBlank()) { "Use provenance out=<file>" }
                InstalledBuild.writeManifest(File(output))
            }


            "corpus" -> corpus(args.drop(1))

            else ->
                error(
                    "unknown command '$cmd' " +
                        "(commands: select, solve-one, provenance, corpus, list)",
                )
        }
    }

    /** `corpus compress [<dir>]`: compress the plain instances under [dir] (default: the corpus cache) in
     *  place. Safe to interrupt and re-run; see [CorpusFiles.compressTree]. `corpus gc` applies the cache cap
     *  and `corpus status` lists the cached collections; see [CorpusCache]. */
    private fun corpus(args: List<String>) {
        when (args.firstOrNull()) {
            "compress" -> corpusCompress(args.getOrNull(1)?.let { File(it) } ?: CorpusFetcher.cacheRoot)
            "gc" -> corpusGc()
            "status" -> printCorpusStatus(CorpusFetcher.cache())
            else -> error("usage: corpus compress [<dir>] | corpus gc | corpus status")
        }
    }

    private fun corpusCompress(root: File) {
        require(root.isDirectory) { "no corpus directory at $root" }
        println("=== corpus compress: $root ===")
        val done = CorpusFiles.compressTree(root, ::println)
        CorpusFetcher.cache().recordSizesUnder(root)
        println(
            "compressed ${done.files} instance(s): ${done.plainBytes / MIB} MiB -> ${done.packedBytes / MIB} MiB",
        )
    }

    private fun corpusGc() {
        val cap = CorpusCache.configuredCapBytes()
        println("=== corpus gc: ${CorpusFetcher.cacheRoot} (cap ${cap?.let { "${it / MIB} MiB" } ?: "off"}) ===")
        val cache = CorpusFetcher.cache()
        val evicted = cache.enforce().evicted
        evicted.forEach { println("evicted ${it.id} (${it.bytes / MIB} MiB)") }
        println("evicted ${evicted.size} collection(s)")
        printCorpusStatus(cache)
    }

    private fun printCorpusStatus(cache: CorpusCache) {
        val entries = cache.collections()
        for (e in entries.asReversed()) {
            val used = Instant.ofEpochMilli(e.lastUsedMillis).truncatedTo(ChronoUnit.SECONDS)
            println("${mibColumn(e.bytes)}  $used  ${e.id}")
        }
        println("${mibColumn(entries.sumOf { it.bytes })}  total, ${entries.size} collection(s)")
    }

    private fun mibColumn(bytes: Long): String = "${bytes / MIB} MiB".padStart(MIB_COLUMN_WIDTH)

    /** Print the [filterArgs] selection one JSON object per line: the suite and problem that name each instance
     *  exactly for `solve-one`, its source collection (`collection`), family, format and category. Resolving
     *  a dynamic suite fetches its collection, so a selection also leaves every instance it names on disk.
     *  `files=true` materializes plain `model` and `data` files and MiniZinc `includeDirs`, which lets a runner
     *  outside the bench solve the instance. */
    private fun printSelection(filterArgs: List<String>) {
        val f = filterArgs.filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val features = f["features"] == "true"
        val files = f["files"] == "true"
        val changedCollections = HashSet<String>()
        for (selected in selectProblems(f)) {
            val line = buildJsonObject {
                put("suite", selected.suite)
                put("problem", selected.ref.name)
                put("collection", collectionOf(selected.ref))
                put("family", selected.family.substringAfter(':'))
                put("format", selected.ref.format.name)
                put("category", selected.ref.category.name)
                if (selected.suite == "mzn-bench-compatible-v1" && f["set"] == null) {
                    put("selectionPolicy", MZN_COMPATIBILITY_POLICY)
                    put("compilerIdentity", Reports.json.encodeToJsonElement(MiniZincCompatibility.compiler))
                }
                if (files) {
                    val model = CorpusFetcher.resolve(selected.ref.source)
                    val data = selected.ref.data?.let(CorpusFetcher::resolve)
                    put("model", CorpusFiles.unpack(model).absolutePath)
                    data?.let { put("data", CorpusFiles.unpack(it).absolutePath) }
                    if (CorpusFiles.isCompressed(model) || data?.let(CorpusFiles::isCompressed) == true) {
                        changedCollections += collectionOf(selected.ref)
                    }
                    putJsonArray("includeDirs") {
                        CorpusFetcher.includeDirs(selected.ref.source).forEach { add(it.absolutePath) }
                    }
                }
                if (features) {
                    InstanceClassifier.classify(selected.ref)?.let { feat ->
                        put("structure", feat.structure)
                        put("logic", feat.logic)
                        putJsonArray("themes") { feat.themes.sorted().forEach { add(it) } }
                    }
                }
            }
            println(line)
        }
        val cache = CorpusFetcher.cache()
        for (collection in changedCollections) if (cache.isPresent(collection)) cache.recordSize(collection)
    }

    /** Solve exactly one problem, `suite=<id> problem=<name>` as `select` prints it, writing its record to
     *  `out=<dir>`. The suite resolves uncapped, so any instance a selection could name is found. Exits non-zero
     *  only when the problem is unknown; one that fails to load or to solve still writes its record, saying why.
     *  Keys it does not know are ignored. */
    private fun solveOne(args: List<String>) {
        val f = args.filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val suite = requireNotNull(f["suite"]) { "solve-one needs suite=<id>" }
        val name = requireNotNull(f["problem"]) { "solve-one needs problem=<name>" }
        val out = requireNotNull(f["out"]) { "solve-one needs out=<dir>" }
        val ref = Catalog.uncapped(suite).problems.singleOrNull { it.name == name }
            ?: error("no problem '$name' in suite '$suite'")
        val settings = SolverInvocation.Settings(
            engine = f["engine"]?.let(::parseEngine),
            processors = f["processors"]?.toIntOrNull(),
            seed = f["solver-seed"]?.toLongOrNull() ?: SolverInvocation.DEFAULT_SEED,
            params = args.filter { it.startsWith("param=") }.map { it.substringAfter('=') },
            lp = f["lp"],
            presolve = f["presolve"],
            exact = f["exact"]?.toBooleanStrict() ?: false,
            captureDirectory = File(out, "diagnostics").takeIf {
                args.any { argument -> argument.startsWith("param=portfolio-work=") }
            },
        )
        val budget = f["timeout"]?.toLongOrNull()?.let { Budget(it) } ?: Budget()
        val record = SolveMetric.solveOne(ref, budget, settings, File(out))
        println("${record.problem}: feasible=${record.feasible} objective=${record.objective} proven=${record.proven}")
    }

    /** Map an `engine=` alias to a klause-cli `-e` value (fixed | cp | mixed | ls | alns). */
    private fun parseEngine(name: String): String = when (name.lowercase()) {
        "cp", "backtrack", "bt" -> "cp"
        "ls", "localsearch", "local-search" -> "ls"
        "mixed", "portfolio", "pf" -> "mixed"
        "alns", "lns", "hybrid-lns" -> "alns"
        "fixed", "fd" -> "fixed"
        else -> error("engine must be fixed|cp|mixed|ls|alns, got '$name'")
    }

    /** Build the selection from filters: suites (`core` expands to the in-process core; static-only unless
     *  named) → category/tag/name filter → per-family caps → overall cap. Dynamic suites resolve
     *  uncapped, so `per-family`/`seed` sample each corpus by its provider's own family key; a suite's default
     *  per-family cap applies only when `per-family` is unset. Families are counted per suite, so two suites
     *  sharing a family name do not share its cap. `kind=cop|csp` is checked only on the instances a cap
     *  reaches (via [ProblemKind]), so a capped selection fills its cap with the requested kind without reading
     *  every source of a large corpus. */
    internal fun selectProblems(f: Map<String, String>): List<SelectedProblem> {
        val suiteIds = f["suite"]?.split(",")?.map { it.trim() }?.flatMap { expandSuiteIds(it) }
            ?: Catalog.suites.map { it.id }
        var candidates = f["set"]?.let { sets ->
            setProblems(
                sets.split(",").map { it.trim() },
                f["suite"]?.let { suiteIds.toSet() },
            )
        }
            ?: suiteIds.flatMap { id ->
                val default = Catalog.defaultPerFamily(id)
                Catalog.uncapped(id).problems.map { SelectedProblem(id, it, default) }
            }
        f["category"]?.split(",")?.map { Category.valueOf(it.trim().uppercase()) }?.toSet()?.let { cats ->
            candidates = candidates.filter { it.ref.category in cats }
        }
        f["tag"]?.split(",")?.map { it.trim() }?.toSet()?.let { tags ->
            candidates = candidates.filter { it.ref.tags.any { t -> t in tags } }
        }
        // Comma-separated name patterns are ORed; each is a substring or * glob.
        f["name"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { pats ->
            candidates = candidates.filter { c -> pats.any { matches(it, c.ref.name) } }
        }
        val wantCop = f["kind"]?.let(::parseKind)
        val capped = BenchSelection.capFamilies(
            candidates,
            perFamily = f["per-family"]?.toIntOrNull(),
            seed = f["seed"]?.toLongOrNull(),
            compatible = { candidate ->
                f["set"] != null || candidate.suite != "mzn-bench-compatible-v1" || compatible(candidate)
            },
            accept = { wantCop == null || ProblemKind.isCop(it) == wantCop },
        )
        // Families are already capped; what is left is the round-robin interleave and the overall `max`.
        val sel = CorpusSelection.Selection(maxInstances = f["max"]?.toIntOrNull())
        // Format balancing water-fills the total cap across formats; unset uses the family-global cap.
        return when (f["balance"]?.lowercase()) {
            null -> CorpusSelection.applySelectionBy(capped, sel) { it.family }
            "format" -> CorpusSelection.applyBalancedBy(capped, sel, { it.family }) { it.ref.format }
            else -> error("balance must be 'format', got '${f["balance"]}'")
        }
    }

    private fun compatible(candidate: SelectedProblem): Boolean =
        MiniZincCompatibility.admit(candidate.suite, candidate.ref)

    /** The problems of the named sets, restricted to [suites] when given. A set is selected whole: no suite's
     *  default per-family cap applies to it, only an explicit `per-family`. */
    private fun setProblems(names: List<String>, suites: Set<String>?): List<SelectedProblem> {
        val entries = ProblemSets.load(names).filter { suites == null || it.suite in suites }
        val bySuite = entries.map { it.suite }.distinct().associateWith { id ->
            Catalog.uncapped(
                id,
            ).problems.associateBy { it.name }
        }
        val missing = entries.filter { bySuite.getValue(it.suite)[it.problem] == null }
        require(missing.isEmpty()) {
            "${missing.size} set problems are in no suite, first ${missing.take(
                3,
            ).joinToString { "${it.suite}/${it.problem}" }}"
        }
        return entries.map { SelectedProblem(it.suite, bySuite.getValue(it.suite).getValue(it.problem)) }
    }

    /** `kind=cop` keeps optimization problems, `kind=csp` keeps satisfaction problems. */
    private fun parseKind(kind: String): Boolean = when (kind.lowercase()) {
        "cop", "opt", "optimization" -> true
        "csp", "sat", "satisfaction" -> false
        else -> error("kind must be cop|csp, got '$kind'")
    }

    /** Expand a suite token: `core` → every in-process core suite; otherwise the named suite. */
    private fun expandSuiteIds(token: String): List<String> = when (token) {
        "core" -> Targets.IN_PROCESS_CORE
        else -> listOf(token)
    }

    private fun matches(pattern: String, name: String): Boolean = if ('*' in pattern) {
        // Escape each literal segment between `*`s (Regex.escape wraps in \Q…\E, so escaping the
        // whole pattern then substituting `*` doesn't work), and join with `.*`.
        val rx = pattern.split('*').joinToString(".*") { Regex.escape(it) }
        Regex("^$rx$").containsMatchIn(name)
    } else {
        name.contains(pattern)
    }

    private fun listProblems(suite: String) {
        val s = Catalog.suite(suite)
        println("=== suite '${s.id}' — ${s.problems.size} problems ===")
        s.problems.forEach { println("  ${it.name.padEnd(28)} [${it.format}/${it.category}] expected=${it.expected}") }
    }

    private fun printListing() {
        println("Suites:")
        for (s in Catalog.suites) println("  ${s.id.padEnd(22)} ${s.problems.size} problems — ${s.description}")
        for (d in Catalog.dynamicSuites) println("  ${d.id.padEnd(22)} (discovered) — ${d.description}")
        println(
            """
            |
            |Usage:
            |  bench select [filters…]               the selection as JSON lines (suite, problem, collection, …)
            |  bench solve-one suite= problem= out=<dir> […]   solve one instance with klause-cli, writing its record
            |  bench provenance out=<file>           capture installed build/runtime once for immutable campaigns
            |  bench corpus compress [<dir>]         zstd-compress the plain instances in the corpus cache, in place
            |  bench corpus gc | corpus status       apply the corpus cache cap, or list the cached collections
            |  bench list [<suite>]                  list suites, or problems in a suite
            |
            |select:    suite=a,b (suite=core = in-process core) set=a,b kind=cop|csp category=SAT,OPTIMIZATION
            |           tag=… name=<glob>[,…] (comma=OR) per-family=N max=N seed=N balance=format
            |           files=true (model/data/includeDirs) features=true (structure/logic/themes)
            |solve-one: engine=fixed|cp|mixed|ls|alns processors=N solver-seed=N timeout=<ms>
            |           lp=off|conservative|default|aggressive[±id] presolve=off|conservative|default|aggressive[,±pass]
            |           param=key=value (repeatable klause-cli --param) exact=true (klause-cli --exact)
            |
            |Experiments, comparisons, references, witness checks and profiling run through klause-lab.
            """.trimMargin(),
        )
    }
}
